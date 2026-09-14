package com.qbdlx.mobile.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.qbdlx.mobile.api.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One entry in the play queue.
 *
 * The Qobuz CDN URL is signed and short-lived, so it is resolved close to play
 * time rather than stored. [streamUrl] is filled in once known.
 */
data class QueueItem(
    val trackId: String,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val artworkUrl: String?,
    val durationSeconds: Int,
    val track: Track?,
    val streamUrl: String? = null,
)

/** The slice of player state the UI shows. */
data class PlaybackState(
    val current: QueueItem? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val queue: List<QueueItem> = emptyList(),
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasItem: Boolean get() = current != null

    /** The track that will play after [current], if any. */
    val upNext: QueueItem?
        get() = queue.getOrNull(queueIndex + 1)

    val repeatLabel: String
        get() = when (repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat one"
            Player.REPEAT_MODE_ALL -> "Repeat all"
            else -> "Repeat off"
        }
}

/**
 * App-side handle to the playback session.
 *
 * Playback itself lives in [PlaybackService] so audio survives backgrounding and
 * the lock screen; this class connects to it, mirrors its state into a
 * [StateFlow] for Compose, and resolves stream URLs on demand.
 *
 * @param resolveStreamUrl suspend function that turns a track id into a signed
 *   CDN URL. Supplied by the caller so this class stays free of API concerns.
 */
class PlayerController(
    private val context: Context,
    private val resolveStreamUrl: suspend (trackId: String) -> String,
) {

    private var controller: MediaController? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var queue: List<QueueItem> = emptyList()
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate
    )

    /** How many upcoming tracks to resolve ahead, smoothing track transitions. */
    private val resolveAhead = 3

    /**
     * Tracks currently having their stream URL resolved.
     *
     * Callers are generous — the initial play, the look-ahead, and every
     * media-item transition all ask — so without this the same track is resolved
     * several times over and the playlist is mutated once per attempt.
     */
    private val resolving = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** How many stream URLs to resolve at once when a queue is opened. */
    private val RESOLVE_BATCH = 8

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player)
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                preResolveUpcoming(player)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error", error)
            _state.update { it.copy(error = describe(error)) }
        }
    }

    /**
     * Notices the playback service going away.
     *
     * A MediaController does not reconnect on its own. Without this the app kept
     * using a dead controller: commands were applied to its own copy of the
     * playlist, so the UI showed a queue and a current track while the service
     * held nothing at all and no sound was ever produced. Nothing in the app
     * could recover from that except restarting it.
     */
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            Log.w(TAG, "playback service disconnected; will reconnect on the next command")
            this@PlayerController.controller = null
            connecting = null
        }
    }

    @Volatile
    private var connecting: CompletableDeferred<MediaController?>? = null

    fun connect() {
        if (controller != null) return
        val pending = connecting
        if (pending != null && !pending.isCompleted) return

        val deferred = CompletableDeferred<MediaController?>()
        connecting = deferred
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            // The disconnect listener is a builder option; a MediaController only
            // takes Player.Listener through addListener.
            .setListener(controllerListener)
            .buildAsync()
        future.addListener(
            {
                runCatching {
                    val c = future.get()
                    c.addListener(listener)
                    controller = c
                    publish(c)
                    deferred.complete(c)
                }.onFailure {
                    Log.w(TAG, "could not connect to playback service", it)
                    deferred.complete(null)
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    /**
     * The controller, connecting first if the service is not attached.
     *
     * Every command needs a live one: against a dead controller they appear to
     * work and quietly do nothing, which is how the app ended up believing it was
     * playing with an empty session behind it.
     */
    private suspend fun liveController(): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        controller = null
        connect()
        return connecting?.await()
    }

    /**
     * The controller, but only while it is genuinely attached.
     *
     * A disconnected controller still accepts commands and applies them to its
     * own copy of the playlist, so a command that silently does nothing looks
     * exactly like one that worked. Anything that finds it detached starts a
     * reconnect instead.
     */
    private fun activeController(): MediaController? {
        val c = controller ?: return null
        if (c.isConnected) return c
        controller = null
        connecting = null
        connect()
        return null
    }

    fun release() {
        controller?.removeListener(listener)
        controller = null
        connecting = null
        _state.value = PlaybackState()
    }

    // -------------------------------------------------------------- commands

    /**
     * Replaces the queue and starts playing the track at [startIndex].
     *
     * Every stream URL is resolved before the playlist is handed over, and only
     * the tracks that resolved are handed over at all. A media item with no URI
     * is not playable, and one unplayable item anywhere in a playlist set from a
     * MediaController is enough to make the session fall back to the first track
     * and to drop items from the queue. Resolving everything up front is what
     * makes the requested track actually start, and because the playlist is
     * complete from the outset it never has to be mutated while playing.
     *
     * The cost is a burst of requests before playback begins.
     */
    suspend fun play(items: List<QueueItem>, startIndex: Int) {
        val c = liveController() ?: run {
            _state.update { it.copy(error = "Could not reach the playback service.") }
            return
        }
        if (items.isEmpty()) return

        val index = startIndex.coerceIn(0, items.lastIndex)

        // Show the tapped track straight away; resolving below is a round of
        // round trips before any sound can start.
        queue = items
        _state.update {
            it.copy(error = null, current = items.getOrNull(index), isBuffering = true)
        }

        val resolved = resolveAll(items)
        if (!currentCoroutineContext().isActive) return

        // Only playable items are handed over. One that could not be resolved has
        // no URI, and leaving any of those in the playlist makes the session fall
        // back to the first track again even when the requested one is fine.
        val playable = resolved.filter { it.streamUrl != null }
        val dropped = resolved.size - playable.size
        if (playable.isEmpty()) {
            _state.update {
                it.copy(isBuffering = false, error = "Could not get a stream URL for any of these tracks.")
            }
            return
        }

        // Keep the requested track playing: if its own URL failed, start at the
        // nearest track that did resolve, preferring the ones after it.
        val wanted = resolved[index].trackId
        val start = playable.indexOfFirst { it.trackId == wanted }
            .takeIf { it >= 0 }
            ?: resolved.take(index).count { it.streamUrl != null }.coerceAtMost(playable.lastIndex)

        queue = playable
        val media = playable.map { it.toMediaItem() }
        Log.i(
            TAG,
            "play: '${playable[start].title}' at $start of ${playable.size}" +
                if (dropped > 0) " ($dropped unavailable)" else "",
        )

        c.setMediaItems(media, start, 0L)
        c.prepare()
        c.play()
        _state.update { it.copy(current = playable.getOrNull(start), isBuffering = true) }
    }

    /**
     * Resolves every item's stream URL, with one retry for the ones that fail.
     *
     * Resolved in small batches rather than all at once, so a long playlist does
     * not fire hundreds of requests at the API simultaneously. The retry matters
     * because a single failure used to cost the whole queue its start position:
     * a batch of fifty regularly loses a few to a timeout or a 429.
     */
    private suspend fun resolveAll(items: List<QueueItem>): List<QueueItem> {
        suspend fun pass(batch: List<QueueItem>): List<QueueItem> = coroutineScope {
            batch.map { item ->
                async {
                    if (item.streamUrl != null) return@async item
                    val url = runCatching { resolveStreamUrl(item.trackId) }.getOrNull()
                    if (url == null) item else item.copy(streamUrl = url)
                }
            }.awaitAll()
        }

        val first = items.chunked(RESOLVE_BATCH).flatMap { pass(it) }
        val missing = first.filter { it.streamUrl == null }
        if (missing.isEmpty()) return first

        Log.i(TAG, "resolve: retrying ${missing.size} track(s)")
        val retried = missing.chunked(RESOLVE_BATCH).flatMap { pass(it) }
            .associateBy { it.trackId }
        return first.map { retried[it.trackId] ?: it }
    }

    fun togglePlayPause() {
        val c = activeController() ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        activeController()?.seekToNextMediaItem()
    }

    fun previous() {
        val c = activeController() ?: return
        // Restart the current track unless we are near its beginning.
        if (c.currentPosition > RESTART_THRESHOLD_MS) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        activeController()?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * The playback position right now.
     *
     * [PlaybackState.positionMs] only moves when the player reports an event, so
     * it is far too coarse to follow a lyric line. The lyrics view polls this
     * instead, which keeps the extra wake-ups out of the shared state flow that
     * the rest of the UI recomposes on.
     */
    fun livePositionMs(): Long = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L

    fun seekToIndex(index: Int) {
        val c = activeController() ?: return
        c.seekTo(index, 0L)
        scope.launch { resolveInto(c, index) }
    }

    fun stop() {
        val c = activeController() ?: return
        c.stop()
        c.clearMediaItems()
        queue = emptyList()
        _state.value = PlaybackState()
    }

    // -------------------------------------------------------- shuffle, repeat

    fun toggleShuffle() {
        val c = activeController() ?: return
        val enabled = !c.shuffleModeEnabled
        c.shuffleModeEnabled = enabled
        // The playing item must not change when shuffle is toggled, which
        // ExoPlayer handles; the queue order it reports is the shuffled one.
        Log.i(TAG, "shuffle ${if (enabled) "on" else "off"}")
        publish(c)
    }

    /**
     * Cycles off, all, one.
     *
     * Ordered so the common cases are one tap away: turning repeat all on to loop
     * an album, then repeat one for a single track, then off.
     */
    fun cycleRepeat() {
        val c = activeController() ?: return
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        Log.i(TAG, "repeat mode $next")
        publish(c)
    }

    // ------------------------------------------------------------ queue edits

    /** Jumps to a position in the queue as the user sees it. */
    fun jumpTo(index: Int) {
        val c = activeController() ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        scope.launch { resolveInto(c, index) }
        publish(c)
    }

    /**
     * Moves an item within the queue.
     *
     * ExoPlayer keeps the current item playing while its position changes, so no
     * explicit seek is needed.
     */
    fun moveInQueue(from: Int, to: Int) {
        val c = activeController() ?: return
        if (from !in 0 until c.mediaItemCount) return
        val target = to.coerceIn(0, c.mediaItemCount - 1)
        if (from == target) return
        c.moveMediaItem(from, target)
        publish(c)
    }

    fun removeFromQueue(index: Int) {
        val c = activeController() ?: return
        if (index !in 0 until c.mediaItemCount) return
        // Removing the last remaining item clears the player.
        c.removeMediaItem(index)
        publish(c)
    }

    /** Drops everything after the current track. */
    fun clearUpcoming() {
        val c = activeController() ?: return
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
        publish(c)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------- internals

    /**
     * Resolves the URL for the item at [index] and swaps it in.
     *
     * The item is queued without a URI, so ExoPlayer cannot start it until this
     * completes — the UI shows a buffering state meanwhile.
     *
     * Two things here are load bearing:
     *
     *  - One resolution per track at a time. Three things call this (the initial
     *    play, the look-ahead, and the media-item-transition event), so the same
     *    track used to be resolved two or three times over, and every one of them
     *    mutated the playlist again.
     *  - A seek by index rather than by position when the current item is the one
     *    being replaced. `replaceMediaItem` is remove-then-insert underneath, so
     *    replacing the playing item drifts the current index; seeking by position
     *    alone then applies to whatever item the player drifted onto. That is how
     *    tapping the third track ended up playing the first one.
     */
    private suspend fun resolveInto(c: Player, index: Int) {
        if (index !in 0 until c.mediaItemCount) return
        val item = c.getMediaItemAt(index)
        if (item.localConfiguration?.uri != null) return

        val trackId = item.mediaId
        if (!resolving.add(trackId)) return

        try {
            val url = runCatching { resolveStreamUrl(trackId) }
                .onFailure { Log.w(TAG, "could not resolve stream URL for $trackId", it) }
                .getOrNull()
                ?: run {
                    _state.update { it.copy(error = "Could not get a stream URL for this track.") }
                    return
                }

            // The queue may have been replaced while the URL was in flight, so
            // find the item again rather than trusting the index we came in with.
            val at = (0 until c.mediaItemCount)
                .firstOrNull { c.getMediaItemAt(it).mediaId == trackId }
                ?: return
            val target = c.getMediaItemAt(at)
            if (target.localConfiguration?.uri != null) return

            val withUri = target.buildUpon().setUri(Uri.parse(url)).build()
            val wasCurrent = c.currentMediaItemIndex == at
            val position = if (wasCurrent) c.currentPosition else 0L
            val indexBefore = c.currentMediaItemIndex

            c.replaceMediaItem(at, withUri)

            // Replacing an item can drag the current index onto the replaced
            // one, which is how resolving a look-ahead track used to yank
            // playback back to the top of the queue. Put the player back where
            // it was, by index and position, whenever that happens.
            if (c.currentMediaItemIndex != indexBefore) {
                val positionNow = if (wasCurrent) position else c.currentPosition
                Log.i(TAG, "resolve: index moved $indexBefore -> ${c.currentMediaItemIndex}, restoring")
                c.seekTo(indexBefore, positionNow.coerceAtLeast(0L))
            } else if (wasCurrent) {
                c.seekTo(position)
            }

            queue = queue.map { if (it.trackId == trackId) it.copy(streamUrl = url) else it }
            Log.i(TAG, "resolved $trackId -> ${url.take(60)}")
        } finally {
            resolving.remove(trackId)
        }
    }

    /** Resolves the current and next few tracks so transitions are smooth. */
    private fun preResolveUpcoming(c: Player) {
        val start = c.currentMediaItemIndex.coerceAtLeast(0)
        val end = (start + resolveAhead).coerceAtMost(c.mediaItemCount - 1)
        for (i in start..end) {
            scope.launch { resolveInto(c, i) }
        }
    }

    private fun publish(player: Player) {
        val index = player.currentMediaItemIndex

        // Read the queue back from the player rather than from our own list: after
        // a shuffle or a reorder the player's order is the truth, and after an app
        // restart our list is empty while the service is still playing. Metadata is
        // reconstructed from each item where the local list has no entry.
        //
        // Every position is kept, even when an item carries nothing to describe it
        // by. Dropping one would make this list shorter than the player's, and the
        // queue screen passes its row indices straight back to the player, so a
        // short list means removing, moving or jumping to the wrong track.
        val ordered = (0 until player.mediaItemCount).map { i ->
            val item = player.getMediaItemAt(i)
            queue.firstOrNull { it.trackId == item.mediaId }
                ?: item.toQueueItem()
                ?: QueueItem(
                    trackId = item.mediaId,
                    title = item.mediaMetadata.title?.toString().orEmpty().ifBlank { "Untitled" },
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                    albumTitle = item.mediaMetadata.albumTitle?.toString().orEmpty(),
                    artworkUrl = item.mediaMetadata.artworkUri?.toString(),
                    durationSeconds = 0,
                    track = null,
                )
        }

        _state.update {
            it.copy(
                current = ordered.getOrNull(index) ?: it.current,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { d -> d > 0 } ?: 0L,
                queueSize = player.mediaItemCount,
                queueIndex = index.coerceAtLeast(0),
                queue = ordered,
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
                shuffleEnabled = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
            )
        }
    }

    private fun QueueItem.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(albumTitle)
            .setArtworkUri(artworkUrl?.let(Uri::parse))
            .build()

        val builder = MediaItem.Builder()
            .setMediaId(trackId)
            .setMediaMetadata(metadata)
            // The player lives in the service, which outlives this controller. Any
            // field the UI needs is therefore carried on the MediaItem itself, so a
            // fresh controller can rebuild the queue after the app is restarted
            // rather than seeing an empty queue while audio keeps playing.
            .setTag(QueueTag(artworkUrl = artworkUrl, durationSeconds = durationSeconds))

        // Left unset when unknown so the service resolves lazily; ExoPlayer will
        // report buffering until a URI is swapped in.
        streamUrl?.takeIf { it.isNotBlank() }?.let { builder.setUri(it) }
        return builder.build()
    }

    /** Extra metadata the UI needs but ExoPlayer does not, carried on the item. */
    private data class QueueTag(
        val artworkUrl: String?,
        val durationSeconds: Int,
    )

    /** Rebuilds a [QueueItem] from a player media item, or null if it has no id. */
    private fun MediaItem.toQueueItem(): QueueItem? {
        if (mediaId.isBlank()) return null
        val tag = localConfiguration?.tag as? QueueTag
        return QueueItem(
            trackId = mediaId,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            albumTitle = mediaMetadata.albumTitle?.toString().orEmpty(),
            artworkUrl = tag?.artworkUrl ?: mediaMetadata.artworkUri?.toString(),
            durationSeconds = tag?.durationSeconds ?: 0,
            track = null,
            streamUrl = localConfiguration?.uri?.toString(),
        )
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "Network problem while streaming. Check your connection."

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "Qobuz refused the stream. Your session may have expired — sign in again."

        else -> e.message ?: "Playback failed (${e.errorCodeName})"
    }

    private companion object {
        const val TAG = "QbdlxPlayer"
        const val RESTART_THRESHOLD_MS = 3_000L
    }
}
