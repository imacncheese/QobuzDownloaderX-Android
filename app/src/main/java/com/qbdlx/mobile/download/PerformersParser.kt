package com.qbdlx.mobile.download

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Track

/**
 * Parses Qobuz's role-annotated `performers` string into artist names.
 *
 * Ported from QobuzDownloaderX `Helpers/QobuzDownloaderXMOD/PerformersParser.cs`.
 *
 * The API returns this field as a `" - "` separated list of
 * `Name, Role, Role` entries, for example:
 *
 *     "Radiohead, MainArtist - Colin Greenwood, AssociatedPerformer, Bass - ..."
 *
 * Taking the whole string (or naively splitting on commas) produces garbage in
 * the ARTIST tag, which is what a vague "artist tag" bug report turns out to be.
 * Only entries whose roles mark them as a main artist or a featured artist
 * belong in the tag.
 */
object PerformersParser {

    /** Roles that make someone the main track artist. */
    private val MAIN_ARTIST_ROLES = setOf(
        "Artist",          // Qobuz uses this plain variant on "Various Artists" releases
        "Main Artist",
        "MainArtist",
        "main-artist",
        "Performer",
        "Primary",
    )

    /** Roles that make someone a featured artist. */
    private val FEATURED_ARTIST_ROLES = setOf(
        "Featured Artist",
        "FeaturedArtist",
        "featured-artist",
        "Featuring",
        "Featuring Vocals",
        "Featuring Vocalist",
    )

    /** Titles already advertising a feature must not get one appended again. */
    private val FEAT_PATTERNS = listOf(
        "featuring ", " ft.", "(feat ", "(feat.", "[feat ", "[feat.",
        " feat ", " feat. ", "[ft ", "[ft.", "(ft ", "(ft.",
    )

    data class Artists(
        val main: List<String>,
        val featured: List<String>,
    ) {
        val isEmpty: Boolean get() = main.isEmpty() && featured.isEmpty()
    }

    fun parse(track: Track?): Artists {
        if (track == null) return Artists(emptyList(), emptyList())

        val raw = track.performers?.takeIf { it.isNotBlank() }
            ?: return Artists(
                main = listOfNotNull(track.performer?.name?.takeIf { it.isNotBlank() }),
                featured = emptyList(),
            )

        val main = LinkedHashSet<String>()
        val featured = LinkedHashSet<String>()

        for (segment in rebuildSegments(raw)) {
            val comma = segment.indexOf(',')
            if (comma < 0) continue

            val name = segment.substring(0, comma).trim()
            if (name.isEmpty()) continue

            val roles = segment.substring(comma + 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            when {
                roles.any { it.equalsAny(MAIN_ARTIST_ROLES) } -> main += name
                roles.any { it.equalsAny(FEATURED_ARTIST_ROLES) } -> featured += name
            }
        }

        // Nobody carries a recognised role: fall back to the singular performer.
        if (main.isEmpty() && featured.isEmpty()) {
            val fallback = track.performer?.name ?: track.artist?.name
            return Artists(listOfNotNull(fallback?.takeIf { it.isNotBlank() }), emptyList())
        }

        // A name must never appear as both.
        featured.removeAll(main.toSet())

        return Artists(main.toList(), featured.toList())
    }

    /**
     * Merges main and featured artists the way QobuzDownloaderX does: main
     * artists first, then featured ones, joined with a locale-aware " & ".
     *
     * When the track title already advertises a feature, featured artists are
     * dropped — Qobuz frequently reports them as primary artists in that case,
     * and the title already says who they are.
     */
    fun trackArtistString(track: Track?): String? {
        val parsed = parse(track)
        val titleHasFeat = track?.title?.let { title ->
            FEAT_PATTERNS.any { title.contains(it, ignoreCase = true) }
        } ?: false

        // If the title names the feature, drop anyone whose name it mentions.
        val main = if (titleHasFeat && parsed.main.size > 1) {
            val normalizedTitle = normalizeForMatch(track?.title.orEmpty())
            listOf(parsed.main.first()) + parsed.main.drop(1).filterNot {
                normalizedTitle.contains(normalizeForMatch(it))
            }
        } else {
            parsed.main
        }

        val featured = if (titleHasFeat) emptyList() else parsed.featured

        val merged = joinArtists(main + featured)
        return merged?.let(::normalizeFeaturingWords)
            ?: track?.performer?.name?.takeIf { it.isNotBlank() }
            ?: track?.artist?.name?.takeIf { it.isNotBlank() }
    }

    /**
     * Album-level artist list.
     *
     * `album.artists[]` carries `roles` (usually `["main-artist"]`), which is the
     * authoritative source. The plain `album.artist` field is the fallback.
     */
    fun albumArtistList(album: Album?): List<String> {
        if (album == null) return emptyList()

        val main = album.artists
            .filter { artist -> artist.roles?.any { it.equalsAny(MAIN_ARTIST_ROLES) } == true }
            .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }

        val featured = album.artists
            .filter { artist -> artist.roles?.any { it.equalsAny(FEATURED_ARTIST_ROLES) } == true }
            .mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }

        if (main.isNotEmpty()) return (main + featured).distinct()

        // No role information (common for compilations): name-only entries.
        val named = album.artists.mapNotNull { it.name?.takeIf { n -> n.isNotBlank() } }
        if (named.isNotEmpty()) return named.distinct()

        return listOfNotNull(album.artist?.name?.takeIf { it.isNotBlank() })
    }

    fun albumArtistString(album: Album?): String =
        joinArtists(albumArtistList(album))
            ?.let(::normalizeFeaturingWords)
            ?: "Unknown Artist"

    // ------------------------------------------------------------- internals

    /**
     * Splits on " - " and re-attaches orphan leading tokens, mirroring the C#
     * parser: a name containing no comma is only meaningful when the following
     * segment is itself an artist entry.
     */
    private fun rebuildSegments(raw: String): List<String> {
        val segments = normalizePerformersLineBreaks(raw)
            .split(" - ")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val rebuilt = mutableListOf<String>()
        var i = 0
        while (i < segments.size) {
            val current = segments[i]
            if (!current.contains(',')) {
                val next = segments.getOrNull(i + 1)
                if (next != null && isArtistSegment(next)) {
                    rebuilt += "$current - $next"
                    i += 2
                    continue
                }
                // Orphan token with no role: drop it.
                i++
                continue
            }
            rebuilt += current
            i++
        }
        return rebuilt
    }

    /**
     * Normalises line breaks in the performers field.
     *
     * A single newline means the value was hard-wrapped mid-entry, so it becomes
     * a space ("Real\nArtist, MainArtist" is one person). A blank line genuinely
     * separates entries and becomes the " - " delimiter.
     */
    internal fun normalizePerformersLineBreaks(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(Regex("""\n[ \t]*\n"""), " - ")
        .replace(Regex("""\s*\n\s*"""), " ")
        .trim()

    private fun isArtistSegment(segment: String): Boolean {
        val comma = segment.indexOf(',')
        if (comma < 0) return false
        return segment.substring(comma + 1)
            .split(',')
            .map { it.trim() }
            .any { it.equalsAny(MAIN_ARTIST_ROLES) || it.equalsAny(FEATURED_ARTIST_ROLES) }
    }

    private fun Set<String>.containsIgnoreCase(value: String): Boolean =
        any { it.equals(value, ignoreCase = true) }

    private fun String.equalsAny(candidates: Set<String>): Boolean =
        candidates.containsIgnoreCase(this)

    /** " & "-joins with an Oxford-free separator, matching the desktop app. */
    private fun joinArtists(names: List<String>): String? {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return when (cleaned.size) {
            0 -> null
            1 -> cleaned.first()
            2 -> "${cleaned[0]} & ${cleaned[1]}"
            else -> cleaned.dropLast(1).joinToString(", ") + " & " + cleaned.last()
        }
    }

    /** Normalises the many ways a feature is spelled. */
    internal fun normalizeFeaturingWords(input: String): String = input
        .replace(" Featuring ", " Feat. ")
        .replace(" featuring ", " Feat. ")
        .replace(" Feat ", " Feat. ")
        .replace(" feat ", " Feat. ")

    /** Lowercase, accent-free-ish comparison form used for title/name matching. */
    private fun normalizeForMatch(input: String): String =
        input.lowercase().replace(Regex("""[^a-z0-9]+"""), " ").trim()

    /** Album-level artists for a track: prefer the track's album, then its own. */
    fun albumArtistStringFor(track: Track?): String =
        albumArtistString(track?.album)
}
