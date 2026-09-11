package com.qbdlx.mobile.download

import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Embeds cover art into a FLAC file by writing a native PICTURE metadata block.
 *
 * JAudioTagger's own artwork path cannot be used on Android: `FlacTag.createField`
 * decodes the image through `javax.imageio.ImageIO` and `java.awt.image.BufferedImage`,
 * and Android ships neither. On a device that call fails with
 *
 *     NoClassDefFoundError: Failed resolution of: Ljavax/imageio/ImageIO
 *
 * so cover art was skipped for every FLAC download. Writing the block directly
 * avoids AWT entirely and keeps the file spec-compliant.
 *
 * PICTURE block layout (FLAC format spec, section 8):
 *   4  picture type (3 = front cover)
 *   4  MIME length, MIME string
 *   4  description length, UTF-8 description
 *   4  width, 4 height, 4 colour depth, 4 palette size
 *   4  picture data length, picture bytes
 *
 * Metadata blocks form a chain: each has a 4-byte header whose top bit marks the
 * last block. The new PICTURE block therefore has to become the final metadata
 * block, with the previous final block's flag cleared.
 */
object FlacPicture {

    private const val TAG = "QbdlxFlacPicture"
    private const val BLOCK_STREAMINFO = 0
    private const val BLOCK_PICTURE = 6
    private const val PICTURE_TYPE_FRONT_COVER = 3
    private const val MAX_BLOCK_BYTES = 0xFFFFFF // 24-bit length field

    private val FLAC_MARKER = byteArrayOf(0x66, 0x4C, 0x61, 0x43) // "fLaC"

    /** A metadata block located in the file. */
    internal data class Block(
        val type: Int,
        /** Offset of the block's 4-byte header. */
        val headerOffset: Long,
        /** Payload length, excluding the header. */
        val length: Int,
        val isLast: Boolean,
    ) {
        val payloadOffset: Long get() = headerOffset + 4
        val endOffset: Long get() = headerOffset + 4 + length
    }

    /**
     * Walks the metadata chain of an already-open FLAC file.
     * The file position is left at the start of the audio frames.
     */
    internal fun readMetadataBlocks(raf: RandomAccessFile): List<Block> {
        val marker = ByteArray(4)
        raf.seek(0)
        raf.readFully(marker)
        require(marker.contentEquals(FLAC_MARKER)) { "Not a FLAC file (missing fLaC marker)" }

        val blocks = mutableListOf<Block>()
        var pos = 4L
        var guard = 0
        while (guard++ < 4096) {
            raf.seek(pos)
            val header = raf.readUnsignedByte()
            val isLast = (header and 0x80) != 0
            val type = header and 0x7F
            val len = raf.readUnsignedByte() shl 16 or
                (raf.readUnsignedByte() shl 8) or
                raf.readUnsignedByte()

            blocks += Block(type, pos, len, isLast)
            pos += 4 + len
            if (isLast) break
        }
        require(blocks.isNotEmpty()) { "FLAC file has no metadata blocks" }
        require(blocks.first().type == BLOCK_STREAMINFO) { "First FLAC block is not STREAMINFO" }
        require(blocks.last().isLast) { "Malformed FLAC: metadata chain never terminated" }
        return blocks
    }

    internal fun encodeVarint(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value.toLong()
        while (true) {
            out.add(0, (v and 0x7F).toByte())
            v = v shr 7
            if (v == 0L) break
        }
        // Set the continuation bit on every byte except the last.
        for (i in 0 until out.size - 1) {
            out[i] = (out[i].toInt() or 0x80).toByte()
        }
        return out.toByteArray()
    }

    private fun probeDimensions(bytes: ByteArray): Triple<Int, Int, Int> = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val depth = if (opts.outMimeType?.contains("png", ignoreCase = true) == true) 32 else 24
        Triple(opts.outWidth.coerceAtLeast(0), opts.outHeight.coerceAtLeast(0), depth)
    } catch (e: Throwable) {
        Log.w(TAG, "Could not probe cover dimensions", e)
        Triple(0, 0, 0)
    }

    internal fun buildPictureBlock(picture: ByteArray, mime: String, description: String): ByteArray {
        val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
        val descBytes = description.toByteArray(Charsets.UTF_8)
        val (w, h, depth) = probeDimensions(picture)

        val buf = java.nio.ByteBuffer.allocate(
            4 + 4 + mimeBytes.size + 4 + descBytes.size + 16 + 4 + picture.size
        )
        buf.putInt(PICTURE_TYPE_FRONT_COVER)
        buf.putInt(mimeBytes.size).put(mimeBytes)
        buf.putInt(descBytes.size).put(descBytes)
        buf.putInt(w).putInt(h).putInt(depth)
        buf.putInt(0) // palette size, 0 for truecolour
        buf.putInt(picture.size).put(picture)
        return buf.array()
    }

    private fun guessMime(bytes: ByteArray): String = when {
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
        else -> "image/jpeg"
    }

    /** Writes a metadata block header (1 flag/type byte + 24-bit length) big-endian. */
    internal fun writeHeader(out: java.io.OutputStream, header: Int) {
        out.write((header ushr 24) and 0xFF)
        out.write((header ushr 16) and 0xFF)
        out.write((header ushr 8) and 0xFF)
        out.write(header and 0xFF)
    }

    /** Packs a metadata block header word: top bit = last flag, low 7 = type. */
    internal fun blockHeader(type: Int, length: Int, isLast: Boolean): Int =
        (((if (isLast) 0x80 else 0x00) or (type and 0x7F)) shl 24) or (length and 0xFFFFFF)

    /**
     * Inserts or replaces the front-cover PICTURE block.
     *
     * Any existing picture block is removed and the new one appended as the final
     * metadata block, so the chain stays well-formed and no duplicate artwork
     * accumulates across re-downloads.
     *
     * @return null on success, otherwise a human-readable reason
     */
    fun embed(file: File, picture: ByteArray, description: String = "Cover"): String? {
        if (picture.isEmpty()) return "Cover art data was empty"

        val block = buildPictureBlock(picture, guessMime(picture), description)
        val payloadSize = 4 + block.size
        if (payloadSize > MAX_BLOCK_BYTES) return "Cover art is too large for a FLAC metadata block"

        return try {
            RandomAccessFile(file, "rw").use { raf ->
                val blocks = readMetadataBlocks(raf)
                val existing = blocks.firstOrNull { it.type == BLOCK_PICTURE }

                // Start of the audio frames, i.e. the end of the metadata chain.
                val framesStart = blocks.last().endOffset

                // Read the audio frames up front; they are the part we must not disturb.
                val framesLength = (raf.length() - framesStart).coerceAtLeast(0L)
                if (framesLength > Int.MAX_VALUE) return "File is too large to embed artwork"
                val frames = ByteArray(framesLength.toInt())
                raf.seek(framesStart)
                raf.readFully(frames)

                // Rebuild the metadata region inline:
                //   marker + kept blocks + new PICTURE block, then the frames.
                // Every kept block is written WITHOUT the last flag; the PICTURE
                // block is the single final block. The flags are then verified by
                // re-reading the file, so a bug here cannot ship silently.
                val out = java.io.ByteArrayOutputStream()
                out.write(FLAC_MARKER)

                val kept = blocks.filterNot { it.type == BLOCK_PICTURE }

                kept.forEach { b ->
                    val data = ByteArray(b.length)
                    raf.seek(b.payloadOffset)
                    raf.readFully(data)

                    writeHeader(out, blockHeader(b.type, b.length, isLast = false))
                    out.write(data)
                }

                // The new PICTURE block is the final metadata block.
                writeHeader(out, blockHeader(BLOCK_PICTURE, payloadSize, isLast = true))
                out.write(block)

                out.write(frames)

                val result = out.toByteArray()
                raf.seek(0)
                raf.write(result)
                raf.setLength(result.size.toLong())

                // Verify: exactly one block may carry the last-metadata flag, and
                // it must be the PICTURE block we just wrote.
                val written = readMetadataBlocks(raf)
                val lastBlocks = written.filter { it.isLast }
                if (lastBlocks.size != 1 || lastBlocks.single().type != BLOCK_PICTURE) {
                    val summary = written.joinToString(", ") { "type=${it.type}${if (it.isLast) "(last)" else ""}" }
                    Log.e(TAG, "metadata chain is malformed after embed: $summary")
                    return "Cover art written but the FLAC metadata chain is malformed"
                }

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    val head = result.take(48).joinToString(" ") { "%02x".format(it) }
                    Log.d(TAG, "after embed: total=${result.size} blocks=${written.size} head=$head")
                }

                Log.i(
                    TAG,
                    "embedded cover art: ${picture.size} bytes " +
                        "(replaced ${if (existing != null) "${existing.length} bytes" else "nothing"})",
                )
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "FLAC cover art embed failed", e)
            "Could not embed cover art: ${e.javaClass.simpleName}"
        }
    }
}
