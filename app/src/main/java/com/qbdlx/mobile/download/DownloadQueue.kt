package com.qbdlx.mobile.download

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Quality presets, mapped to Qobuz `format_id` values (same as the C# app). */
enum class Quality(val formatId: String, val label: String, val extension: String) {
    MP3_320("5", "MP3 320 kbps", "mp3"),
    FLAC_LOW("6", "FLAC 16-bit / 44.1 kHz", "flac"),
    FLAC_MID("7", "FLAC 24-bit / <=96 kHz", "flac"),
    FLAC_HIGH("27", "FLAC 24-bit / >96 kHz (Hi-Res)", "flac");

    companion object {
        fun fromId(id: String?): Quality =
            entries.firstOrNull { it.formatId == id } ?: FLAC_HIGH

        /**
         * Ordered fallback chain for when the requested quality is not licensed
         * for a given track. Qobuz returns success for format 5 (MP3) for
         * essentially everything streamable, so it terminates the chain.
         */
        fun fallbackChain(requested: Quality): List<Quality> = when (requested) {
            FLAC_HIGH -> listOf(FLAC_HIGH, FLAC_MID, FLAC_LOW, MP3_320)
            FLAC_MID -> listOf(FLAC_MID, FLAC_LOW, MP3_320)
            FLAC_LOW -> listOf(FLAC_LOW, MP3_320)
            MP3_320 -> listOf(MP3_320)
        }
    }
}

enum class DownloadStatus { QUEUED, RESOLVING, DOWNLOADING, TAGGING, COMPLETED, FAILED, CANCELLED }

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val trackId: String,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val albumId: String?,
    val trackNumber: Int,
    val discNumber: Int,
    val durationSeconds: Int,
    val coverUrl: String?,
    val track: Track?,
    val album: Album?,
    val requestId: String? = null,
    val isAlbumItem: Boolean = false,
) {
    val displayName: String get() = if (trackNumber > 0) "$trackNumber. $title" else title
}

data class DownloadState(
    val item: DownloadItem,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val speedBytesPerSecond: Long = 0L,
    val progressPercent: Int = 0,
    val resolvedQuality: Quality? = null,
    val savedTo: String? = null,
    val warning: String? = null,
    val error: String? = null,
) {
    val isTerminal: Boolean
        get() = status == DownloadStatus.COMPLETED ||
            status == DownloadStatus.FAILED ||
            status == DownloadStatus.CANCELLED
}

/**
 * In-memory queue shared between the UI and [DownloadService].
 * Single source of truth so progress survives screen rotation.
 */
object DownloadQueue {

    /** Guards the read-modify-write sections of [_items]; see [claimNext]. */
    private val lock = Any()

    private val counter = AtomicInteger(0)
    private val _items = MutableStateFlow<List<DownloadState>>(emptyList())
    val items: StateFlow<List<DownloadState>> = _items.asStateFlow()

    /** Ids the user asked to cancel; the engine polls this. */
    private val cancelled = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun enqueue(requests: List<DownloadItem>): Int {
        val withIds = requests.map { it.copy(requestId = it.requestId ?: nextId()) }
        var added = 0
        synchronized(lock) {
            val current = _items.value
            // Skip anything already queued or in flight for the same track.
            val existing = current.filter { !it.isTerminal }.map { it.item.trackId }.toSet()
            val fresh = withIds.filterNot { it.trackId in existing }
                .map { DownloadState(item = it, status = DownloadStatus.QUEUED) }
            added = fresh.size
            if (fresh.isNotEmpty()) _items.value = current + fresh
        }
        return added
    }

    /**
     * Atomically takes the next queued item and marks it as started.
     *
     * This must be atomic: with more than one concurrent worker, a plain
     * "read the list then start" sequence lets two workers pick the same item
     * and download the same track twice.
     *
     * @return the claimed item, or null when nothing is queued.
     */
    fun claimNext(): DownloadState? = synchronized(lock) {
        val current = _items.value
        val index = current.indexOfFirst { it.status == DownloadStatus.QUEUED }
        if (index < 0) return null
        val claimed = current[index].copy(status = DownloadStatus.RESOLVING, error = null)
        _items.value = current.toMutableList().also { it[index] = claimed }
        claimed
    }

    fun update(id: String, transform: (DownloadState) -> DownloadState) {
        synchronized(lock) {
            _items.value = _items.value.map { if (it.item.id == id) transform(it) else it }
        }
    }

    fun cancel(id: String) {
        cancelled.add(id)
        update(id) { it.copy(status = DownloadStatus.CANCELLED) }
    }

    fun isCancelled(id: String): Boolean = id in cancelled

    fun clearFinished() {
        synchronized(lock) {
            val removed = _items.value.filter { it.isTerminal }.map { it.item.id }
            removed.forEach { cancelled.remove(it) }
            _items.value = _items.value.filterNot { it.isTerminal }
        }
    }

    fun retry(id: String) {
        cancelled.remove(id)
        update(id) {
            it.copy(
                status = DownloadStatus.QUEUED,
                bytesDownloaded = 0,
                progressPercent = 0,
                error = null,
                savedTo = null,
            )
        }
    }

    fun pendingCount(): Int = _items.value.count { !it.isTerminal }

    fun snapshot(): List<DownloadState> = _items.value

    private fun nextId(): String = "req-" + counter.incrementAndGet()
}
