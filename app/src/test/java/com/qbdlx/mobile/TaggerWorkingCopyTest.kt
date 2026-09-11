package com.qbdlx.mobile

import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.ArtistRef
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.download.MetadataTagger
import kotlinx.serialization.json.JsonPrimitive
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression tests for the bug that shipped 1.0.0: the download engine writes
 * audio to `qbdlx_<id>.part` and the tagger's working copy was
 * `qbdlx_<id>.tagged`. JAudioTagger dispatches its reader on the file
 * *extension*, so it threw
 *
 *     CannotReadException: No Reader associated with this extension: tagged
 *
 * tagging was silently skipped, and the untagged file was published. Every
 * earlier test used a `.flac` fixture, which is why none of them caught it.
 */
class TaggerWorkingCopyTest {

    private val album = Album(
        id = JsonPrimitive("alb1"),
        title = "Kind of Blue",
        release_date_original = "1959-08-17",
        tracks_count = 5,
        artist = ArtistRef(name = "Miles Davis"),
        artists = listOf(ArtistRef(name = "Miles Davis")),
    )

    private val track = Track(
        id = JsonPrimitive("trk1"),
        title = "So What",
        track_number = 1,
        media_number = 1,
        media_count = 1,
    )

    private fun flacBytes(): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("sample.flac")!!.use { it.readBytes() }

    /** A file whose name has no usable audio extension, like the engine's `.part`. */
    private fun partFile(name: String): File {
        val f = File.createTempFile(name, ".part")
        f.deleteOnExit()
        f.writeBytes(flacBytes())
        return f
    }

    @Test
    fun `detects the container from magic bytes when the name has no extension`() {
        val flac = partFile("detect-flac")
        assertEquals("flac", MetadataTagger.detectAudioExtension(flac))

        val mp3 = File.createTempFile("detect-mp3", ".part").apply { deleteOnExit() }
        // ID3v2 header followed by a frame sync.
        mp3.writeBytes(byteArrayOf(0x49, 0x44, 0x33, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        assertEquals("mp3", MetadataTagger.detectAudioExtension(mp3))

        val unknown = File.createTempFile("detect-unknown", ".part").apply { deleteOnExit() }
        unknown.writeBytes(ByteArray(32) { 0x11 })
        assertEquals(null, MetadataTagger.detectAudioExtension(unknown))
    }

    @Test
    fun `working copy gains the real audio extension for a dot-part source`() {
        val source = partFile("wc-source")
        val preferred = File.createTempFile("wc-preferred", ".tagged").apply { deleteOnExit() }

        val corrected = MetadataTagger.withAudioExtension(source, preferred)

        assertTrue(
            "working copy must end in a real audio extension, was ${corrected.name}",
            corrected.name.endsWith(".flac"),
        )
    }

    @Test
    fun `working copy is left alone when the source already has an audio extension`() {
        val source = File.createTempFile("wc-native", ".flac").apply {
            deleteOnExit()
            writeBytes(flacBytes())
        }
        val preferred = File.createTempFile("wc-native-out", ".tagged").apply { deleteOnExit() }

        val result = MetadataTagger.withAudioExtension(source, preferred)
        assertEquals("no rename needed for a .flac source", preferred, result)
    }

    @Test
    fun `tagging a dot-part file actually writes tags`() {
        val source = partFile("tags-part")
        val preferred = File.createTempFile("tags-part-out", ".tagged").apply { deleteOnExit() }

        val result = MetadataTagger.tag(
            file = source,
            album = album,
            track = track,
            coverArt = null,
            options = MetadataTagger.Options(),
            workingCopy = preferred,
        )

        assertTrue("tagging a .part file must succeed: ${result.warning}", result.ok)
        assertNotNull("the tagger must report the file it wrote", result.file)

        val written = result.file!!
        assertTrue("written file must exist", written.exists())
        assertTrue(
            "written file must carry an audio extension: ${written.name}",
            written.name.endsWith(".flac"),
        )

        // Read it back through the same reader the app uses.
        val reread = AudioFileIO.read(written)
        assertEquals("So What", reread.tag.getFirst(FieldKey.TITLE))
        assertEquals("Kind of Blue", reread.tag.getFirst(FieldKey.ALBUM))
        assertEquals("Miles Davis", reread.tag.getFirst(FieldKey.ARTIST))
    }

    @Test
    fun `a file with an undetectable container fails cleanly instead of throwing`() {
        val source = File.createTempFile("junk", ".part").apply {
            deleteOnExit()
            writeBytes(ByteArray(2048) { 0x5A })
        }
        val preferred = File.createTempFile("junk-out", ".tagged").apply { deleteOnExit() }

        val result = MetadataTagger.tag(
            file = source,
            album = album,
            track = track,
            coverArt = null,
            options = MetadataTagger.Options(),
            workingCopy = preferred,
        )

        assertFalse("junk must not report success", result.ok)
        assertNotNull("a reason must be surfaced", result.warning)
    }
}
