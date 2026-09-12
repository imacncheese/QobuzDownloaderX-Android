package com.qbdlx.mobile.di

import android.content.Context
import com.qbdlx.mobile.api.QobuzClient
import com.qbdlx.mobile.data.SessionStore
import com.qbdlx.mobile.download.DownloadEngine
import com.qbdlx.mobile.download.StorageManager
import com.qbdlx.mobile.download.Quality
import com.qbdlx.mobile.settings.SettingsStore

/**
 * Minimal manual DI. Initialised once from [com.qbdlx.mobile.QbdlxApp] so both
 * the Compose UI and the background [com.qbdlx.mobile.download.DownloadService]
 * see the same client instance and therefore the same auth session.
 */
object AppGraph {

    @Volatile private var initialised = false

    lateinit var client: QobuzClient
        private set
    lateinit var sessions: SessionStore
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var storage: StorageManager
        private set

    /**
     * Lyrics come from an external service rather than Qobuz, and the session
     * cache is only useful if the UI and the download engine share one instance:
     * playing a track then downloading it should not look the lyrics up twice.
     */
    val lyrics: com.qbdlx.mobile.lyrics.LyricsRepository by lazy {
        com.qbdlx.mobile.lyrics.LyricsRepository()
    }

    @Volatile
    var downloadEngine: DownloadEngine? = null
        private set

    /**
     * Playback handle. Built lazily because it connects to a media session
     * service, which should not be started just because the app launched.
     */
    @Volatile
    private var playerRef: com.qbdlx.mobile.playback.PlayerController? = null

    fun player(context: Context): com.qbdlx.mobile.playback.PlayerController {
        playerRef?.let { return it }
        synchronized(this) {
            playerRef?.let { return it }
            val created = com.qbdlx.mobile.playback.PlayerController(
                context = context.applicationContext,
                resolveStreamUrl = { trackId -> resolveStreamUrl(trackId) },
            )
            playerRef = created
            return created
        }
    }

    /**
     * Resolves a signed stream URL for playback.
     *
     * Mirrors the download path's quality fallback: Qobuz refuses formats a
     * release is not licensed for, and playback should degrade rather than fail.
     */
    private suspend fun resolveStreamUrl(trackId: String): String {
        val requested = Quality.fromId(settings.current.quality.formatId)
        var lastError: Throwable? = null
        for (quality in Quality.fallbackChain(requested)) {
            try {
                val url = client.getFileUrl(trackId, quality.formatId).url
                if (!url.isNullOrBlank()) return url
            } catch (e: Throwable) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No stream URL available for track $trackId")
    }

    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val app = context.applicationContext

            sessions = SessionStore(app)
            settings = SettingsStore(app)
            storage = StorageManager(app)
            client = QobuzClient()

            // Restore a previous sign-in so downloads work without reopening the app.
            sessions.current?.let { client.session = it }

            downloadEngine = DownloadEngine(
                context = app,
                client = client,
                settings = settings,
                storage = storage,
                lyrics = lyrics,
            )
            initialised = true
        }
    }
}
