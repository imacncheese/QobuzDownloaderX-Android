package com.qbdlx.mobile.api

sealed class QobuzApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Network / TLS / DNS level failure. */
    class Network(message: String, cause: Throwable? = null) : QobuzApiException(message, cause)

    /** Non-2xx HTTP response. */
    class Http(val code: Int, message: String) : QobuzApiException(message) {
        val isAuthFailure: Boolean get() = code == 401 || code == 403
        val isRateLimited: Boolean get() = code == 429
    }

    /** Qobuz answered 200 but with a status/message error payload. */
    class Api(val status: String, val apiMessage: String) :
        QobuzApiException("Qobuz API error [$status]: $apiMessage")

    /** Could not determine app_id/app_secret from the web player bundle. */
    class CredentialsUnavailable(message: String) : QobuzApiException(message)

    /** Login rejected. */
    class Auth(message: String) : QobuzApiException(message)

    /** Response body could not be parsed into the expected shape. */
    class Parsing(message: String, cause: Throwable? = null) : QobuzApiException(message, cause)

    /** The requested quality is not available for this track. */
    class QualityUnavailable(message: String) : QobuzApiException(message)
}
