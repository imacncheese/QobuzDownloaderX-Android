package com.qbdlx.mobile.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.qbdlx.mobile.api.QobuzApiException
import com.qbdlx.mobile.api.QobuzClient
import com.qbdlx.mobile.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads a single queue item end to end:
 *   resolve signed URL -> stream to a temp file -> tag -> publish to storage.
 *
 * The tmp-then-move strategy mirrors DownloadFile.DownloadStream in the C#
 * reference: nothing half-written ever appears in the user's library, and
 * tagging always happens on a real file (JAudioTagger requires one).
 */
class DownloadEngine(
    private val context: Context,
    private val client: QobuzClient,
    private val settings: SettingsStore,
    private val storage: StorageManager,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // large FLACs may take a long time
        .retryOnConnectionFailure(true)
        .build()

    private val tempDir: File
        get() = File(context.cacheDir, "qbdlx-temp").apply { if (!exists()) mkdirs() }

    /** Runs one item. Returns true when the file landed successfully. */
    suspend fun process(state: DownloadState): Boolean {
        val item = state.item
        val config = settings.current
        val rawFile = File(tempDir, "qbdlx_${item.id}.part")
        val taggedFile = File(tempDir, "qbdlx_${item.id}.tagged")

        try {
            DownloadQueue.update(item.id) {
                it.copy(status = DownloadStatus.RESOLVING, error = null, progressPercent = 0)
            }

            val (fileUrl, quality) = resolveWithFallback(item.trackId, config.quality)
            Log.i(TAG, "[${item.id}] resolved quality=${quality.label}, url starts ${fileUrl.take(60)}")
            DownloadQueue.update(item.id) {
                it.copy(status = DownloadStatus.DOWNLOADING, resolvedQuality = quality)
            }

            streamToFile(fileUrl, rawFile, item.id)
            Log.i(TAG, "[${item.id}] downloaded ${rawFile.length()} bytes to ${rawFile.name}")
            if (!coroutineContext.isActive) throw CancellationException()

            DownloadQueue.update(item.id) { it.copy(status = DownloadStatus.TAGGING) }

            val coverBytes = if (config.tag.writeCoverArt || config.saveCoverToFolder) {
                downloadCoverBytes(item).also {
                    Log.i(TAG, "[${item.id}] cover art: ${it?.size ?: 0} bytes")
                }
            } else {
                null
            }

            // Tagging is best-effort and must never cost the user the download.
            // If it fails we still publish the untouched audio, because the
            // bytes on disk are the valuable part and tags can be redone later.
            var tagWarning: String? = null
            var fileToPublish = rawFile

            if (shouldTag(config)) {
                val tagResult = withContext(Dispatchers.IO) {
                    MetadataTagger.tag(
                        file = rawFile,
                        album = item.album,
                        track = item.track,
                        coverArt = coverBytes.takeIf { config.tag.writeCoverArt },
                        options = config.tag,
                        workingCopy = taggedFile,
                    )
                }
                tagWarning = tagResult.warning
                // The tagger may rename its output to carry the real audio
                // extension (JAudioTagger dispatches readers on extension), so
                // publish whatever file it actually produced.
                val produced = tagResult.file
                if (tagResult.ok && produced != null && produced.exists() && produced.length() > 0L) {
                    fileToPublish = produced
                    Log.i(TAG, "[${item.id}] tagged OK (${produced.length()} bytes, ${produced.name})")
                } else {
                    // Tagging failed or damaged the file: publish the pristine
                    // download instead of a broken one.
                    tagWarning = tagResult.warning ?: "Tagging failed; saved without tags."
                    Log.w(TAG, "[${item.id}] $tagWarning")
                }
            }

            val ext = quality.extension
            val relativeDir = buildRelativeDir(config, item)
            val fileName = buildFileName(config, item, ext)

            val saved = withContext(Dispatchers.IO) {
                storage.publish(
                    target = resolveTarget(config, relativeDir),
                    fileName = fileName,
                    mimeType = RenameTemplates.mimeFor(ext),
                    source = fileToPublish,
                )
            }
            Log.i(TAG, "[${item.id}] published to $saved")

            if (config.saveCoverToFolder && coverBytes != null) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        storage.publishRaw(
                            target = resolveTarget(config, relativeDir),
                            fileName = "Cover.jpg",
                            mimeType = "image/jpeg",
                            bytes = coverBytes,
                        )
                    }
                }
            }

            DownloadQueue.update(item.id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    progressPercent = 100,
                    savedTo = saved,
                    warning = tagWarning,
                )
            }
            return true
        } catch (e: CancellationException) {
            DownloadQueue.update(item.id) { it.copy(status = DownloadStatus.CANCELLED) }
            return false
        } catch (e: Throwable) {
            Log.e(TAG, "[${item.id}] failed: ${describe(e)}", e)
            DownloadQueue.update(item.id) {
                it.copy(status = DownloadStatus.FAILED, error = describe(e))
            }
            return false
        } finally {
            runCatching { rawFile.delete() }
            runCatching { taggedFile.delete() }
            // The tagger may have produced a renamed copy carrying the real
            // audio extension; clean that up too.
            runCatching {
                tempDir.listFiles()
                    ?.filter { it.name.startsWith("qbdlx_${item.id}") }
                    ?.forEach { it.delete() }
            }
        }
    }

    private fun shouldTag(config: SettingsStore.Settings): Boolean = with(config.tag) {
        writeAlbumTitle || writeAlbumArtist || writeTrackArtist || writeComposer ||
            writeCopyright || writeLabel || writeDiscNumber || writeDiscTotal ||
            writeGenre || writeIsrc || writeUrl || writeReleaseType || writeExplicit ||
            writeTrackTitle || writeTrackNumber || writeTrackTotal || writeUpc ||
            writeReleaseDate || writeYear || writeComment || writeReplayGain ||
            writeCoverArt
    }

    /**
     * Tries the requested quality, then degrades: Hi-Res -> CD -> MP3.
     * Qobuz refuses formats the user's plan or the label does not license,
     * which is the single most common real-world download failure.
     */
    private suspend fun resolveWithFallback(trackId: String, requested: Quality): Pair<String, Quality> {
        val chain = Quality.fallbackChain(requested)
        var lastError: Throwable? = null
        for (quality in chain) {
            try {
                val resp = client.getFileUrl(trackId, quality.formatId)
                val url = resp.url
                if (!url.isNullOrBlank()) return url to quality
            } catch (e: QobuzApiException.QualityUnavailable) {
                lastError = e
            } catch (e: QobuzApiException.Http) {
                // 400/404 here usually means "this format is not available".
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No stream URL available for track $trackId")
    }

    private suspend fun streamToFile(url: String, dest: File, itemId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", com.qbdlx.mobile.api.QobuzCredentials.USER_AGENT)
            .build()

        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw QobuzApiException.Network("Could not reach the Qobuz CDN: ${e.message}", e)
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                throw QobuzApiException.Http(resp.code, "CDN returned HTTP ${resp.code} for the audio stream.")
            }
            val body = resp.body ?: throw QobuzApiException.Network("Empty audio response body.")
            val total = body.contentLength()

            var written = 0L
            var windowBytes = 0L
            var windowStart = System.currentTimeMillis()
            var lastEmit = 0L

            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        if (DownloadQueue.isCancelled(itemId)) throw CancellationException()

                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        windowBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 300) {
                            val elapsed = (now - windowStart).coerceAtLeast(1)
                            val speed = windowBytes * 1000L / elapsed
                            val pct = if (total > 0) ((written * 100) / total).toInt() else 0
                            DownloadQueue.update(itemId) {
                                it.copy(
                                    bytesDownloaded = written,
                                    totalBytes = total,
                                    progressPercent = pct.coerceIn(0, 100),
                                    speedBytesPerSecond = speed,
                                )
                            }
                            lastEmit = now
                            windowBytes = 0
                            windowStart = now
                        }
                    }
                    out.flush()
                }
            }

            if (written <= 0L) {
                throw QobuzApiException.Network("Qobuz sent no audio data (0 bytes).")
            }
            // A truncated stream would otherwise be tagged and saved as a corrupt file.
            if (total > 0 && written < total) {
                throw QobuzApiException.Network(
                    "Download incomplete: got $written of $total bytes. Check your connection and retry."
                )
            }

            DownloadQueue.update(itemId) {
                it.copy(bytesDownloaded = written, totalBytes = total, progressPercent = 100)
            }
        }
    }

    /**
     * Fetches cover art, trying the largest rendition first.
     *
     * The desktop app only ever requests `_1400`, so a missing large image made
     * artwork vanish silently (logged as "cover art: 0 bytes"). Here every known
     * rendition is tried, including the album's own image, before giving up.
     */
    private suspend fun downloadCoverBytes(item: DownloadItem): ByteArray? {
        val candidates = linkedSetOf<String>()

        fun add(url: String?) {
            if (url.isNullOrBlank()) return
            // Prefer the biggest available rendition of each distinct image.
            candidates += url
            candidates += url.replace(Regex("""_\d+\.jpg"""), "_1400.jpg")
            candidates += url.replace(Regex("""_\d+\.jpg"""), "_600.jpg")
            if (!url.contains("_")) candidates += url
        }

        add(item.coverUrl)
        add(item.album?.image?.large)
        add(item.album?.image?.small)
        add(item.track?.album?.image?.large)
        add(item.track?.album?.image?.small)

        if (candidates.isEmpty()) {
            Log.w(TAG, "[${item.id}] no cover art URL on the album or track")
            return null
        }

        return withContext(Dispatchers.IO) {
            for (url in candidates) {
                val bytes = runCatching {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", com.qbdlx.mobile.api.QobuzCredentials.USER_AGENT)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        resp.body?.bytes()
                    }
                }.getOrNull()

                if (bytes != null && bytes.isNotEmpty()) {
                    Log.i(TAG, "[${item.id}] cover art ${bytes.size} bytes from $url")
                    return@withContext bytes
                }
            }
            Log.w(TAG, "[${item.id}] could not fetch cover art from ${candidates.size} candidate URLs")
            null
        }
    }

    private fun resolveTarget(config: SettingsStore.Settings, relativeDir: String): StorageManager.Target {
        val tree = config.customTreeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return if (tree != null) {
            StorageManager.Target.Tree(tree, relativeDir)
        } else {
            storage.defaultTarget(relativeDir)
        }
    }

    private fun buildRelativeDir(config: SettingsStore.Settings, item: DownloadItem): String {
        val artist = RenameTemplates.expandToPath(config.artistTemplate, item.album, item.track, "flac")
            .joinToString("/")
            .ifBlank { "Unknown Artist" }
        val album = RenameTemplates.expandToPath(config.albumTemplate, item.album, item.track, "flac")
            .joinToString("/")
            .ifBlank { "Unknown Album" }
        val isMultiDisc = (item.track?.media_count ?: 1) > 1
        val base = "$artist/$album"
        return if (isMultiDisc && item.discNumber > 0) {
            "$base/CD${item.discNumber.toString().padStart(2, '0')}"
        } else {
            base
        }
    }

    private fun buildFileName(config: SettingsStore.Settings, item: DownloadItem, ext: String): String {
        val template = config.trackTemplate
        var name = RenameTemplates.expand(template, item.album, item.track, ext)
        if (name.isBlank()) {
            name = "${item.trackNumber.toString().padStart(2, '0')} - ${item.title}"
        }
        name = RenameTemplates.sanitize(name, maxLength = 180)
        return "$name.$ext"
    }

    private fun describe(e: Throwable): String = when (e) {
        is QobuzApiException -> e.message ?: e.javaClass.simpleName
        is IOException -> "Network error: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
    }

    private companion object {
        const val TAG = "QbdlxDownload"
    }
}
