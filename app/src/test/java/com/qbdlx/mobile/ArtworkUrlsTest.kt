package com.qbdlx.mobile

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Image
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.download.ArtworkUrls
import com.qbdlx.mobile.download.ArtworkUrls.Size
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Embedded artwork is written into the audio file permanently, so the size
 * chosen here is not recoverable later. These tests pin the ordering: the
 * largest rendition must be attempted first.
 */
class ArtworkUrlsTest {

    private val base = "https://static.qobuz.com/images/covers/ab/cd/abcdefghijklmnopqrstuvwxyz_600.jpg"

    // ------------------------------------------------------------- rewriting

    @Test
    fun `rewrites the rendition suffix`() {
        assertEquals(
            "https://static.qobuz.com/images/covers/ab/cd/abcdefghijklmnopqrstuvwxyz_max.jpg",
            ArtworkUrls.withSize(base, Size.MAX),
        )
        assertEquals(
            "https://static.qobuz.com/images/covers/ab/cd/abcdefghijklmnopqrstuvwxyz_org.jpg",
            ArtworkUrls.withSize(base, Size.ORG),
        )
        assertEquals(
            "https://static.qobuz.com/images/covers/ab/cd/abcdefghijklmnopqrstuvwxyz_50.jpg",
            ArtworkUrls.withSize(base, Size.PX_50),
        )
    }

    @Test
    fun `handles the WxH suffix form`() {
        val square = base.replace("_600.jpg", "_600x600.jpg")
        assertEquals(
            "https://static.qobuz.com/images/covers/ab/cd/abcdefghijklmnopqrstuvwxyz_max.jpg",
            ArtworkUrls.withSize(square, Size.MAX),
        )
    }

    @Test
    fun `leaves a suffixless url untouched since it is already full size`() {
        val bare = "https://static.qobuz.com/images/covers/ab/cd/abcdefghijklmnopqrstuvwxyz.jpg"
        assertEquals(bare, ArtworkUrls.withSize(bare, Size.MAX))
    }

    @Test
    fun `handles png and uppercase extensions`() {
        assertEquals(
            "https://x/cover_max.jpg",
            ArtworkUrls.withSize("https://x/cover_600.png", Size.MAX),
        )
        assertEquals(
            "https://x/cover_max.jpg",
            ArtworkUrls.withSize("https://x/cover_600.JPG", Size.MAX),
        )
    }

    // ---------------------------------------------------------------- order

    @Test
    fun `maximum is the default preference and comes first`() {
        val order = ArtworkUrls.defaultPreference()
        assertEquals(Size.MAX, order.first())
    }

    @Test
    fun `a chosen size is tried before the rest`() {
        val order = ArtworkUrls.defaultPreference(Size.PX_600)
        assertEquals(Size.PX_600, order.first())
        assertTrue("the largest rendition must still be attempted", order.contains(Size.MAX))
        assertEquals("no duplicates", order.size, order.distinct().size)
    }

    @Test
    fun `every rendition is present in the preference list`() {
        val order = ArtworkUrls.defaultPreference()
        Size.entries.forEach { assertTrue("missing $it", order.contains(it)) }
    }

    @Test
    fun `candidates put the largest rendition of the first url first`() {
        val candidates = ArtworkUrls.candidates(base, album = null, track = null)
        assertTrue(candidates.isNotEmpty())
        assertTrue(
            "expected a max rendition first, got ${candidates.first()}",
            candidates.first().endsWith("_max.jpg"),
        )
    }

    @Test
    fun `candidates deduplicate across album and track fields`() {
        val image = Image(large = base, small = base, thumbnail = base)
        val album = Album(id = JsonPrimitive("a"), title = "T", image = image)
        val track = Track(id = JsonPrimitive("t"), album = album)

        val candidates = ArtworkUrls.candidates(base, album, track)
        assertEquals("identical urls must collapse", candidates.size, candidates.distinct().size)
    }

    @Test
    fun `candidates still work when only the album has an image`() {
        val album = Album(
            id = JsonPrimitive("a"),
            title = "T",
            image = Image(large = base, small = null),
        )
        val candidates = ArtworkUrls.candidates(null, album, null)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.first().endsWith("_max.jpg"))
    }

    @Test
    fun `no urls yields no candidates`() {
        assertTrue(ArtworkUrls.candidates(null, null, null).isEmpty())
        assertTrue(ArtworkUrls.candidates("", Album(title = "x"), null).isEmpty())
    }

    // ----------------------------------------------------------- validation

    @Test
    fun `a tiny response is rejected rather than embedded`() {
        // Some CDNs answer an unknown rendition with a small placeholder or an
        // error document; that must not become the album art.
        assertFalse(ArtworkUrls.looksLikeRealArtwork(ByteArray(64) { 0xFF.toByte() }))
        assertFalse(ArtworkUrls.looksLikeRealArtwork(ByteArray(0)))
        assertFalse(ArtworkUrls.looksLikeRealArtwork(null))
    }

    @Test
    fun `a real jpeg is accepted`() {
        val jpeg = ByteArray(8192)
        jpeg[0] = 0xFF.toByte()
        jpeg[1] = 0xD8.toByte()
        jpeg[2] = 0xFF.toByte()
        jpeg[3] = 0xE0.toByte()
        assertTrue(ArtworkUrls.looksLikeRealArtwork(jpeg))
    }

    @Test
    fun `a real png is accepted`() {
        val png = ByteArray(8192)
        png[0] = 0x89.toByte()
        png[1] = 'P'.code.toByte()
        png[2] = 'N'.code.toByte()
        png[3] = 'G'.code.toByte()
        assertTrue(ArtworkUrls.looksLikeRealArtwork(png))
    }

    @Test
    fun `a large non-image response is rejected`() {
        // e.g. an HTML error page, which would otherwise be embedded verbatim.
        val html = "<!DOCTYPE html><html><body>Not found</body></html>".toByteArray()
            .let { ByteArray(8192) { i -> if (i < it.size) it[i] else ' '.code.toByte() } }
        assertFalse(ArtworkUrls.looksLikeRealArtwork(html))
    }

    @Test
    fun `size lookup falls back to maximum for unknown suffixes`() {
        assertEquals(Size.MAX, Size.fromSuffix("max"))
        assertEquals(Size.PX_600, Size.fromSuffix("600"))
        assertEquals(Size.MAX, Size.fromSuffix(null))
        assertEquals(Size.MAX, Size.fromSuffix("nonsense"))
    }

    // ------------------------------------------- sibling-track cache ordering

    @Test
    fun `a known-good rendition is promoted to the front`() {
        val candidates = ArtworkUrls.candidates(base, null, null)
        val ordered = ArtworkUrls.prioritiseKnown(candidates, base, Size.PX_600)

        assertTrue(
            "expected the cached rendition first, got ${ordered.first()}",
            ordered.first().endsWith("_600.jpg"),
        )
        assertEquals("no candidate may be lost", candidates.size, ordered.size)
        assertEquals("no duplicates", ordered.size, ordered.distinct().size)
    }

    @Test
    fun `prioritising with no knowledge leaves the order untouched`() {
        val candidates = ArtworkUrls.candidates(base, null, null)
        assertEquals(candidates, ArtworkUrls.prioritiseKnown(candidates, base, null))
        assertEquals(candidates, ArtworkUrls.prioritiseKnown(candidates, "", Size.MAX))
    }

    @Test
    fun `a cached rendition that is not among the candidates is ignored`() {
        // e.g. the cover URL changed between tracks; do not invent a URL.
        val candidates = listOf("https://x/other_max.jpg")
        val ordered = ArtworkUrls.prioritiseKnown(candidates, base, Size.PX_600)
        assertEquals(candidates, ordered)
    }
}
