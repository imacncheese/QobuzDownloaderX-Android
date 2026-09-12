package com.qbdlx.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.ArtistRef
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.download.RenameTemplates
import com.qbdlx.mobile.ui.formatBitDepthRate
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Naming templates on a real device.
 *
 * The JVM and Android regex engines do not agree on everything. A pattern
 * containing a bare "}" compiles fine on the JVM and throws
 * PatternSyntaxException on Android. Unit tests passed, the APK failed every
 * download, and only a device run showed it. Anything that builds a regex from
 * a template or metadata belongs here as well as in the JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class RenameTemplatesAndroidTest {

    private val album = Album(
        id = JsonPrimitive("alb1"),
        title = "Kind of Blue",
        version = "Mono",
        release_date_original = "1959-08-17",
        tracks_count = 5,
        // Qobuz reports this field in kHz, not Hz.
        maximum_bit_depth = 24,
        maximum_sampling_rate = 192.0,
        product_type = "album",
        artist = ArtistRef(name = "Miles Davis"),
        artists = listOf(ArtistRef(name = "Miles Davis", roles = listOf("main-artist"))),
    )

    private val track = Track(
        id = JsonPrimitive("trk1"),
        title = "So What",
        track_number = 1,
        media_number = 1,
        media_count = 1,
        isrc = "USSM15900111",
        duration = 545,
    )

    @Test
    fun defaultTemplateProducesTheExpectedFolderName() {
        val out = RenameTemplates.expand(
            RenameTemplates.DEFAULT_ALBUM_TEMPLATE, album, track, "flac",
        )
        assertEquals("Kind of Blue (Mono) (1959) [FLAC 24-192kHz]", out)
    }

    /**
     * Every bracket kind the template language can produce, so that a regex
     * which only compiles on the JVM cannot slip through again.
     */
    @Test
    fun everyHollowBracketPairIsHandledOnDevice() {
        val cases = listOf(
            "A () B" to "A B",
            "A [] B" to "A B",
            "A {} B" to "A B",
            "A ( ) B" to "A B",
            "%albumtitle% () [%format%]" to "Kind of Blue (Mono) [FLAC]",
        )
        for ((template, expected) in cases) {
            val out = RenameTemplates.expand(template, album, track, "flac")
            assertEquals("template '$template'", expected, out)
        }
    }

    @Test
    fun anEmptyAlbumCannotLeaveUnbalancedBrackets() {
        for (template in listOf(
            RenameTemplates.DEFAULT_ALBUM_TEMPLATE,
            "%albumtitle% (%year%) [%format% %bitdepth%-%samplerate%]",
            "{%albumtitle%} [%year%]",
        )) {
            val out = RenameTemplates.expand(template, album = null, track = null, formatExt = "flac")
            val first = out.firstOrNull()
            assertTrue(
                "template '$template' produced '$out'",
                first == null || first.isLetterOrDigit(),
            )
            for ((open, close) in listOf('(' to ')', '[' to ']', '{' to '}')) {
                assertEquals(
                    "unbalanced '$open$close' in '$out' from '$template'",
                    out.count { it == open },
                    out.count { it == close },
                )
            }
        }
    }

    @Test
    fun samplingRateIsNotScaledTwice() {
        assertEquals("96kHz", RenameTemplates.fmtRate(96.0))
        assertEquals("44.1kHz", RenameTemplates.fmtRate(44.1))
        assertEquals("24-bit / 96 kHz", formatBitDepthRate(24, 96.0))
        assertFalse("a 96 kHz album must never read as 0.1 kHz", formatBitDepthRate(24, 96.0)!!.contains("0.1"))
    }
}
