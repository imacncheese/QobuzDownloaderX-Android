package com.qbdlx.mobile.lyrics

/**
 * One timestamped lyric line.
 *
 * [text] is empty for the blank lines LRC files use to separate verses. Those
 * are kept rather than dropped so the spacing a lyricist intended survives, and
 * because removing them would shift nothing but read worse.
 */
data class LyricLine(
    val timeMs: Long,
    val text: String,
)

/**
 * Lyrics for one track: the timestamped lines when the source had them, and the
 * plain block when it did not.
 */
data class Lyrics(
    val synced: List<LyricLine> = emptyList(),
    val plain: String? = null,
) {

    val hasSynced: Boolean get() = synced.isNotEmpty()
    val hasPlain: Boolean get() = !plain.isNullOrBlank()
    val isEmpty: Boolean get() = !hasSynced && !hasPlain

    /**
     * Unsynchronised text.
     *
     * Falls back to the timestamped lines stripped of their timing, so a file
     * always gets a usable lyrics block even when only LRC was available.
     */
    val plainText: String
        get() = plain?.takeIf { it.isNotBlank() }
            ?: synced.joinToString("\n") { it.text }

    /**
     * Index of the line to highlight at [positionMs], or -1 before the first
     * line starts. Binary search: this is called on every position tick, and a
     * linear scan over a few hundred lines at 10 Hz is wasted work.
     */
    fun indexAt(positionMs: Long): Int {
        if (synced.isEmpty()) return -1
        var low = 0
        var high = synced.size - 1
        var found = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (synced[mid].timeMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /**
     * Regenerates LRC text.
     *
     * The source file is not stored verbatim because the parsed form is what the
     * player syncs against; embedding a rebuilt file keeps the tag and the UI
     * showing the same thing.
     */
    fun toLrc(): String = synced.joinToString("\n") { line ->
        val minutes = line.timeMs / 60_000
        val seconds = (line.timeMs % 60_000) / 1000
        val hundredths = (line.timeMs % 1000) / 10
        "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.text)
    }
}
