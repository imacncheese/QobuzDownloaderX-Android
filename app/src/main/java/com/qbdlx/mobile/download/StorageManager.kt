package com.qbdlx.mobile.download

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException

/**
 * Where finished audio files land.
 *
 * The shipped QBDLX Mobile 0.5.0 release notes describe repeated breakage around
 * custom download paths ("downloads were not tagged and cover files were missing
 * when using custom paths"), falling back to /storage/emulated/0/Download. This
 * implementation avoids direct filesystem paths entirely:
 *
 *  - API 29+ : MediaStore `Downloads` collection. No runtime permission needed,
 *              and the files are visible to every media player / file manager.
 *  - SAF tree: the user may pick an arbitrary folder via ACTION_OPEN_DOCUMENT_TREE
 *              (including an SD card); we then create artist/album subfolders
 *              through DocumentFile.
 *  - API < 29: legacy direct write to the public Downloads directory.
 */
class StorageManager(private val context: Context) {

    sealed interface Target {
        /** MediaStore Downloads, relative sub-path e.g. "Artist/Album". */
        data class MediaStore(val relativeDir: String) : Target

        /** User-picked tree, relative sub-path e.g. "Artist/Album". */
        data class Tree(val treeUri: Uri, val relativeDir: String) : Target

        /** Legacy pre-Q public storage. */
        data class Legacy(val dir: File) : Target
    }

    /**
     * Writes [source] into the currently configured destination.
     * @return a human readable location description for the UI.
     */
    fun publish(target: Target, fileName: String, mimeType: String, source: File): String {
        return when (target) {
            is Target.MediaStore -> publishMediaStore(target.relativeDir, fileName, mimeType, source)
            is Target.Tree -> publishTree(target, fileName, mimeType, source)
            is Target.Legacy -> publishLegacy(target.dir, fileName, source)
        }
    }

    /**
     * Creates/overwrites a sibling non-audio file (Cover.jpg).
     *
     * Safe to call repeatedly for the same album: an existing file with the same
     * name and path is removed first, and a second Cover.jpg no longer throws.
     * Failures are returned as null rather than propagated, because a missing
     * cover must never fail an otherwise good download.
     */
    fun publishRaw(target: Target, fileName: String, mimeType: String, bytes: ByteArray): String? {
        return try {
            when (target) {
                is Target.MediaStore -> {
                    val resolver = context.contentResolver
                    val relPath = "Download/" + target.relativeDir.trim('/')
                        .let { if (it.isEmpty()) "" else "$it/" }

                    runCatching {
                        resolver.query(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            arrayOf(MediaStore.Downloads._ID),
                            "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                            arrayOf(fileName, relPath),
                            null,
                        )?.use { c ->
                            if (c.moveToFirst()) {
                                resolver.delete(
                                    android.content.ContentUris.withAppendedId(
                                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                                    ),
                                    null, null,
                                )
                            }
                        }
                    }

                    val values = android.content.ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, mimeType)
                        put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return null
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: return null
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    "Download/$relPath$fileName"
                }

                is Target.Tree -> {
                    val dir = resolveTreeDir(target.treeUri, target.relativeDir) ?: return null
                    dir.findFile(fileName)?.let { if (it.exists()) it.delete() }
                    val doc = dir.createFile(mimeType, fileName) ?: return null
                    context.contentResolver.openOutputStream(doc.uri)?.use { it.write(bytes) }
                    "${target.relativeDir}/$fileName"
                }

                is Target.Legacy -> {
                    val dir = target.dir.apply { if (!exists()) mkdirs() }
                    val out = File(dir, fileName)
                    out.writeBytes(bytes)
                    out.absolutePath
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("QbdlxStorage", "Could not write $fileName: ${e.message}", e)
            null
        }
    }

    private fun publishMediaStore(
        relativeDir: String,
        fileName: String,
        mimeType: String,
        source: File,
    ): String {
        val resolver = context.contentResolver
        val relPath = "Download/" + relativeDir.trim('/').let { if (it.isEmpty()) "" else "$it/" }

        // Replace any existing file with the same name so re-downloads are idempotent.
        runCatching {
            val existing = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(fileName, relPath),
                null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    resolver.delete(
                        android.content.ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        ),
                        null, null,
                    )
                }
            }
        }

        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relPath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused to create $fileName")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not open an output stream for $fileName")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return "Download/$relPath$fileName"
    }

    private fun publishTree(
        target: Target.Tree,
        fileName: String,
        mimeType: String,
        source: File,
    ): String {
        val dir = resolveTreeDir(target.treeUri, target.relativeDir)
            ?: throw IOException("Could not open the selected download folder. Re-pick it in Settings.")

        dir.findFile(fileName)?.let { if (it.exists()) it.delete() }

        val doc = dir.createFile(mimeType, fileName)
            ?: throw IOException("Could not create $fileName in ${dir.name}")

        try {
            context.contentResolver.openOutputStream(doc.uri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            } ?: throw IOException("Could not open an output stream for $fileName")
        } catch (e: Exception) {
            runCatching { doc.delete() }
            throw e
        }
        return "${target.relativeDir}/$fileName"
    }

    private fun publishLegacy(dir: File, fileName: String, source: File): String {
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Could not create ${dir.absolutePath}")
        }
        val dest = File(dir, fileName)
        source.inputStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        return dest.absolutePath
    }

    private fun resolveTreeDir(treeUri: Uri, relativeDir: String): DocumentFile? {
        var current = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val parts = relativeDir.split('/').filter { it.isNotBlank() }
        for (part in parts) {
            current = current.findFile(part)
                ?: current.createDirectory(part)
                ?: return current
        }
        return current
    }

    /** Default destination when the user has not picked a folder. */
    fun defaultTarget(relativeDir: String): Target = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Target.MediaStore(relativeDir)
        else -> Target.Legacy(
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                relativeDir,
            )
        )
    }
}
