package com.qbdlx.mobile.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Qobuz ships its API credentials inside the web-player JavaScript bundle.
 *
 * The `appSecret` has to be reconstructed by de-obfuscating the timezone table
 * (the technique QopenAPI uses: concatenate seed+info+extras, drop the last 44
 * chars, base64-decode).
 *
 * Bundle 8.x also exposes a literal `appSecret:"<32 hex>"` right next to
 * `appId`. That value is *not* the signing secret. Signing `track/getFileUrl`
 * with it is rejected with
 *
 *   HTTP 400 Invalid Request Signature parameter (request_sig)
 *
 * whereas a `request_sig` built from the de-obfuscated secret passes signature
 * validation and the call proceeds (it then fails only on authentication if the
 * token is wrong). The literal pair is therefore used only as a last resort, if
 * the timezone table cannot be read at all.
 */
class QobuzCredentials(
    private val http: OkHttpClient = defaultClient(),
) {

    data class Credentials(val appId: String, val appSecret: String, val bundleUrl: String)

    private val bundleUrlRegex =
        Regex("""<script src="(?<bundleJS>/resources/\d+\.\d+\.\d+-[a-z]\d{3}/bundle\.js)"></script>""")
    private val appIdRegex = Regex("""production:\{api:\{appId:"(?<appID>.*?)",appSecret:""")
    private val appIdSecretRegex = Regex("""production:\{api:\{appId:"(?<appID>.*?)",appSecret:"(?<appSecret>[^"]+)"""")
    private val berlinRegex =
        Regex("""name:"(?<timezone>[A-Za-z/]+/Berlin)",info:"(?<info>[\w=]+)",extras:"(?<extras>[\w=]+)"""")
    private val seedRegex =
        Regex("""[a-z]\.initialSeed\("(?<seed>[\w=]+)",window\.utimezone\.(?<timezone>berlin)\)""")

    /** Set to true once we have burned through a stale cached bundle URL. */
    @Volatile
    var lastBundleUrl: String? = null
        private set

    fun fetch(forceRefresh: Boolean = false): Credentials {
        val bundleUrl = if (!forceRefresh && lastBundleUrl != null) {
            lastBundleUrl!!
        } else {
            discoverBundleUrl().also { lastBundleUrl = it }
        }
        val js = httpGet(BASE_PLAY + bundleUrl)
        return parseBundle(js, bundleUrl)
    }

    private fun discoverBundleUrl(): String {
        val html = httpGet(LOGIN_URL)
        return bundleUrlRegex.find(html)?.groups?.get("bundleJS")?.value
            ?: throw QobuzApiException.CredentialsUnavailable(
                "Could not locate the Qobuz player bundle.js on the login page. " +
                    "The page layout may have changed."
            )
    }

    internal fun parseBundle(js: String, bundleUrl: String): Credentials {
        val appId = appIdSecretRegex.find(js)?.groups?.get("appID")?.value
            ?: appIdRegex.find(js)?.groups?.get("appID")?.value
            ?: throw QobuzApiException.CredentialsUnavailable(
                "Could not extract app_id from the Qobuz bundle."
            )

        // Preferred: the de-obfuscated secret, which is the one the API
        // validates a `request_sig` against.
        deriveSecretFromTimezoneTable(js)?.let { secret ->
            return Credentials(appId, secret, bundleUrl)
        }

        // Last resort: the plaintext pair that bundle 8.x+ carries. Kept only so
        // that a future bundle without a readable timezone table still yields
        // something; requests signed with it are likely to be rejected.
        appIdSecretRegex.find(js)?.let { m ->
            val secret = m.groups["appSecret"]?.value
            if (!secret.isNullOrBlank() && secret.length in 16..128) {
                return Credentials(appId, secret, bundleUrl)
            }
        }

        throw QobuzApiException.CredentialsUnavailable(
            "Found app_id ($appId) but could not derive app_secret from the bundle."
        )
    }

    internal fun deriveSecretFromTimezoneTable(js: String): String? {
        val berlin = berlinRegex.find(js) ?: return null
        val seed = seedRegex.find(js) ?: return null
        val info = berlin.groups["info"]?.value ?: return null
        val extras = berlin.groups["extras"]?.value ?: return null
        val combined = (seed.groups["seed"]?.value ?: return null) + info + extras

        // Trailing 44 chars are a signature/checksum that must be discarded.
        if (combined.length <= 44) return null
        val trimmed = combined.substring(0, combined.length - 44)
        return try {
            val decoded = android.util.Base64.decode(trimmed, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8).trim().ifBlank { null }
        } catch (_: Exception) {
            // Deliberately broad: on the JVM unit tests android.util.Base64 is a
            // stub that returns null, so String(null, ...) throws NPE rather
            // than IllegalArgumentException.
            null
        }
    }

    private fun httpGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "*/*")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw QobuzApiException.Http(resp.code, "GET $url -> HTTP ${resp.code}")
            }
            return resp.body?.string()
                ?: throw QobuzApiException.Http(resp.code, "Empty response body from $url")
        }
    }

    companion object {
        const val BASE_PLAY = "https://play.qobuz.com"
        const val LOGIN_URL = "$BASE_PLAY/login"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
