package com.qbdlx.mobile.lyrics

/**
 * Outcome of a lyrics lookup.
 *
 * [Instrumental] and [NotFound] are separate because they mean different things
 * to a listener: an instrumental track has no lyrics by design, whereas a miss
 * might be worth retrying later or reporting.
 */
sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
    data class Error(val message: String) : LyricsResult
}

/**
 * What the lyrics panel shows.
 *
 * [visible] is part of the state rather than the composable so the panel
 * survives navigating away and back, the same way the queue does.
 */
data class LyricsUi(
    val visible: Boolean = false,
    val trackId: String? = null,
    val loading: Boolean = false,
    val result: LyricsResult? = null,
) {
    val lyrics: Lyrics? get() = (result as? LyricsResult.Found)?.lyrics
    val hasSynced: Boolean get() = lyrics?.hasSynced == true

    /** True when the lookup finished and there is nothing to show. */
    val isEmpty: Boolean get() = !loading && (result == null || result !is LyricsResult.Found)
}
