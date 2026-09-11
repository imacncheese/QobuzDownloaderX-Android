package com.qbdlx.mobile.di

import android.content.Context
import com.qbdlx.mobile.api.QobuzClient
import com.qbdlx.mobile.data.SessionStore
import com.qbdlx.mobile.download.DownloadEngine
import com.qbdlx.mobile.download.StorageManager
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

    @Volatile
    var downloadEngine: DownloadEngine? = null
        private set

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
            )
            initialised = true
        }
    }
}
