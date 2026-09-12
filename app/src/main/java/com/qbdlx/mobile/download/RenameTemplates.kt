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

    private val HOLLOW_PARENS = Regex("""\(\s*\)""")
    private val HOLLOW_SQUARES = Regex("""\[\s*\]""")
    private val HOLLOW_BRACES = Regex("""\{\s*\}""")

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
     *
     * Separators and brackets are then trimmed from the ends, but only brackets
     * that are genuinely unbalanced. Trimming "anything bracket-like" at each end
     * stripped a perfectly good trailing `]`, and an earlier version trimmed
     * different characters at each end, which removed a leading "(" and left its
     * matching ")" behind - that is how a download with no album produced a folder
     * named `) [FLAC 24-0.1kHz]`.
     */
    internal fun tidy(input: String): String {
        // Drop bracket pairs an empty placeholder hollowed out.
        //
        // Every closing bracket is escaped. A bare "}" is accepted by the JVM's
        // regex engine but rejected by the one on Android, which threw
        // PatternSyntaxException and failed the whole download.
        val raw = input
            .replace(HOLLOW_PARENS, "")
            .replace(HOLLOW_SQUARES, "")
            .replace(HOLLOW_BRACES, "")
            .let { multiSpace.replace(it, " ") }
            .trim()

        if (raw.isEmpty()) return ""

        // Strip a leading run of separators, and a trailing run. Only separators,
        // never brackets: blindly trimming brackets at the ends is what removed a
        // valid trailing "]".
        val separators = charArrayOf(' ', '-', '_', '.', ',')
        val body = raw.trimStart(*separators).trimEnd(*separators).trim()
        if (body.isEmpty()) return ""

        // Walk the string tracking bracket depth. Extras are the brackets that
        // never get closed, so they are what to remove from the ends.
        var depth = 0
        for (c in body) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth > 0) depth--
            }
        }

        var s = body

        // A leading closer means whatever opened it was also stripped, so the
        // group is hollow: drop everything up to the character after it.
        while (s.isNotEmpty() && s.first() in ")]}") {
            s = s.drop(1).trimStart(*separators)
        }

        // Loopers to the left of the content have nothing to close. Drop the
        // leading run, which is what an emptied `(%year%)` leaves behind.
        while (s.isNotEmpty() && s.first() in "([{") {
            s = s.drop(1).trimStart(*separators)
        }

        // Unclosed openers at the tail, after any closers they did have.
        var guard = 0
        while (guard++ < 16) {
            val trailing = s.takeLastWhile { it in ")]}" }
            val rest = s.dropLast(trailing.length)
            val opens = rest.takeLastWhile { it in "([{" }
            if (opens.isEmpty()) {
                // A closer that is now the last character and has no opener left
                // means its group was emptied too.
                if (s.isNotEmpty() && s.last() in ")]}" && !rest.contains('(') &&
                    !rest.contains('[') && !rest.contains('{')
                ) {
                    s = s.dropLast(1).trimEnd(*separators)
                    continue
                }
                break
            }
            s = rest.dropLast(opens.length).trimEnd(*separators)
        }

        s = s.trimStart(*separators).trimEnd(*separators).trim()

        // Collapse separator runs that are now adjacent.
        s = s.replace(Regex("""\s*-\s*-\s*"""), " - ")
            .replace(Regex("""\s*_\s*_\s*"""), "_")

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

    /**
     * Qobuz reports `maximum_sampling_rate` in kHz - 44.1, 96, 192 - which is why
     * the desktop app appends "kHz" straight onto the raw value. Dividing by 1000
     * again turned every Hi-Res album into "24-0.1kHz". Anything at or above 1000
     * can only be Hz, so it is scaled rather than printed as "44100kHz".
     */
    fun fmtRate(rate: Double): String {
        val khz = if (rate >= 1000.0) rate / 1000.0 else rate
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
        // Deliberately not text/plain. Android's document providers append the
        // extension registered for a MIME type when the file name does not
        // already end with it, so a .lrc written as text/plain lands on disk as
        // "track.lrc.txt". Nothing claims application/x-lrc, so the name is left
        // exactly as given.
        "lrc" -> "application/x-lrc"
        else -> "application/octet-stream"
    }
}
