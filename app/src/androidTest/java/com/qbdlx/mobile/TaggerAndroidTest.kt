package com.qbdlx.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.qbdlx.mobile.download.MetadataTagger
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real tagger on a real device.
 *
 * This exists because the JVM unit tests cannot catch Android-only breakage:
 * JAudioTagger's FlacTag.createField decodes artwork through
 * `javax.imageio` / `BufferedImage`, and **java.awt does not exist on Android**.
 * A JVM test passes happily while the device fails, which is exactly how the
 * "tagging failed / file not saved" report got through.
 */
@RunWith(AndroidJUnit4::class)
class TaggerAndroidTest {

    /** Target app context — used only for a writable cache directory. */
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Test APK context — assets live in the *test* APK, not the app under test.
     * Reading them from targetContext throws FileNotFoundException.
     */
    private val testContext get() = InstrumentationRegistry.getInstrumentation().context

    private fun flacFixture(): File {
        val dest = File(context.cacheDir, "android-fixture.flac")
        testContext.assets.open("sample.flac").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        assertTrue("fixture must be non-empty", dest.length() > 0)
        return dest
    }

    private fun jpegFixture(): ByteArray =
        testContext.assets.open("cover.jpg").use { it.readBytes() }

    @Test
    fun canReadAFlacAtAll() {
        val flac = flacFixture()
        val header = AudioFileIO.read(flac).audioHeader
        assertNotNull("JAudioTagger must parse FLAC on Android", header)
        assertEquals("44100", header.sampleRate)
    }

    @Test
    fun writesBasicTagsOnAndroid() {
        val work = File(context.cacheDir, "android-tags.flac")
        flacFixture().copyTo(work, overwrite = true)

        val audio = AudioFileIO.read(work)
        val tag = audio.tagOrCreateAndSetDefault
        tag.setField(FieldKey.TITLE, "Android Title")
        tag.setField(FieldKey.ARTIST, "Android Artist")
        AudioFileIO.write(audio)

        val reread = AudioFileIO.read(work).tag
        assertEquals("Android Title", reread.getFirst(FieldKey.TITLE))
        assertEquals("Android Artist", reread.getFirst(FieldKey.ARTIST))
    }

    /**
     * The regression this whole file exists for.
     *
     * JAudioTagger's FLAC artwork path needs javax.imageio/java.awt, which
     * Android does not have, so covering FLAC used to fail on-device while
     * passing on the JVM. Cover art is now written as a native PICTURE block.
     */
    @Test
    fun flacCoverArtIsEmbeddedOnAndroid() {
        val work = File(context.cacheDir, "android-cover.flac")
        flacFixture().copyTo(work, overwrite = true)

        val result = MetadataTagger.tag(
            file = work,
            album = null,
            track = null,
            coverArt = null,
            options = MetadataTagger.Options(),
            workingCopy = File(context.cacheDir, "unused.flac"),
        )
        // Metadata-less tagging is a clean failure by design; not the point here.
        assertFalse(result.ok)

        // Now the real thing: an album with cover art.
        val album = com.qbdlx.mobile.api.Album(
            id = kotlinx.serialization.json.JsonPrimitive("a1"),
            title = "Android Album",
            artist = com.qbdlx.mobile.api.ArtistRef(name = "Android Artist"),
            artists = listOf(com.qbdlx.mobile.api.ArtistRef(name = "Android Artist")),
        )
        val workingCopy = File(context.cacheDir, "android-cover-work.flac")
        val tagged = MetadataTagger.tag(
            file = work,
            album = album,
            track = null,
            coverArt = jpegFixture(),
            options = MetadataTagger.Options(writeCoverArt = true),
            workingCopy = workingCopy,
        )

        android.util.Log.i("TaggerAndroidTest", "FLAC cover art result: ok=${tagged.ok} warning=${tagged.warning}")

        assertTrue("tagging must succeed: ${tagged.warning}", tagged.ok)
        assertNull("cover art must not be skipped: ${tagged.warning}", tagged.warning)

        // Verify the PICTURE block at the byte level rather than trusting
        // JAudioTagger's own FLAC artwork reader.
        val block = findPictureBlock(workingCopy)
        assertNotNull("a PICTURE metadata block must be present", block)
        assertTrue("picture type must be 3 (front cover)", block!!.pictureType == 3)
        assertEquals("MIME type must be image/jpeg", "image/jpeg", block.mime)
        assertTrue("picture payload must be non-empty", block.data.size > 0)
        assertTrue(
            "picture payload must start with a JPEG SOI marker",
            block.data[0] == 0xFF.toByte() && block.data[1] == 0xD8.toByte(),
        )
        // The embedded bytes must still decode as an image on Android.
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(block.data, 0, block.data.size)
        assertNotNull("embedded cover art must decode as an image", bitmap)

        // The audio must be untouched, and tags must still be readable.
        val reread = AudioFileIO.read(workingCopy)
        assertEquals("44100", reread.audioHeader.sampleRate)
        assertEquals("Android Album", reread.tag.getFirst(FieldKey.ALBUM))
        assertTrue("file must not be truncated", workingCopy.length() > 0)
    }

    /** Dumps the FLAC metadata chain so failures are diagnosable from logcat. */
    private fun dumpBlocks(label: String, file: File) {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val marker = ByteArray(4)
            raf.readFully(marker)
            val sb = StringBuilder("$label: size=${file.length()} ")
            var guard = 0
            while (guard++ < 128) {
                val header = raf.readUnsignedByte()
                val isLast = header and 0x80 != 0
                val type = header and 0x7F
                val len = raf.readUnsignedByte() shl 16 or
                    (raf.readUnsignedByte() shl 8) or
                    raf.readUnsignedByte()
                sb.append("[type=$type len=$len${if (isLast) " LAST" else ""}] ")
                raf.seek(raf.filePointer + len)
                if (isLast) break
            }
            android.util.Log.i("TaggerAndroidTest", sb.toString())
        }
    }

    /**
     * The exact production shape: the download engine hands the tagger a temp
     * file named `<id>.part`, not a nicely-suffixed `.flac`. JAudioTagger
     * dispatches readers on extension, so this is where tagging silently broke.
     */
    @Test
    fun tagsAFileNamedLikeTheDownloadEngineProduces() {
        val part = File(context.cacheDir, "qbdlx_regression.part")
        flacFixture().copyTo(part, overwrite = true)

        val album = com.qbdlx.mobile.api.Album(
            id = kotlinx.serialization.json.JsonPrimitive("a1"),
            title = "Part Album",
            release_date_original = "2026-01-02",
            artist = com.qbdlx.mobile.api.ArtistRef(name = "Part Artist"),
            artists = listOf(com.qbdlx.mobile.api.ArtistRef(name = "Part Artist")),
        )
        val track = com.qbdlx.mobile.api.Track(
            id = kotlinx.serialization.json.JsonPrimitive("t1"),
            title = "Part Track",
            track_number = 1,
            media_number = 1,
            media_count = 1,
        )

        val result = MetadataTagger.tag(
            file = part,
            album = album,
            track = track,
            coverArt = jpegFixture(),
            options = MetadataTagger.Options(writeCoverArt = true),
            workingCopy = File(context.cacheDir, "qbdlx_regression.tagged"),
        )

        android.util.Log.i(
            "TaggerAndroidTest",
            "part-file tagging: ok=${result.ok} warning=${result.warning} file=${result.file?.name}",
        )

        assertTrue("tagging a .part file must succeed: ${result.warning}", result.ok)
        val written = result.file
        assertNotNull("tagger must report the written file", written)
        assertTrue("written file must exist", written!!.exists())
        assertTrue(
            "written file must carry an audio extension: ${written.name}",
            written.name.endsWith(".flac"),
        )

        // Tags must be readable, and the cover art must be a real PICTURE block.
        val reread = AudioFileIO.read(written)
        assertEquals("Part Track", reread.tag.getFirst(FieldKey.TITLE))
        assertEquals("Part Album", reread.tag.getFirst(FieldKey.ALBUM))
        assertEquals("Part Artist", reread.tag.getFirst(FieldKey.ARTIST))
        assertEquals("2026", reread.tag.getFirst(FieldKey.YEAR))
        assertEquals("1", reread.tag.getFirst(FieldKey.TRACK))

        // The embedded artwork must match the source image's dimensions exactly:
        // any downscaling here would be a permanent loss of detail in the file.
        val picture = findPictureBlock(written)
        assertNotNull("cover art must be embedded", picture)
        val embedded = android.graphics.BitmapFactory
            .decodeByteArray(picture!!.data, 0, picture.data.size)
        assertNotNull("embedded cover art must decode", embedded)

        val source = android.graphics.BitmapFactory
            .decodeByteArray(jpegFixture(), 0, jpegFixture().size)
        assertNotNull("fixture must decode", source)

        assertEquals(
            "embedded width must equal the source width",
            source.width, embedded.width,
        )
        assertEquals(
            "embedded height must equal the source height",
            source.height, embedded.height,
        )
        android.util.Log.i(
            "TaggerAndroidTest",
            "embedded artwork ${embedded.width}x${embedded.height}, ${picture.data.size} bytes",
        )

        assertEquals("44100", reread.audioHeader.sampleRate)
    }

    /**
     * ARTIST must come from parsing the role-annotated `performers` string, not
     * from the raw string. A naive split puts engineers and producers into the
     * artist tag.
     */
    @Test
    fun artistTagsAreDerivedFromRolesOnAndroid() {
        val part = File(context.cacheDir, "qbdlx_artist.part")
        flacFixture().copyTo(part, overwrite = true)

        val album = com.qbdlx.mobile.api.Album(
            id = kotlinx.serialization.json.JsonPrimitive("a1"),
            title = "Kid A",
            artist = com.qbdlx.mobile.api.ArtistRef(name = "Radiohead"),
            artists = listOf(
                com.qbdlx.mobile.api.ArtistRef(name = "Radiohead", roles = listOf("main-artist")),
                com.qbdlx.mobile.api.ArtistRef(name = "Nigel Godrich", roles = listOf("producer")),
            ),
        )
        val track = com.qbdlx.mobile.api.Track(
            id = kotlinx.serialization.json.JsonPrimitive("t1"),
            title = "Everything In Its Right Place",
            track_number = 1,
            media_number = 1,
            media_count = 1,
            performers = "Radiohead, MainArtist - Nigel Godrich, Producer - " +
                "Thom Yorke, AssociatedPerformer, Vocals",
            performer = com.qbdlx.mobile.api.ArtistRef(name = "Radiohead"),
            album = album,
        )

        val result = MetadataTagger.tag(
            file = part,
            album = album,
            track = track,
            coverArt = null,
            options = MetadataTagger.Options(),
            workingCopy = File(context.cacheDir, "qbdlx_artist.tagged"),
        )

        assertTrue("tagging must succeed: ${result.warning}", result.ok)
        val written = result.file!!
        val tag = AudioFileIO.read(written).tag

        val artist = tag.getFirst(FieldKey.ARTIST)
        val albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST)
        android.util.Log.i(
            "TaggerAndroidTest",
            "artist='$artist' albumArtist='$albumArtist'",
        )

        assertEquals("Radiohead", artist)
        assertEquals("Radiohead", albumArtist)
        assertFalse("producer must not be an artist", artist.contains("Godrich"))
        assertFalse("engineer roles must not leak", artist.contains("Producer"))
    }

    private data class PictureBlock(
        val pictureType: Int,
        val mime: String,
        val data: ByteArray,
    )

    /**
     * Minimal FLAC metadata walk that extracts the PICTURE block, mirroring what
     * a media player does. Implemented here so the assertion does not depend on
     * JAudioTagger's own (Android-limited) artwork reading.
     */
    private fun findPictureBlock(file: File): PictureBlock? {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val marker = ByteArray(4)
            raf.readFully(marker)
            assertEquals("fLaC", String(marker, Charsets.US_ASCII))

            var guard = 0
            while (guard++ < 128) {
                val header = raf.readUnsignedByte()
                val isLast = header and 0x80 != 0
                val type = header and 0x7F
                val len = raf.readUnsignedByte() shl 16 or
                    (raf.readUnsignedByte() shl 8) or
                    raf.readUnsignedByte()
                val payload = ByteArray(len)
                raf.readFully(payload)

                if (type == 6) { // PICTURE
                    var p = 0
                    fun u32(): Int {
                        val v = (payload[p].toInt() and 0xFF) shl 24 or
                            (payload[p + 1].toInt() and 0xFF) shl 16 or
                            (payload[p + 2].toInt() and 0xFF) shl 8 or
                            (payload[p + 3].toInt() and 0xFF)
                        p += 4
                        return v
                    }
                    val pictureType = u32()
                    val mimeLen = u32()
                    val mime = String(payload, p, mimeLen, Charsets.US_ASCII)
                    p += mimeLen
                    val descLen = u32()
                    p += descLen
                    p += 16 // width, height, depth, palette
                    val dataLen = u32()
                    return PictureBlock(pictureType, mime, payload.copyOfRange(p, p + dataLen))
                }

                if (isLast) break
            }
        }
        return null
    }
}
