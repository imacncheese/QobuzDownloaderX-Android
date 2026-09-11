package com.qbdlx.mobile.download

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Track
import java.io.File

/**
 * Builds the ordered list of artwork URLs to try for a release.
 *
 * Qobuz exposes the same cover at several renditions, selected by the suffix
 * before `.jpg` (`…_600.jpg`). The desktop application offers these in a
 * dropdown (`qbdlxForm.Designer.cs`):
 *
 *     org, max, 600, 300, 150, 100, 50
 *
 * The default here is [Size.MAX], because the embedded cover is the copy that
 * ends up inside the audio file for good — embedding a small rendition loses
 * detail permanently, while the file can always be re-tagged smaller later.
 *
 * The order is deliberately largest-first and the caller keeps probing until a
 * rendition actually returns image bytes, so this is correct even if a given
 * release only publishes some renditions.
 */
object ArtworkUrls {

    /** Renditions Qobuz publishes, largest first. [suffix] is what goes after `_`. */
    enum class Size(val suffix: String, val label: String, val isMaximum: Boolean) {
        MAX("max", "Maximum available", true),
        ORG("org", "Original", true),
        PX_2048("2048", "2048 px", false),
        PX_1400("1400", "1400 px", false),
        PX_1000("1000", "1000 px", false),
        PX_600("600", "600 px", false),
        PX_300("300", "300 px", false),
        PX_150("150", "150 px", false),
        PX_100("100", "100 px", false),
        PX_50("50", "50 px", false);

        companion object {
            fun fromSuffix(suffix: String?): Size =
                entries.firstOrNull { it.suffix == suffix } ?: MAX
        }
    }

    /**
     * True when [bytes] is too small to be a real cover.
     *
     * Qobuz's CDN answers unsupported renditions in ways that are not always a
     * clean 404 — a placeholder or error page would otherwise be embedded as the
     * album art. Nothing legitimate at these dimensions is this small.
     */
    private const val MIN_PLAUSIBLE_BYTES = 4096

    fun looksLikeRealArtwork(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < MIN_PLAUSIBLE_BYTES) return false
        // Must actually be an image, not an error document.
        val jpeg = bytes.size > 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
        val png = bytes.size > 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()
        return jpeg || png
    }

    /**
     * Rewrites a Qobuz artwork URL to a specific rendition.
     *
     * Handles the three shapes the API actually returns: `…_600.jpg`,
     * `…_600x600.jpg`, and a bare path with no suffix (treated as already the
     * full-size image).
     */
    fun withSize(url: String, size: Size): String {
        val pattern = Regex("""_\d+(?:x\d+)?\.(jpg|jpeg|png)$""", RegexOption.IGNORE_CASE)
        return if (pattern.containsMatchIn(url)) {
            pattern.replace(url, "_${size.suffix}.jpg")
        } else {
            // No rendition suffix: a bare URL is the original image.
            url
        }
    }

    /** Every distinct image URL worth trying, for one artwork URL. */
    private fun variantsFor(url: String, sizes: List<Size>): List<String> {
        if (url.isBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        // A URL with no suffix is already the full-size original: try it first.
        val hasSuffix = Regex("""_\d+(?:x\d+)?\.(jpg|jpeg|png)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)
        if (!hasSuffix) out += url
        sizes.forEach { out += withSize(url, it) }
        return out.toList()
    }

    /**
     * Candidate URLs for an item, ordered by descending preference.
     *
     * The item's own cover URL comes first, then the album's `large` and `small`
     * fields, since those are separate CDN objects that may exist when the first
     * does not.
     */
    fun candidates(
        coverUrl: String?,
        album: Album?,
        track: Track?,
        sizes: List<Size> = defaultPreference(),
    ): List<String> {
        val out = LinkedHashSet<String>()
        variantsFor(coverUrl.orEmpty(), sizes).forEach { out += it }
        variantsFor(album?.image?.large.orEmpty(), sizes).forEach { out += it }
        variantsFor(album?.image?.small.orEmpty(), sizes).forEach { out += it }
        variantsFor(track?.album?.image?.large.orEmpty(), sizes).forEach { out += it }
        variantsFor(track?.album?.image?.small.orEmpty(), sizes).forEach { out += it }
        return out.toList()
    }

    /**
     * Order of renditions to attempt.
     *
     * [preferred] is tried first (the user's chosen setting); when it is not a
     * maximum size, the largest renditions are still tried afterwards so a
     * missing preferred rendition degrades upward rather than straight down.
     */
    fun defaultPreference(preferred: Size = Size.MAX): List<Size> =
        (listOf(preferred) + Size.entries.filter { it != preferred }).distinct()

    /** Local file name for a saved cover, e.g. `Cover.jpg`. */
    fun coverFile(directory: File): File = File(directory, "Cover.jpg")
}
