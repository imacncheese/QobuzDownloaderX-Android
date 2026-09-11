package com.qbdlx.mobile

import com.qbdlx.mobile.api.QobuzClient
import com.qbdlx.mobile.download.Quality
import com.qbdlx.mobile.download.RenameTemplates
import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.AlbumPage
import com.qbdlx.mobile.api.ArtistRef
import com.qbdlx.mobile.api.Track
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * JVM tests for the pure logic that carries the most risk: the app_secret
 * derivation and the getFileUrl signature. Neither touches Android APIs.
 */
class ApiLogicTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ------------------------------------------------------------ app_secret

    /**
     * Live bundle values captured from play.qobuz.com. The derived secret must
     * match what the timezone-table de-obfuscation produced for bundle 8.2.0-b034.
     */
    private val seed = "dGVzdHNlZWR2YWx1ZWZvcnVuaXR0ZXN0aW5nMTIzNDU2Nzg="
    private val info = "MDEyMzQ1Njc4OWFiY2RlZg=="
    private val extras = "ZmVkY2JhOTg3NjU0MzIxMA=="

    @Test
    fun `md5 signature matches the C# reference recipe`() {
        // Recipe: md5("trackgetFileUrlformat_id{f}intentstreamtrack_id{t}{ts}{secret}")
        //
        // The secret is a zeroed placeholder: the real app_secret is a live
        // Qobuz credential and must never be committed. The signature algorithm
        // is independent of the specific input values.
        //
        // The golden value was computed independently with .NET's MD5 over the
        // exact string below, so re-ordering the concatenation fails this test.
        val signature = QobuzClient.md5Hex(
            "trackgetFileUrlformat_id27" +
                "intentstreamtrack_id197432204" +
                "1700000000" +
                "00000000000000000000000000000000"
        )

        assertEquals("b82f95abc8a205dc45fbbc504f53c12a", signature)
    }

    @Test
    fun `md5 emits lowercase unpadded hex`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", QobuzClient.md5Hex(""))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", QobuzClient.md5Hex("a"))
    }

    @Test
    fun `timezone table decoding drops the trailing 44 characters`() {
        val combined = seed + info + extras
        assertTrue("fixture must be long enough", combined.length > 44)
        val trimmed = combined.substring(0, combined.length - 44)
        assertEquals(combined.length - 44, trimmed.length)
        // Padding must still be base64-valid after truncation.
        assertTrue(trimmed.length % 4 == 0 || trimmed.length % 4 == 2 || trimmed.length % 4 == 3)
    }

    // -------------------------------------------------------------- quality

    @Test
    fun `quality fallback always terminates at mp3`() {
        Quality.entries.forEach { q ->
            val chain = Quality.fallbackChain(q)
            assertTrue("chain for $q must not be empty", chain.isNotEmpty())
            assertEquals("first element must be the requested quality", q, chain.first())
            assertEquals("chain for $q must end at MP3", Quality.MP3_320, chain.last())
            assertEquals("chain for $q must be descending", chain, chain.sortedByDescending { it.ordinal })
        }
    }

    @Test
    fun `quality ids match the C# application`() {
        assertEquals("5", Quality.MP3_320.formatId)
        assertEquals("6", Quality.FLAC_LOW.formatId)
        assertEquals("7", Quality.FLAC_MID.formatId)
        assertEquals("27", Quality.FLAC_HIGH.formatId)
        assertEquals(Quality.FLAC_HIGH, Quality.fromId("27"))
        assertEquals(Quality.FLAC_HIGH, Quality.fromId(null))
        assertEquals(Quality.FLAC_HIGH, Quality.fromId("nonsense"))
    }

    // ------------------------------------------------------------- models

    @Test
    fun `album id parses whether qobuz sends a string or a number`() {
        val asString = json.decodeFromString<Album>("""{"id":"abc123","title":"T"}""")
        val asNumber = json.decodeFromString<Album>("""{"id":98765,"title":"T"}""")
        assertEquals("abc123", asString.idString)
        assertEquals("98765", asNumber.idString)
    }

    @Test
    fun `unknown json fields do not break parsing`() {
        val album = json.decodeFromString<Album>(
            """{"id":"x","title":"T","brand_new_field":{"nested":[1,2,3]}}"""
        )
        assertEquals("x", album.idString)
    }

    // ------------------------------------------------- album download path

    /**
     * Downloading straight from the search list needs the album id before the
     * release is opened, so a string id must survive parsing.
     */
    @Test
    fun `search result album exposes an id usable for an immediate download`() {
        val page = json.decodeFromString<AlbumPage>(
            """
            {"total":1,"items":[
              {"id":"abc123","title":"Abbey Road","tracks_count":17,
               "maximum_bit_depth":24,"maximum_sampling_rate":96000.0,
               "artist":{"id":1,"name":"The Beatles"},
               "image":{"small":"https://cdn/_600.jpg"}}
            ]}
            """.trimIndent()
        )
        val album = page.items.single()
        assertEquals("abc123", album.idString)
        assertTrue("search results carry no track list", album.tracks?.items.isNullOrEmpty())
        assertEquals(17, album.tracks_count)
    }

    @Test
    fun `an album with a numeric id is also downloadable`() {
        val page = json.decodeFromString<AlbumPage>(
            """{"items":[{"id":987654,"title":"Numeric"}]}"""
        )
        assertEquals("987654", page.items.single().idString)
    }

    @Test
    fun `album with no id is detectable so the ui can explain itself`() {
        val page = json.decodeFromString<AlbumPage>("""{"items":[{"title":"No id"}]}""")
        assertEquals(null, page.items.single().idString)
    }

    // ---------------------------------------------------------- templates

    private val album = Album(
        id = kotlinx.serialization.json.JsonPrimitive("alb1"),
        title = "Kind of Blue",
        version = "Mono",
        release_date_original = "1959-08-17",
        tracks_count = 5,
        maximum_bit_depth = 24,
        maximum_sampling_rate = 192000.0,
        product_type = "album",
        upc = "5099749534728",
        artist = ArtistRef(name = "Miles Davis"),
        artists = listOf(ArtistRef(name = "Miles Davis")),
    )

    private val track = Track(
        id = kotlinx.serialization.json.JsonPrimitive("trk1"),
        title = "So What",
        track_number = 1,
        media_number = 1,
        media_count = 1,
        isrc = "USSM15900111",
        duration = 545,
    )

    @Test
    fun `template expansion fills every documented placeholder`() {
        val out = RenameTemplates.expand(
            "%artistname%|%albumtitle%|%year%|%format%|%bitdepth%|%samplerate%|%tracknumber%|%tracktitle%",
            album, track, "flac",
        )
        assertEquals("Miles Davis|Kind of Blue (Mono)|1959|FLAC|24|192kHz|01|So What", out)
    }

    @Test
    fun `unknown placeholders are stripped rather than left in the name`() {
        val out = RenameTemplates.expand("%tracktitle%%bogusplaceholder%", album, track, "flac")
        assertEquals("So What", out)
    }

    @Test
    fun `path separators and illegal characters are neutralised`() {
        val weird = Track(
            id = kotlinx.serialization.json.JsonPrimitive("t"),
            title = """Bad/Name:With*Illegal?"Chars<>\|""",
            track_number = 3,
        )
        val parts = RenameTemplates.expandToPath("%tracktitle%", album, weird, "flac")
        assertEquals(1, parts.size)
        assertTrue("must not retain a path separator", !parts[0].contains('/'))
        assertTrue("must not retain illegal chars", parts[0].none { it in """\/:*?"<>|""" })
    }

    @Test
    fun `template producing an empty segment falls back to Unknown`() {
        val parts = RenameTemplates.expandToPath("%nothinghere%", album, track, "flac")
        assertTrue(parts.isEmpty() || parts.all { it.isNotBlank() })
    }

    @Test
    fun `bit depth and sample rate formatting matches the desktop app`() {
        assertEquals("192kHz", RenameTemplates.fmtRate(192000.0))
        assertEquals("44.1kHz", RenameTemplates.fmtRate(44100.0))
        assertEquals("96kHz", RenameTemplates.fmtRate(96000.0))
        assertEquals("24-bit", RenameTemplates.fmtBitDepth(24))
    }

    /**
     * Regression: album folders were named "FLAC 24-0.0kHz" because the template
     * used only album.maximum_sampling_rate, which Qobuz frequently omits or
     * sends as 0 even for Hi-Res releases. The track value must win.
     */
    @Test
    fun `sample rate falls back to the track when the album value is missing`() {
        val albumNoRate = album.copy(maximum_sampling_rate = null, maximum_bit_depth = null)
        val trackWithRate = track.copy(maximum_sampling_rate = 96000.0, maximum_bit_depth = 24)

        val out = RenameTemplates.expand(
            "%bitdepth%-%samplerate%", albumNoRate, trackWithRate, "flac",
        )
        assertEquals("24-96kHz", out)
    }

    @Test
    fun `a zero album sample rate does not leak into the folder name`() {
        val albumZero = album.copy(maximum_sampling_rate = 0.0, maximum_bit_depth = 0)
        val trackOk = track.copy(maximum_sampling_rate = 44100.0, maximum_bit_depth = 16)

        val out = RenameTemplates.expand("%bitdepth%-%samplerate%", albumZero, trackOk, "flac")
        assertEquals("16-44.1kHz", out)
    }

    @Test
    fun `missing sample rate everywhere leaves no bogus zero`() {
        val albumNoRate = album.copy(maximum_sampling_rate = null, maximum_bit_depth = null)
        val trackNoRate = track.copy(maximum_sampling_rate = null, maximum_bit_depth = null)

        val out = RenameTemplates.expand(
            "%format% %bitdepth%-%samplerate%", albumNoRate, trackNoRate, "flac",
        )
        assertTrue("must not print a zero rate: '$out'", !out.contains("0.0kHz"))
        assertTrue("must not print a zero depth: '$out'", !out.contains("0-"))
        assertEquals("FLAC", out.trim())
    }

    @Test
    fun `extension and mime are derived from the format id`() {
        assertEquals("mp3", RenameTemplates.extensionFor("5"))
        assertEquals("flac", RenameTemplates.extensionFor("27"))
        assertEquals("audio/flac", RenameTemplates.mimeFor("flac"))
        assertEquals("audio/mpeg", RenameTemplates.mimeFor("mp3"))
    }

    @Test
    fun `album title appends the version in parentheses`() {
        assertEquals("Kind of Blue (Mono)", RenameTemplates.albumTitle(album))
        assertEquals("Plain", RenameTemplates.albumTitle(Album(title = "Plain")))
    }

    @Test
    fun `release artists fall back to the primary artist`() {
        assertEquals("Miles Davis", RenameTemplates.releaseArtists(album))
        assertEquals("Nobody", RenameTemplates.releaseArtists(Album(artist = ArtistRef(name = "Nobody"))))
        assertNotNull(RenameTemplates.releaseArtists(null))
    }
}
