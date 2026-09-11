package com.qbdlx.mobile.download

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Track

/**
 * Filename / folder templating using the same `%placeholder%` vocabulary as
 * ImAiiR/QobuzDownloaderX, so templates people already use keep working.
 */
object RenameTemplates {

    const val DEFAULT_ARTIST_TEMPLATE = "%artistname%"
    const val DEFAULT_ALBUM_TEMPLATE = "%albumtitle% (%year%) [%format% %bitdepth%-%samplerate%]"
    const val DEFAULT_TRACK_TEMPLATE = "%tracknumber% - %tracktitle%"

    private val illegal = Regex("""[\\/:*?"<>|\x00-\x1f]""")
    private val multiSpace = Regex("""\s{2,}""")

    /** Characters that are illegal in FAT/exFAT paths, plus trailing dots/spaces. */
    fun sanitize(input: String, maxLength: Int = 180): String {
        var s = illegal.replace(input, "_")
        s = s.trim().trimEnd('.')
        if (s.length > maxLength) s = s.substring(0, maxLength).trimEnd('.', ' ')
        return s.ifBlank { "Unknown" }
    }

    fun applyArtist(album: Album, track: Track? = null): String =
        expand(DEFAULT_ARTIST_TEMPLATE, album, track, "flac")

    fun expand(template: String, album: Album?, track: Track?, formatExt: String): String {
        var t = template
        val releaseArtists = releaseArtists(album)
        val trackArtists = trackArtists(track) ?: releaseArtists

        t = t.replace("%artistname%", releaseArtists)
        t = t.replace("%trackartist%", trackArtists)
        t = t.replace("%albumartist%", releaseArtists)

        t = t.replace("%albumtitle%", albumTitle(album))
        t = t.replace("%albumid%", album?.idString ?: "")
        t = t.replace("%albumurl%", album?.url ?: "")
        t = t.replace("%albumgenre%", album?.genre?.name ?: "")
        t = t.replace("%label%", multiSpace.replace(album?.label?.name ?: "", " "))
        t = t.replace("%copyright%", album?.copyright ?: "")
        t = t.replace("%upc%", album?.upc ?: "")
        t = t.replace("%releasedate%", album?.release_date_original?.trim() ?: "")
        t = t.replace("%year%", year(album?.release_date_original))
        t = t.replace("%releasetype%", releaseType(album?.product_type))
        t = t.replace("%format%", formatExt.uppercase().trimStart('.'))
        t = t.replace("%formatwithquality%", formatExt.uppercase().trimStart('.'))
        t = t.replace("%formatwithhiresquality%", formatExt.uppercase().trimStart('.'))
        t = t.replace("%mediatype%", "Digital Media")

        t = t.replace("%tracktitle%", track?.title ?: album?.title ?: "")
        t = t.replace("%trackid%", track?.idString ?: "")
        t = t.replace("%tracknumber%", (track?.track_number ?: 0).toString().padStart(2, '0'))
        t = t.replace("%tracknumberraw%", (track?.track_number ?: 0).toString())
        t = t.replace("%isrc%", track?.isrc ?: "")
        t = t.replace("%trackpa%", if (explicitFor(track, album)) "Explicit" else "Clean")

        // Bit depth / sample rate come from the track when Qobuz supplies them and
        // fall back to the album. Using the album alone produced folders named
        // "24-0.0kHz" whenever album.maximum_sampling_rate was absent.
        val bitDepth = track?.maximum_bit_depth?.takeIf { it > 0 }
            ?: album?.maximum_bit_depth?.takeIf { it > 0 }
        val sampleRate = track?.maximum_sampling_rate?.takeIf { it > 0 }
            ?: album?.maximum_sampling_rate?.takeIf { it > 0 }

        t = t.replace("%trackbitdepth%", bitDepth?.toString() ?: "")
        t = t.replace("%tracksamplerate%", sampleRate?.let { fmtRate(it) } ?: "")
        t = t.replace("%bitdepth%", bitDepth?.toString() ?: "")
        t = t.replace("%samplerate%", sampleRate?.let { fmtRate(it) } ?: "")

        val disc = track?.media_number ?: 1
        val discCount = track?.media_count ?: 1
        t = t.replace("%discnumber%", disc.toString().padStart(2, '0'))
        t = t.replace("%disctotal%", discCount.toString())
        t = t.replace("%tracktotal%", (album?.tracks_count ?: 0).toString())

        t = t.replace("%trackpaenclosed%", if (explicitFor(track, album)) "(Explicit)" else "(Clean)")
        t = t.replace("%albumtitlepa%", if (explicitFor(track, album)) "Explicit" else "Clean")

        t = t.replace(Regex("""%[a-z0-9_]+%"""), "")
        return tidy(t)
    }

    /**
     * Cleans up the artefacts left behind by empty placeholders, so a template
     * like `%format% %bitdepth%-%samplerate%` with no bit depth collapses to
     * "FLAC" rather than "FLAC -".
     */
    internal fun tidy(input: String): String {
        var s = multiSpace.replace(input, " ").trim()
        // Drop separator runs left dangling at either end.
        s = s.trimStart(' ', '-', '_', '.', ',', '(').trimEnd(' ', '-', '_', '.', ',', '(', ')')
        // Collapse separator runs that now sit next to each other.
        s = s.replace(Regex("""\s*-\s*-\s*"""), " - ")
        s = s.replace(Regex("""\s*_\s*_\s*"""), "_")
        s = s.replace(Regex("""\(\s*\)"""), "")
        return multiSpace.replace(s, " ").trim()
    }

    private fun explicitFor(track: Track?, album: Album?): Boolean =
        track?.parental_warning == true || album?.parental_warning == true

    /**
     * Expands a template into path segments.
     *
     * Sanitisation happens *before* splitting so that a `/` coming from
     * metadata (a track literally titled "Bad/Name") cannot inject extra
     * directory levels into the output path.
     */
    fun expandToPath(template: String, album: Album?, track: Track?, formatExt: String): List<String> =
        sanitize(expand(template, album, track, formatExt), maxLength = 400)
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "." && it != ".." }
            .map { sanitize(it) }

    fun albumTitle(album: Album?): String {
        val title = album?.title?.trimEnd().orEmpty()
        val version = album?.version?.trim()?.takeIf { it.isNotBlank() }
        return if (version == null) title else "$title ($version)"
    }

    /**
     * Album-level artist names, honouring the `roles` annotations Qobuz supplies
     * on `album.artists[]` rather than blindly joining every credited name.
     */
    fun releaseArtists(album: Album?): String = PerformersParser.albumArtistString(album)

    /**
     * Track-level artist names, parsed from the role-annotated `performers`
     * string. See [PerformersParser].
     */
    fun trackArtists(track: Track?): String? = PerformersParser.trackArtistString(track)

    private fun year(date: String?): String =
        date?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) } ?: ""

    private fun releaseType(productType: String?): String =
        productType?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }
            ?.lowercase()
            ?.replaceFirstChar { it.uppercase() }
            ?: ""

    fun fmtRate(rate: Double): String {
        val khz = rate / 1000.0
        return if (khz % 1.0 == 0.0) "${khz.toInt()}kHz" else "%.1fkHz".format(khz)
    }

    fun fmtBitDepth(depth: Int?): String = depth?.let { "$it-bit" } ?: ""

    fun extensionFor(formatId: String): String = when (formatId) {
        "5" -> "mp3"
        else -> "flac"
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }
}
