package com.qbdlx.mobile.download

import android.util.Log
import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Track
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.TagField
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.images.StandardArtwork
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import java.io.File

/**
 * Writes tags + embedded cover art into a downloaded file with JAudioTagger,
 * matching the field set the C# reference writes via taglib-sharp.
 *
 * Tagging is *best effort*: the caller still publishes the audio when this
 * fails, so a tagger outage can never cost the user a download.
 *
 * JAudioTagger 2.2.5 exposes no FieldKey for Copyright, the full release date
 * (TDRL) or ReplayGain, and its typed `createField` overloads take format
 * specific id types. Those go through a small reflective helper that works for
 * both Vorbis comments (FLAC) and ID3v2 (MP3); unsupported formats skip them.
 */
object MetadataTagger {

    private const val TAG = "QbdlxTagger"

    data class Options(
        val writeAlbumTitle: Boolean = true,
        val writeAlbumArtist: Boolean = true,
        val writeTrackArtist: Boolean = true,
        val writeComposer: Boolean = true,
        val writeCopyright: Boolean = true,
        val writeLabel: Boolean = true,
        val writeDiscNumber: Boolean = true,
        val writeDiscTotal: Boolean = true,
        val writeGenre: Boolean = true,
        val writeIsrc: Boolean = true,
        val writeUrl: Boolean = true,
        val writeReleaseType: Boolean = true,
        val writeExplicit: Boolean = true,
        val writeTrackTitle: Boolean = true,
        val writeTrackNumber: Boolean = true,
        val writeTrackTotal: Boolean = true,
        val writeUpc: Boolean = true,
        val writeReleaseDate: Boolean = true,
        val writeYear: Boolean = true,
        val writeCoverArt: Boolean = true,
        val writeComment: Boolean = false,
        val commentText: String = "",
        val writeReplayGain: Boolean = true,
    )

    /**
     * @param ok      true when [file] holds a valid, tagged audio file
     * @param warning a non-fatal problem worth surfacing to the user, if any
     * @param file    the file that actually holds the tagged audio. This is not
     *                always the requested working copy: the tagger appends the
     *                real audio extension so JAudioTagger can dispatch a reader.
     */
    data class Result(
        val ok: Boolean,
        val warning: String? = null,
        val file: File? = null,
    )

    /**
     * Tags [file] in place but writes through [workingCopy] first, so a failure
     * can never leave the caller's file truncated or corrupt.
     *
     * [file] is typically a temp file with a generic extension (".part"), and
     * JAudioTagger dispatches on the file *extension* when choosing a reader.
     * The working copy therefore always carries the source file's real extension
     * — see [withAudioExtension].
     *
     * @param coverArt JPEG/PNG bytes for the front cover, or null to skip
     */
    fun tag(
        file: File,
        album: Album?,
        track: Track?,
        coverArt: ByteArray?,
        options: Options,
        workingCopy: File,
    ): Result {
        if (album == null && track == null) {
            return Result(ok = false, warning = "No metadata available for this track")
        }

        val sizeBefore = file.length()
        val target = withAudioExtension(file, workingCopy)

        val audioFile = try {
            runCatching { file.copyTo(target, overwrite = true) }
                .getOrElse { e ->
                    Log.w(TAG, "Could not create a tagging working copy", e)
                    return Result(false, "Tagging skipped: ${e.javaClass.simpleName}")
                }
            AudioFileIO.read(target)
        } catch (e: Throwable) {
            Log.w(TAG, "Tagging skipped: cannot read ${file.name}", e)
            return Result(false, describe("Could not read the audio file", e))
        }

        return try {
            val tag: Tag = audioFile.tagOrCreateAndSetDefault

            // FLAC PICTURE blocks are written after AudioFileIO.write; see below.
            var pendingArtwork: ByteArray? = null

            fun put(key: FieldKey, vararg values: String?) {
                val cleaned = values.filterNotNull().map { it.trim() }.filter { it.isNotEmpty() }
                if (cleaned.isEmpty()) return
                runCatching {
                    tag.deleteField(key)
                    tag.setField(key, cleaned.first())
                    for (v in cleaned.drop(1)) tag.addField(key, v)
                }
            }

            if (options.writeTrackTitle) put(FieldKey.TITLE, track?.title ?: album?.title)
            if (options.writeAlbumTitle) put(FieldKey.ALBUM, RenameTemplates.albumTitle(album))
            if (options.writeAlbumArtist) put(FieldKey.ALBUM_ARTIST, RenameTemplates.releaseArtists(album))
            if (options.writeTrackArtist) {
                put(
                    FieldKey.ARTIST,
                    RenameTemplates.trackArtists(track) ?: RenameTemplates.releaseArtists(album),
                )
            }
            if (options.writeComposer) put(FieldKey.COMPOSER, track?.composer?.name)
            if (options.writeLabel) put(FieldKey.RECORD_LABEL, album?.label?.name)
            if (options.writeGenre) put(FieldKey.GENRE, album?.genre?.name ?: track?.genre?.name)
            if (options.writeIsrc) put(FieldKey.ISRC, track?.isrc)
            if (options.writeUpc) put(FieldKey.BARCODE, album?.upc ?: track?.upc)
            if (options.writeReleaseType) put(FieldKey.GROUPING, album?.product_type)

            // taglib-sharp's file.Tag.Copyright -> Vorbis COPYRIGHT / ID3 TCOP.
            if (options.writeCopyright) {
                putCustom(tag, "COPYRIGHT", "TCOP", album?.copyright ?: track?.copyright)
            }

            // taglib-sharp writes the full release date to DATE / TDRL and the
            // bare year to YEAR; JAudioTagger's FieldKey.YEAR is year-only.
            if (options.writeReleaseDate) {
                putCustom(
                    tag, "DATE", "TDRL",
                    album?.release_date_original ?: track?.release_date_original,
                )
            }
            if (options.writeYear) {
                val raw = album?.release_date_original ?: track?.release_date_original
                val y = raw?.trim()?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }
                put(FieldKey.YEAR, y)
            }

            if (options.writeUrl) {
                val url = album?.url ?: track?.idString?.let { "https://play.qobuz.com/track/$it" }
                put(FieldKey.URL_OFFICIAL_RELEASE_SITE, url)
            }
            if (options.writeExplicit) {
                val explicit = track?.parental_warning == true || album?.parental_warning == true
                put(FieldKey.RATING, if (explicit) "1" else "0")
            }
            if (options.writeTrackNumber) put(FieldKey.TRACK, track?.track_number?.toString())
            if (options.writeTrackTotal) put(FieldKey.TRACK_TOTAL, album?.tracks_count?.toString())
            if (options.writeDiscNumber) {
                put(FieldKey.DISC_NO, track?.media_number?.takeIf { it > 0 }?.toString())
            }
            if (options.writeDiscTotal) {
                put(FieldKey.DISC_TOTAL, track?.media_count?.takeIf { it > 0 }?.toString())
            }
            if (options.writeComment && options.commentText.isNotBlank()) {
                put(FieldKey.COMMENT, options.commentText)
            }
            if (options.writeReplayGain) {
                putCustom(
                    tag, "REPLAYGAIN_TRACK_GAIN", "TXXX:REPLAYGAIN_TRACK_GAIN",
                    track?.audio_info?.replaygain_track_gain,
                )
                putCustom(
                    tag, "REPLAYGAIN_TRACK_PEAK", "TXXX:REPLAYGAIN_TRACK_PEAK",
                    track?.audio_info?.replaygain_track_peak,
                )
            }

            var warning: String? = null

            if (options.writeCoverArt && coverArt != null && coverArt.isNotEmpty()) {
                if (isFlac(target)) {
                    // Deferred: JAudioTagger's FLAC writer is allowed to reshape
                    // the metadata chain, so the PICTURE block is written after it.
                    // (Its own artwork path is unusable on Android — no AWT.)
                    pendingArtwork = coverArt
                } else {
                    runCatching { embedArtwork(tag, coverArt) }
                        .exceptionOrNull()
                        ?.let { e ->
                            Log.w(TAG, "Cover art embed failed", e)
                            warning = "Cover art skipped (${e.javaClass.simpleName})"
                        }
                }
            }

            AudioFileIO.write(audioFile)

            // FLAC artwork goes in after the tag write so it cannot be dropped.
            if (pendingArtwork != null) {
                val artWarning = FlacPicture.embed(target, pendingArtwork!!)
                if (artWarning != null) {
                    Log.w(TAG, "Cover art skipped: $artWarning")
                    warning = "Cover art skipped"
                }
            }

            // Verify the result before letting the caller publish it: a tagger
            // failure that truncates the stream must not reach the library.
            val sizeAfter = target.length()
            if (sizeAfter <= 0L) {
                return Result(false, "Tagging produced an empty file; saved the original instead.")
            }
            val wholeFileLost = sizeBefore > 0 && sizeAfter < sizeBefore / 2
            if (wholeFileLost) {
                Log.w(TAG, "Tagged file shrank suspiciously: $sizeBefore -> $sizeAfter bytes")
                return Result(false, "Tagging damaged the file; saved the untagged original instead.")
            }

            Result(ok = true, warning = warning, file = target)
        } catch (e: Throwable) {
            // Throwable on purpose: JAudioTagger can raise Error on bad input.
            Log.w(TAG, "Tagging failed for ${file.name}", e)
            Result(false, describe("Tagging failed", e))
        }
    }

    /**
     * JAudioTagger picks its reader from the file extension, so a working copy
     * named `something.part` or `something.tagged` fails with
     * `CannotReadException: No Reader associated with this extension`.
     *
     * This returns a file that carries the source's real audio extension
     * (derived from the magic bytes when the source name has none), placed next
     * to [preferred] so callers can still clean it up.
     */
    internal fun withAudioExtension(source: File, preferred: File): File {
        val sourceExt = source.extension.lowercase()
        if (sourceExt in AUDIO_EXTENSIONS) return preferred

        val detected = detectAudioExtension(source) ?: return preferred
        val corrected = File(preferred.parentFile, "${preferred.name}.$detected")
        if (corrected.exists()) corrected.delete()
        return corrected
    }

    /** Sniffs the container format from magic bytes. */
    internal fun detectAudioExtension(file: File): String? = try {
        val head = ByteArray(12)
        val read = file.inputStream().use { it.read(head) }
        when {
            read >= 4 && head[0] == 0x66.toByte() && head[1] == 0x4C.toByte() &&
                head[2] == 0x61.toByte() && head[3] == 0x43.toByte() -> "flac"

            read >= 3 && head[0] == 0x49.toByte() && head[1] == 0x44.toByte() &&
                head[2] == 0x33.toByte() -> "mp3" // "ID3" tag

            read >= 2 && head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"

            read >= 12 && head[4] == 0x66.toByte() && head[5] == 0x74.toByte() &&
                head[6] == 0x79.toByte() && head[7] == 0x70.toByte() -> "m4a" // ....ftyp

            read >= 4 && head[0] == 0x4F.toByte() && head[1] == 0x67.toByte() &&
                head[2] == 0x67.toByte() && head[3] == 0x53.toByte() -> "ogg" // OggS

            read >= 4 && head[0] == 0x52.toByte() && head[1] == 0x49.toByte() &&
                head[2] == 0x46.toByte() && head[3] == 0x46.toByte() -> "wav" // RIFF

            else -> null
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Could not sniff the audio container", e)
        null
    }

    /** Cheap magic-byte sniff so FLAC gets the AWT-free artwork path. */
    internal fun isFlac(file: File): Boolean = try {
        val head = ByteArray(4)
        file.inputStream().use { it.read(head) }
        head[0] == 0x66.toByte() && head[1] == 0x4C.toByte() &&
            head[2] == 0x61.toByte() && head[3] == 0x43.toByte()
    } catch (_: Throwable) {
        false
    }

    /** Extensions JAudioTagger can map to a reader. */
    private val AUDIO_EXTENSIONS = setOf("flac", "mp3", "ogg", "oga", "m4a", "mp4", "wav", "aiff", "aif", "wma")

    private fun describe(prefix: String, e: Throwable): String {
        val detail = e.message?.take(160)?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "$prefix: $detail. Saved without tags."
    }

    /**
     * Sets a tag field that has no [FieldKey], using the format-native id:
     * a Vorbis comment name for FLAC, an ID3v2 frame id for MP3.
     *
     * The typed `createField` overloads take format-specific id types
     * (VorbisCommentFieldKey / ID3v24FieldKey) which are not always general
     * purpose, so the untyped `createField(String, String)` is preferred and a
     * frame-id based variant is used as a fallback. Anything a given format does
     * not support is skipped silently.
     */
    private fun putCustom(tag: Tag, vorbisId: String, id3Id: String, value: String?) {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return
        runCatching {
            runCatching { (tag as? VorbisCommentTag)?.deleteField(vorbisId) }
            runCatching { (tag as? AbstractID3v2Tag)?.deleteField(id3Id) }
            val field = createCustomField(tag, vorbisId, id3Id, v) ?: return@runCatching
            tag.setField(field)
        }
    }

    private fun createCustomField(tag: Tag, vorbisId: String, id3Id: String, value: String): TagField? {
        // Preferred: an untyped (String, String) factory, present on VorbisCommentTag.
        tag.javaClass.methods.firstOrNull { m ->
            m.name == "createField" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[0] == String::class.java &&
                m.parameterTypes[1] == String::class.java
        }?.let { m ->
            runCatching { return m.invoke(tag, vorbisId, value) as? TagField }
        }

        // Fallback for ID3: create an empty frame for the id, then a (Class, String) factory.
        val frameClass = runCatching { Class.forName("org.jaudiotagger.tag.id3.ID3v24Frame") }.getOrNull()
            ?: return null
        val frame = runCatching {
            frameClass.getConstructor(String::class.java).newInstance(id3Id)
        }.getOrNull() ?: return null

        tag.javaClass.methods.firstOrNull { m ->
            m.name == "createField" &&
                m.parameterTypes.size == 2 &&
                m.parameterTypes[1] == String::class.java
        }?.let { m ->
            runCatching { return m.invoke(tag, frame, value) as? TagField }
        }
        return null
    }

    private fun embedArtwork(tag: Tag, coverArt: ByteArray) {
        tag.deleteArtworkField()
        val art = StandardArtwork()
        art.binaryData = coverArt
        art.mimeType = "image/jpeg"
        art.pictureType = 3 // ID3 APIC "front cover"; FLAC stores it as picture type 3 too
        art.description = "Cover"
        tag.setField(art)
    }
}
