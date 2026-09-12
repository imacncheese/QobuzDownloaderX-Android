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

    private fun mp3Fixture(): File {
        val dest = File(context.cacheDir, "silence.mp3")
        testContext.assets.open("silence.mp3").use { input ->
            dest.outputStream().use { input.copyTo(it) }
        }
        assertTrue("mp3 fixture must be non-empty", dest.length() > 0)
        return dest
    }

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

    // ------------------------------------------------------------- lyrics

    private val lyricsFixture = com.qbdlx.mobile.lyrics.Lyrics(
        synced = listOf(
            com.qbdlx.mobile.lyrics.LyricLine(1_000, "First line"),
            com.qbdlx.mobile.lyrics.LyricLine(5_000, "Second line"),
            com.qbdlx.mobile.lyrics.LyricLine(12_500, "Third line"),
        ),
        plain = "First line\nSecond line\nThird line",
    )

    /**
     * MP3 lyrics go into ID3 frames, and the frame class has to match the tag's
     * ID3 version: a fresh MP3 gets an ID3v2.3 tag from JAudioTagger, and a v2.4
     * frame added to a v2.3 tag produces a file no reader can parse. That is
     * device-only knowledge, because on Windows JAudioTagger cannot grow an ID3
     * tag at all.
     */
    @Test
    fun mp3LyricsAreEmbeddedOnAndroid() {
        val work = File(context.cacheDir, "lyrics.mp3")
        mp3Fixture().copyTo(work, overwrite = true)

        val result = MetadataTagger.tag(
            file = work,
            album = null,
            track = com.qbdlx.mobile.api.Track(
                id = kotlinx.serialization.json.JsonPrimitive("t1"),
                title = "Lyrics Track",
            ),
            coverArt = null,
            options = MetadataTagger.Options(writeLyrics = true, writeCoverArt = false),
            workingCopy = File(context.cacheDir, "lyrics-work.mp3"),
            lyrics = lyricsFixture,
        )
        assertTrue("tagging must succeed: ${result.warning}", result.ok)
        val written = result.file!!

        val tag = AudioFileIO.read(written).tag
        android.util.Log.i(
            "TaggerAndroidTest",
            "mp3 tag version=${tag.javaClass.simpleName} uslt=${tag.getFirst(FieldKey.LYRICS)}",
        )

        // The unsynchronised block is what every player reads.
        val uslt = tag.getFirst(FieldKey.LYRICS)
        assertNotNull("USLT must be written", uslt)
        assertTrue("USLT must carry the text: $uslt", uslt!!.contains("Second line"))

        // And the timed frame has to round-trip with its timestamps intact.
        val sylt = readSylt(tag)
        assertNotNull("a SYLT frame must be present", sylt)
        assertEquals(3, sylt!!.size)
        assertEquals(1_000L, sylt[0].first)
        assertEquals("First line", sylt[0].second)
        assertEquals(5_000L, sylt[1].first)
        assertEquals("Second line", sylt[1].second)
        assertEquals(12_500L, sylt[2].first)
        assertEquals("Third line", sylt[2].second)
    }

    @Test
    fun flacLyricsAreEmbeddedOnAndroid() {
        val work = File(context.cacheDir, "lyrics.flac")
        flacFixture().copyTo(work, overwrite = true)

        val result = MetadataTagger.tag(
            file = work,
            album = null,
            track = com.qbdlx.mobile.api.Track(
                id = kotlinx.serialization.json.JsonPrimitive("t1"),
                title = "Lyrics Track",
            ),
            coverArt = null,
            options = MetadataTagger.Options(writeLyrics = true, writeCoverArt = false),
            workingCopy = File(context.cacheDir, "lyrics-work.flac"),
            lyrics = lyricsFixture,
        )
        assertTrue("tagging must succeed: ${result.warning}", result.ok)
        val written = result.file!!

        val tag = AudioFileIO.read(written).tag
        val plain = tag.getFirst(FieldKey.LYRICS)
        assertNotNull("LYRICS must be written", plain)
        assertTrue("LYRICS must carry the text: $plain", plain!!.contains("Second line"))

        // FLAC keeps the timing in a SYNCEDLYRICS comment. FieldKey has no entry
        // for it, so it is looked up by its raw comment name, which is also what
        // another player would do.
        val flac = tag as org.jaudiotagger.tag.flac.FlacTag
        val value = flac.getFirst("SYNCEDLYRICS")
        android.util.Log.i("TaggerAndroidTest", "SYNCEDLYRICS=$value")

        assertNotNull("SYNCEDLYRICS must be present", value)
        assertTrue("must start with the first stamp: $value", value!!.startsWith("[00:01.00]"))
        assertTrue("must keep the last stamp: $value", value.contains("[00:12.50]"))
        assertTrue("must keep the text: $value", value.contains("Third line"))
    }

    /**
     * Decodes the SYLT payload back the way a player would.
     *
     * Deliberately not using JAudioTagger's own reader for the assertion: the
     * point is to prove the bytes on disk are right, not that the library can
     * read back what it wrote.
     */
    private fun readSylt(tag: org.jaudiotagger.tag.Tag): List<Pair<Long, String>>? {
        val frames = tag.getFields("SYLT")
        if (frames.isEmpty()) return null
        val frame = frames.first() as org.jaudiotagger.tag.id3.AbstractID3v2Frame
        val body = frame.body as? org.jaudiotagger.tag.id3.framebody.FrameBodySYLT ?: return null
        assertEquals("time stamp format must be milliseconds", 2, body.timeStampFormat)
        assertEquals("content type must be lyrics", 1, body.contentType)
        assertEquals("eng", body.language)

        val raw = body.lyrics
        val out = ArrayList<Pair<Long, String>>()
        var p = 0
        while (p + 5 <= raw.size) {
            var ms = 0L
            for (i in 0 until 4) {
                ms = (ms shl 8) or (raw[p + i].toLong() and 0xFF)
            }
            p += 4
            val start = p
            while (p < raw.size && raw[p] != 0.toByte()) p++
            out.add(ms to String(raw, start, p - start, Charsets.UTF_8))
            p++ // the NUL terminator
        }
        return out
    }
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
