package com.qbdlx.mobile

import android.app.Application
import android.util.Log
import com.qbdlx.mobile.di.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class QbdlxApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)

        // JAudioTagger is extremely chatty on the JUL logger; keep logcat usable.
        runCatching {
            java.util.logging.Logger.getLogger("org.jaudiotagger").level =
                java.util.logging.Level.SEVERE
            java.util.logging.Logger.getLogger("org.jaudiotagger").useParentHandlers = false
        }.onFailure { Log.w(TAG, "Could not quiet jaudiotagger logging", it) }

        verifyCredentialDiscovery()
    }

    /**
     * Confirms at startup that the Qobuz web-player bundle can still be parsed
     * for an app_id/app_secret pair. This is the one part of the integration
     * that Qobuz can break from their side without any API change, so surfacing
     * it immediately in logcat makes that class of outage obvious.
     *
     * Only logs — a failure here is not fatal because the user can still supply
     * their own credentials from the Advanced section of the login screen.
     */
    private fun verifyCredentialDiscovery() {
        scope.launch {
            val result = withTimeoutOrNull(30_000) {
                runCatching { com.qbdlx.mobile.api.QobuzCredentials().fetch() }
            }
            result?.fold(
                onSuccess = { creds ->
                    Log.i(
                        TAG,
                        "Qobuz credentials OK: app_id=${creds.appId} " +
                            "app_secret=${creds.appSecret.take(4)}…(${creds.appSecret.length} chars) " +
                            "bundle=${creds.bundleUrl}",
                    )
                },
                onFailure = { e ->
                    Log.w(TAG, "Automatic credential discovery failed: ${e.message}")
                },
            )
        }
    }

    private companion object {
        const val TAG = "QbdlxApp"
    }
}
