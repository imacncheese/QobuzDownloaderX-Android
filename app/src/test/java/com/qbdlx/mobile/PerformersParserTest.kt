package com.qbdlx.mobile

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.ArtistRef
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.download.PerformersParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ARTIST / ALBUMARTIST tags are derived from Qobuz's role-annotated
 * `performers` string. A naive split on commas puts engineers, mixers and
 * mastering engineers into the artist tag, which is the "artist tag is wrong"
 * class of bug.
 */
class PerformersParserTest {

    private fun track(
        performers: String? = null,
        title: String = "A Song",
        performerName: String? = null,
        album: Album? = null,
    ) = Track(
        id = JsonPrimitive("t1"),
        title = title,
        performers = performers,
        performer = performerName?.let { ArtistRef(name = it) },
        album = album,
    )

    // ------------------------------------------------------------ track artist

    @Test
    fun `only main artists reach the artist tag, not every credited role`() {
        val t = track(
            performers = "Radiohead, MainArtist - Colin Greenwood, AssociatedPerformer, Bass - " +
                "Jonny Greenwood, AssociatedPerformer, Guitar - Nigel Godrich, Producer",
        )
        assertEquals("Radiohead", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `the plain Artist role variant is treated as a main artist`() {
        // Qobuz uses the bare "Artist" role on many Various Artists releases.
        val t = track(performers = "Some Artist, Artist")
        assertEquals("Some Artist", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `performer and primary roles count as main artists`() {
        assertEquals(
            "Alice",
            PerformersParser.trackArtistString(track(performers = "Alice, Performer")),
        )
        assertEquals(
            "Bob",
            PerformersParser.trackArtistString(track(performers = "Bob, Primary")),
        )
        assertEquals(
            "Carol",
            PerformersParser.trackArtistString(track(performers = "Carol, main-artist")),
        )
    }

    @Test
    fun `multiple main artists are joined with an ampersand`() {
        val t = track(
            performers = "Alice, MainArtist - Bob, MainArtist",
        )
        assertEquals("Alice & Bob", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `featured artists are appended to the artist tag`() {
        val t = track(
            performers = "Alice, MainArtist - Guest, Featured Artist",
            title = "Some Song",
        )
        assertEquals("Alice & Guest", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `featured artists are not duplicated when the title already says feat`() {
        val t = track(
            performers = "Alice, MainArtist - Guest, Featured Artist",
            title = "Some Song (feat. Guest)",
        )
        // The title already advertises the feature, so it must not be appended.
        assertEquals("Alice", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `a main artist named in the title is not repeated`() {
        // Qobuz often reports the featured artist with the MainArtist role.
        val t = track(
            performers = "Alice, MainArtist - Guest, MainArtist",
            title = "Some Song (feat. Guest)",
        )
        assertEquals("Alice", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `featuring spellings are normalised`() {
        assertEquals(
            "Alice Feat. Bob",
            PerformersParser.normalizeFeaturingWords("Alice featuring Bob"),
        )
        assertEquals(
            "Alice Feat. Bob",
            PerformersParser.normalizeFeaturingWords("Alice Feat Bob"),
        )
    }

    @Test
    fun `missing performers falls back to the singular performer name`() {
        assertEquals(
            "Solo Artist",
            PerformersParser.trackArtistString(track(performerName = "Solo Artist")),
        )
    }

    @Test
    fun `a performers string with no recognised role falls back to the performer`() {
        val t = track(
            performers = "Someone, UnknownRole - Other, AnotherRole",
            performerName = "Fallback Artist",
        )
        assertEquals("Fallback Artist", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `composer is not treated as the track artist`() {
        val t = track(
            performers = "Composer Person, Composer - Real Artist, MainArtist",
        )
        val result = PerformersParser.trackArtistString(t)
        assertEquals("Real Artist", result)
        assertFalse("composer must not leak into ARTIST", result!!.contains("Composer Person"))
    }

    @Test
    fun `an orphan token is dropped when the next segment is not an artist`() {
        // "Orphan" has no comma, and the following segment carries nothing but a
        // production role, so there is no artist for it to belong to.
        val t = track(
            performers = "Orphan Name - Engineer Person, Engineer - Real Artist, MainArtist",
        )
        assertEquals("Real Artist", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `a single newline inside a name is a wrap, not a separator`() {
        val t = track(performers = "Real\nArtist, MainArtist")
        assertEquals("Real Artist", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `a blank line separates entries`() {
        val t = track(performers = "Alice, MainArtist\n\nBob, MainArtist")
        assertEquals("Alice & Bob", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `carriage returns are handled`() {
        val t = track(performers = "Real\r\nArtist, MainArtist")
        assertEquals("Real Artist", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `duplicate names are collapsed`() {
        val t = track(performers = "Alice, MainArtist - Alice, MainArtist")
        assertEquals("Alice", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `a name cannot be both main and featured`() {
        val t = track(performers = "Alice, MainArtist - Alice, Featured Artist")
        assertEquals("Alice", PerformersParser.trackArtistString(t))
    }

    @Test
    fun `three or more artists are joined with commas and a final ampersand`() {
        val t = track(
            performers = "A, MainArtist - B, MainArtist - C, MainArtist",
        )
        assertEquals("A, B & C", PerformersParser.trackArtistString(t))
    }

    // ------------------------------------------------------------ album artist

    private fun album(vararg artists: Pair<String, List<String>?>) = Album(
        id = JsonPrimitive("alb1"),
        title = "An Album",
        artists = artists.map { (name, roles) -> ArtistRef(name = name, roles = roles) },
        artist = artists.firstOrNull()?.let { ArtistRef(name = it.first) },
    )

    @Test
    fun `album artists come from entries marked main-artist`() {
        val a = album(
            "Radiohead" to listOf("main-artist"),
            "Nigel Godrich" to listOf("producer"),
        )
        assertEquals("Radiohead", PerformersParser.albumArtistString(a))
    }

    @Test
    fun `instrumental and production roles are excluded from album artists`() {
        val a = album(
            "The Band" to listOf("main-artist"),
            "Some Engineer" to listOf("engineer"),
            "A Mixer" to listOf("mixing-engineer"),
        )
        val result = PerformersParser.albumArtistString(a)
        assertEquals("The Band", result)
        assertFalse(result.contains("Engineer"))
        assertFalse(result.contains("Mixer"))
    }

    @Test
    fun `multiple main artists are joined`() {
        val a = album(
            "Alice" to listOf("main-artist"),
            "Bob" to listOf("main-artist"),
        )
        assertEquals("Alice & Bob", PerformersParser.albumArtistString(a))
    }

    @Test
    fun `album artists fall back to name-only entries when roles are absent`() {
        val a = album("No Role Artist" to null)
        assertEquals("No Role Artist", PerformersParser.albumArtistString(a))
    }

    @Test
    fun `album artists fall back to the singular artist field`() {
        val a = Album(
            id = JsonPrimitive("alb1"),
            title = "Compilation",
            artists = emptyList(),
            artist = ArtistRef(name = "Various Artists"),
        )
        assertEquals("Various Artists", PerformersParser.albumArtistString(a))
    }

    @Test
    fun `album artists never come back empty`() {
        assertEquals("Unknown Artist", PerformersParser.albumArtistString(null))
        assertEquals("Unknown Artist", PerformersParser.albumArtistString(Album(title = "x")))
        assertNotNull(PerformersParser.albumArtistString(Album(title = "x")))
    }

    @Test
    fun `roles parse from real qobuz json`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val a = json.decodeFromString<Album>(
            """
            {"id":"abc","title":"Kid A","artists":[
              {"id":1,"name":"Radiohead","roles":["main-artist"]},
              {"id":2,"name":"Nigel Godrich","roles":["producer"]}
            ]}
            """.trimIndent()
        )
        assertEquals("Radiohead", PerformersParser.albumArtistString(a))
        assertTrue(a.artists.first().roles!!.contains("main-artist"))
    }

    @Test
    fun `track performers parse from real qobuz json`() {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val t = json.decodeFromString<Track>(
            """
            {"id":"t1","title":"Everything In Its Right Place",
             "performers":"Radiohead, MainArtist - Thom Yorke, AssociatedPerformer, Vocals",
             "performer":{"id":1,"name":"Radiohead"}}
            """.trimIndent()
        )
        assertEquals("Radiohead", PerformersParser.trackArtistString(t))
    }
}
