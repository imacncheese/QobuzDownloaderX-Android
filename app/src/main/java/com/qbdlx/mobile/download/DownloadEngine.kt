package com.qbdlx.mobile.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.qbdlx.mobile.api.QobuzApiException
import com.qbdlx.mobile.api.QobuzClient
import com.qbdlx.mobile.lyrics.Lyrics
import com.qbdlx.mobile.lyrics.LyricsRepository
import com.qbdlx.mobile.lyrics.LyricsResult
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
    private val lyrics: LyricsRepository,
) {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS) // large FLACs may take a long time
        .retryOnConnectionFailure(true)
        .build()

    private val tempDir: File
        get() = File(context.cacheDir, "qbdlx-temp").apply { if (!exists()) mkdirs() }

    /**
     * Remembers which artwork rendition actually resolved, keyed by the base
     * artwork URL.
     *
     * Without this, probing a largest-first candidate list would cost up to ten
     * requests *per track* — roughly 200 for a 20-track album, all for the same
     * cover. The first track resolves it; the rest reuse the answer.
     */
    private val artworkRenditionCache =
        java.util.concurrent.ConcurrentHashMap<String, ArtworkUrls.Size?>()

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
                downloadCoverBytes(item, config.artworkSize).also {
                    Log.i(TAG, "[${item.id}] cover art: ${it?.size ?: 0} bytes")
                }
            } else {
                null
            }

            // Lyrics are fetched once and used twice: embedded in the file and,
            // if asked for, written out as a sidecar .lrc.
            val lyrics = if (config.tag.writeLyrics || config.saveLyricsFile) {
                fetchLyrics(item).also {
                    Log.i(
                        TAG,
                        "[${item.id}] lyrics: ${it?.let { l -> "${l.synced.size} timed lines" } ?: "none"}",
                    )
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
                        lyrics = lyrics.takeIf { config.tag.writeLyrics },
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

            // Sidecar lyrics. Written as LRC when the source was timed, so
            // players that read a .lrc next to the audio get the sync too.
            if (config.saveLyricsFile && lyrics != null) {
                val body = if (lyrics.hasSynced) lyrics.toLrc() else lyrics.plainText
                withContext(Dispatchers.IO) {
                    runCatching {
                        storage.publishRaw(
                            target = resolveTarget(config, relativeDir),
                            fileName = fileName.substringBeforeLast('.') + ".lrc",
                            mimeType = RenameTemplates.mimeFor("lrc"),
                            bytes = body.toByteArray(Charsets.UTF_8),
                        )
                    }.onFailure { Log.w(TAG, "[${item.id}] could not write the .lrc sidecar", it) }
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
            writeCoverArt || writeLyrics
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
     * Fetches cover art at the largest available rendition.
     *
     * The desktop app requests a single size and silently produced no artwork
     * when that rendition was missing (logged as "cover art: 0 bytes"). Here the
     * candidate list is ordered largest-first and probed until one returns real
     * image bytes, so the embedded cover is the biggest copy Qobuz publishes for
     * the release.
     */
    private suspend fun downloadCoverBytes(
        item: DownloadItem,
        preferred: ArtworkUrls.Size = ArtworkUrls.Size.MAX,
    ): ByteArray? {
        val baseKey = (item.coverUrl ?: item.album?.image?.large ?: item.album?.image?.small).orEmpty()

        val candidates = ArtworkUrls.candidates(
            coverUrl = item.coverUrl,
            album = item.album,
            track = item.track,
            sizes = ArtworkUrls.defaultPreference(preferred),
        )

        if (candidates.isEmpty()) {
            Log.w(TAG, "[${item.id}] no cover art URL on the album or track")
            return null
        }

        // A rendition already known to work for this cover is tried first.
        val wasKnown = artworkRenditionCache.containsKey(baseKey)
        val ordered = ArtworkUrls.prioritiseKnown(
            candidates = candidates,
            baseUrl = baseKey,
            known = artworkRenditionCache[baseKey],
        )

        return withContext(Dispatchers.IO) {
            for (url in ordered) {
                val bytes = runCatching {
                    val req = Request.Builder().url(url)
                        .header("User-Agent", com.qbdlx.mobile.api.QobuzCredentials.USER_AGENT)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        resp.body?.bytes()
                    }
                }.getOrNull()

                if (!ArtworkUrls.looksLikeRealArtwork(bytes)) {
                    Log.d(TAG, "[${item.id}] no usable artwork at $url")
                    continue
                }

                // Remember which rendition worked so sibling tracks skip probing.
                ArtworkUrls.Size.entries.firstOrNull { url.endsWith("_${it.suffix}.jpg") }
                    ?.let { artworkRenditionCache[baseKey] = it }

                val data = bytes!!
                Log.i(TAG, "[${item.id}] cover art ${data.size} bytes (rendition '${url.substringAfterLast('_').removeSuffix(".jpg")}') from $url")
                return@withContext data
            }

            // Nothing resolved for this cover; do not cache a failure, in case it
            // was a transient CDN problem rather than a missing rendition.
            if (wasKnown) artworkRenditionCache.remove(baseKey)
            Log.w(
                TAG,
                "[${item.id}] no usable cover art across ${ordered.size} candidate URLs",
            )
            null
        }
    }

    /**
     * Looks up lyrics for a queued item.
     *
     * Best effort by design: a lyrics outage must never cost a download, so any
     * failure is logged and treated as "no lyrics".
     */
    private suspend fun fetchLyrics(item: DownloadItem): Lyrics? {
        val title = item.track?.title ?: item.title
        if (title.isBlank()) return null
        return when (
            val result = lyrics.lyricsFor(
                trackId = item.trackId,
                artist = item.track?.artist?.name ?: item.artist,
                title = title,
                album = item.album?.title ?: item.albumTitle,
                durationSeconds = item.durationSeconds,
            )
        ) {
            is LyricsResult.Found -> result.lyrics
            LyricsResult.Instrumental -> null
            LyricsResult.NotFound -> null
            is LyricsResult.Error -> {
                Log.w(TAG, "[${item.id}] lyrics unavailable: ${result.message}")
                null
            }
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
