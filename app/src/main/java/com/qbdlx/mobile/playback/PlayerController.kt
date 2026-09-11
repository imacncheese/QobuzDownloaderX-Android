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
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val error: String? = null,
) {
    val progress: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    val hasItem: Boolean get() = current != null
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
        val mediaId = if (index in 0 until player.mediaItemCount) {
            player.getMediaItemAt(index).mediaId
        } else {
            null
        }
        _state.update {
            it.copy(
                current = queue.firstOrNull { q -> q.trackId == mediaId } ?: it.current,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { d -> d > 0 } ?: 0L,
                queueSize = player.mediaItemCount,
                queueIndex = index.coerceAtLeast(0),
                hasNext = player.hasNextMediaItem(),
                hasPrevious = player.hasPreviousMediaItem(),
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

        // Left unset when unknown so the service resolves lazily; ExoPlayer will
        // report buffering until a URI is swapped in.
        streamUrl?.takeIf { it.isNotBlank() }?.let { builder.setUri(it) }
        return builder.build()
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
