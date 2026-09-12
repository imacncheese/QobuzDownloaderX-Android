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

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                runCatching {
                    val c = future.get()
                    c.addListener(listener)
                    controller = c
                    publish(c)
                }.onFailure { Log.w(TAG, "could not connect to playback service", it) }
            },
            MoreExecutors.directExecutor(),
        )
    }

    fun release() {
        controller?.removeListener(listener)
        controller = null
        _state.value = PlaybackState()
    }

    // -------------------------------------------------------------- commands

    /** Replaces the queue and starts playing [startIndex]. */
    fun play(items: List<QueueItem>, startIndex: Int) {
        val c = controller ?: return
        if (items.isEmpty()) return

        queue = items
        val index = startIndex.coerceIn(0, items.lastIndex)

        val media = items.map { it.toMediaItem() }
        c.setMediaItems(media, index, 0L)
        c.prepare()
        c.play()
        _state.update { it.copy(error = null, current = items.getOrNull(index)) }

        // Resolve the starting track immediately; the rest follow as playback nears them.
        scope.launch { resolveInto(c, index) }
        preResolveUpcoming(c)
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        val c = controller ?: return
        // Restart the current track unless we are near its beginning.
        if (c.currentPosition > RESTART_THRESHOLD_MS) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun seekToIndex(index: Int) {
        val c = controller ?: return
        c.seekTo(index, 0L)
        scope.launch { resolveInto(c, index) }
    }

    fun stop() {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()
        queue = emptyList()
        _state.value = PlaybackState()
    }

    // -------------------------------------------------------- shuffle, repeat

    fun toggleShuffle() {
        val c = controller ?: return
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
        val c = controller ?: return
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
        val c = controller ?: return
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
        val c = controller ?: return
        if (from !in 0 until c.mediaItemCount) return
        val target = to.coerceIn(0, c.mediaItemCount - 1)
        if (from == target) return
        c.moveMediaItem(from, target)
        publish(c)
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        // Removing the last remaining item clears the player.
        c.removeMediaItem(index)
        publish(c)
    }

    /** Drops everything after the current track. */
    fun clearUpcoming() {
        val c = controller ?: return
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
     */
    private suspend fun resolveInto(c: Player, index: Int) {
        if (index !in 0 until c.mediaItemCount) return
        val item = c.getMediaItemAt(index)
        if (item.localConfiguration?.uri != null) return

        val trackId = item.mediaId
        val url = runCatching { resolveStreamUrl(trackId) }
            .onFailure { Log.w(TAG, "could not resolve stream URL for $trackId", it) }
            .getOrNull()
            ?: run {
                _state.update { it.copy(error = "Could not get a stream URL for this track.") }
                return
            }

        // Re-create the item with the resolved URI, preserving metadata.
        val withUri = item.buildUpon().setUri(Uri.parse(url)).build()
        val wasCurrent = c.currentMediaItemIndex == index
        val position = if (wasCurrent) c.currentPosition else 0L
        c.replaceMediaItem(index, withUri)
        if (wasCurrent) c.seekTo(position)

        queue = queue.map { if (it.trackId == trackId) it.copy(streamUrl = url) else it }
        Log.i(TAG, "resolved $trackId -> ${url.take(60)}")
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
        val ordered = (0 until player.mediaItemCount).mapNotNull { i ->
            val item = player.getMediaItemAt(i)
            queue.firstOrNull { it.trackId == item.mediaId } ?: item.toQueueItem()
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

    /** Rebuilds a [QueueItem] from a player media item. */
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
