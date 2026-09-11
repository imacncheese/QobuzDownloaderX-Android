package com.qbdlx.mobile.data

import android.content.Context
import com.qbdlx.mobile.api.QobuzClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the Qobuz session so the user does not have to sign in on every
 * launch (the C# app does the same via DPAPI-encrypted settings).
 *
 * The file is private to the app (`MODE_PRIVATE`); no other app on the device
 * can read the token.
 */
class SessionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("qbdlx_session", Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<QobuzClient.Session?> = _session.asStateFlow()

    val current: QobuzClient.Session? get() = _session.value
    val isLoggedIn: Boolean get() = _session.value != null

    private fun load(): QobuzClient.Session? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        val appId = prefs.getString(KEY_APP_ID, null) ?: return null
        val secret = prefs.getString(KEY_APP_SECRET, null) ?: return null
        return QobuzClient.Session(
            appId = appId,
            appSecret = secret,
            userAuthToken = token,
            userId = prefs.getLong(KEY_USER_ID, -1L).takeIf { it > 0 },
            displayName = prefs.getString(KEY_DISPLAY_NAME, null),
            shortLabel = prefs.getString(KEY_SHORT_LABEL, null),
            avatar = prefs.getString(KEY_AVATAR, null),
            email = prefs.getString(KEY_EMAIL, null),
        )
    }

    fun save(session: QobuzClient.Session) {
        prefs.edit()
            .putString(KEY_TOKEN, session.userAuthToken)
            .putString(KEY_APP_ID, session.appId)
            .putString(KEY_APP_SECRET, session.appSecret)
            .putLong(KEY_USER_ID, session.userId ?: -1L)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putString(KEY_SHORT_LABEL, session.shortLabel)
            .putString(KEY_AVATAR, session.avatar)
            .putString(KEY_EMAIL, session.email)
            .apply()
        _session.value = session
    }

    /** Remembered so a returning user does not retype their e-mail. */
    fun lastEmail(): String? = prefs.getString(KEY_EMAIL, null)

    fun clear() {
        prefs.edit().clear().apply()
        _session.value = null
    }

    private companion object {
        const val KEY_TOKEN = "user_auth_token"
        const val KEY_APP_ID = "app_id"
        const val KEY_APP_SECRET = "app_secret"
        const val KEY_USER_ID = "user_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SHORT_LABEL = "short_label"
        const val KEY_AVATAR = "avatar"
        const val KEY_EMAIL = "email"
    }
}
