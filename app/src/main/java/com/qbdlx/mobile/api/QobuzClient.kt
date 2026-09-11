package com.qbdlx.mobile.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Qobuz JSON API client.
 *
 * Ported from ImAiiR/QobuzDownloaderX (C#) + the Qo(penAPI) library it uses.
 *
 * Two request styles are deliberately kept apart:
 *  - [paramUrl] builds the query string by hand without percent-encoding, which
 *    is what the C# client does and what `track/getFileUrl` depends on, because
 *    the `request_sig` is computed over the *raw* parameter order and values.
 *  - [formParamUrl] percent-encodes values, used for login where the password
 *    may contain reserved characters.
 */
class QobuzClient(
    private val http: OkHttpClient = defaultHttpClient(),
    private val credentials: QobuzCredentials = QobuzCredentials(),
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    // ---------------------------------------------------------------- session

    data class Session(
        val appId: String,
        val appSecret: String,
        val userAuthToken: String,
        val userId: Long?,
        val displayName: String?,
        val shortLabel: String?,
        val avatar: String?,
        val email: String?,
    )

    @Volatile
    var session: Session? = null

    val isLoggedIn: Boolean get() = session != null

    private fun requireSession(): Session = session ?: throw QobuzApiException.Auth(
        "Not signed in. Sign in to Qobuz first."
    )

    // ------------------------------------------------------------------ login

    /**
     * Discovers app_id/app_secret from the Qobuz web player, then authenticates.
     * Mirrors LoginForm.loginBackground_DoWork in the C# reference.
     */
    suspend fun login(
        email: String?,
        password: String?,
        token: String? = null,
        appIdOverride: String? = null,
        appSecretOverride: String? = null,
    ): Session = withContext(Dispatchers.IO) {
        val creds = if (!appIdOverride.isNullOrBlank() && !appSecretOverride.isNullOrBlank()) {
            QobuzCredentials.Credentials(
                appIdOverride.trim(),
                appSecretOverride.trim(),
                bundleUrl = "custom",
            )
        } else {
            try {
                credentials.fetch()
            } catch (e: QobuzApiException.CredentialsUnavailable) {
                // Bundle layout may have rotated since we cached the URL.
                credentials.fetch(forceRefresh = true)
            }
        }

        val loginResponse = requestLogin(
            appId = creds.appId,
            email = if (token.isNullOrBlank()) email else null,
            password = if (token.isNullOrBlank()) password else null,
            token = token,
        )

        val authToken = loginResponse.user_auth_token
            ?: throw QobuzApiException.Auth(
                loginResponse.message ?: "Qobuz did not return an authentication token."
            )

        val account = loginResponse.user
        val resolvedSecret = if (!appSecretOverride.isNullOrBlank()) {
            appSecretOverride.trim()
        } else {
            creds.appSecret
        }

        Session(
            appId = creds.appId,
            appSecret = resolvedSecret,
            userAuthToken = authToken,
            userId = account?.id ?: loginResponse.id,
            displayName = account?.display_name ?: account?.login ?: email,
            shortLabel = account?.credential?.parameters?.short_label,
            avatar = account?.avatar,
            email = account?.email ?: email,
        ).also { session = it }
    }

    /** Validates a stored session by hitting a cheap authenticated endpoint. */
    suspend fun validateSession(s: Session): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = paramUrl("favorite/getUserFavorites", linkedMapOf(
                "app_id" to s.appId,
                "user_id" to (s.userId?.toString() ?: return@withContext false),
                "type" to "albums",
                "limit" to "1",
                "offset" to "0",
                "user_auth_token" to s.userAuthToken,
            ))
            getJson(url).isNotBlank()
        } catch (_: Exception) {
            false
        }
    }

    fun logout() {
        session = null
    }

    private fun requestLogin(
        appId: String,
        email: String?,
        password: String?,
        token: String?,
    ): LoginResponse {
        val params = linkedMapOf<String, String>()
        if (!email.isNullOrBlank()) params["email"] = email
        if (!password.isNullOrBlank()) params["password"] = password
        if (!token.isNullOrBlank()) params["user_auth_token"] = token

        val url = formParamUrl("user/login", params)
        val body = runCatching {
            rawGet(
                url,
                extraHeaders = mapOf("X-App-Id" to appId),
            )
        }.getOrElse { e ->
            throw when (e) {
                is QobuzApiException -> e
                is IOException -> QobuzApiException.Network(
                    "Could not reach Qobuz to sign in: ${e.message}", e
                )
                else -> e
            }
        }

        val parsed = parse<LoginResponse>(body)
        parsed.status?.let { status ->
            if (status.equals("error", ignoreCase = true) || parsed.user_auth_token == null) {
                throw QobuzApiException.Auth(
                    parsed.message ?: "Login failed (status=$status)."
                )
            }
        }
        if (parsed.user_auth_token == null) {
            throw QobuzApiException.Auth(
                parsed.message ?: "Login failed: no user_auth_token in the response."
            )
        }
        return parsed
    }

    // --------------------------------------------------------------- catalog

    suspend fun getAlbum(albumId: String, withAuth: Boolean = true): Album =
        withContext(Dispatchers.IO) {
            val s = if (withAuth) requireSession() else null
            val params = linkedMapOf("app_id" to (s?.appId ?: publicAppId()))
            s?.let { params["user_auth_token"] = it.userAuthToken }
            params["album_id"] = albumId
            params["limit"] = "500"
            params["offset"] = "0"

            val album = parse<Album>(getJson(paramUrl("album/get", params)))
            checkApiError(album.id == null, album.toString())
            album
        }

    /** Fetches every track of an album, following the pagination loop from the C# GetInfo class. */
    suspend fun getFullAlbum(albumId: String): Album = withContext(Dispatchers.IO) {
        val first = getAlbum(albumId)
        val page = first.tracks

        Log.i(
            TAG,
            "album/get id=$albumId -> title='${first.title}' tracksCountField=${first.tracks_count} " +
                "tracksPage=${if (page == null) "null" else "items=${page.items.size} total=${page.total}"}",
        )

        if (page == null) {
            // The album loaded but carried no track page at all. Surfacing this in
            // logcat is the difference between "the screen is empty" and knowing why.
            Log.w(TAG, "album/get returned no tracks object for id=$albumId")
            return@withContext first
        }

        val total = page.total ?: page.items.size
        if (page.items.size >= total) return@withContext first

        val s = requireSession()
        val all = page.items.toMutableList()
        var offset = page.items.size
        while (all.size < total && offset < MAX_OFFSET) {
            val params = linkedMapOf(
                "app_id" to s.appId,
                "user_auth_token" to s.userAuthToken,
                "album_id" to albumId,
                "limit" to "500",
                "offset" to offset.toString(),
            )
            val next = parse<Album>(getJson(paramUrl("album/get", params)))
            val items = next.tracks?.items ?: break
            if (items.isEmpty()) break
            all += items
            offset += items.size
        }
        Log.i(TAG, "album/get id=$albumId collected ${all.size} of $total tracks")
        first.copy(tracks = page.copy(items = all))
    }

    suspend fun getTrack(trackId: String): Track = withContext(Dispatchers.IO) {
        val s = requireSession()
        val params = linkedMapOf(
            "app_id" to s.appId,
            "user_auth_token" to s.userAuthToken,
            "track_id" to trackId,
        )
        parse<Track>(getJson(paramUrl("track/get", params)))
    }

    suspend fun getArtist(artistId: String): Artist = withContext(Dispatchers.IO) {
        val s = requireSession()
        val extra = "albums%2Calbums_with_last_release"
        val first = parse<Artist>(
            getJson(
                paramUrl("artist/get", linkedMapOf(
                    "app_id" to s.appId,
                    "user_auth_token" to s.userAuthToken,
                    "artist_id" to artistId,
                    "extra" to extra,
                    "limit" to "500",
                    "offset" to "0",
                ))
            )
        )
        val page = first.albums ?: return@withContext first
        val total = page.total ?: page.items.size
        if (page.items.size >= total) return@withContext first

        val all = page.items.toMutableList()
        var offset = page.items.size
        while (all.size < total && offset < MAX_OFFSET) {
            val next = parse<Artist>(
                getJson(
                    paramUrl("artist/get", linkedMapOf(
                        "app_id" to s.appId,
                        "user_auth_token" to s.userAuthToken,
                        "artist_id" to artistId,
                        "extra" to extra,
                        "limit" to "500",
                        "offset" to offset.toString(),
                    ))
                )
            )
            val items = next.albums?.items ?: break
            if (items.isEmpty()) break
            all += items
            offset += items.size
        }
        first.copy(albums = page.copy(items = all))
    }

    suspend fun getPlaylist(playlistId: String): Playlist = withContext(Dispatchers.IO) {
        val s = requireSession()
        val first = parse<Playlist>(
            getJson(
                paramUrl("playlist/get", linkedMapOf(
                    "app_id" to s.appId,
                    "user_auth_token" to s.userAuthToken,
                    "playlist_id" to playlistId,
                    "extra" to "tracks",
                    "limit" to "500",
                    "offset" to "0",
                ))
            )
        )
        val page = first.tracks ?: return@withContext first
        val total = page.total ?: page.items.size
        if (page.items.size >= total) return@withContext first

        val all = page.items.toMutableList()
        var offset = page.items.size
        while (all.size < total && offset < MAX_OFFSET) {
            val next = parse<Playlist>(
                getJson(
                    paramUrl("playlist/get", linkedMapOf(
                        "app_id" to s.appId,
                        "user_auth_token" to s.userAuthToken,
                        "playlist_id" to playlistId,
                        "extra" to "tracks",
                        "limit" to "500",
                        "offset" to offset.toString(),
                    ))
                )
            )
            val items = next.tracks?.items ?: break
            if (items.isEmpty()) break
            all += items
            offset += items.size
        }
        first.copy(tracks = page.copy(items = all))
    }

    /**
     * Fetches the user's favourite tracks.
     *
     * Only "tracks" is implemented: the Qobuz favourites payload nests albums,
     * tracks and artists under different keys with different shapes, and the
     * current UI only surfaces favourite *tracks*. Asking for another type
     * throws rather than silently returning an empty list.
     */
    suspend fun getFavoriteTracks(): List<Track> = withContext(Dispatchers.IO) {
        val s = requireSession()
        val userId = s.userId ?: throw QobuzApiException.Auth("No user id in session.")
        val out = mutableListOf<Track>()
        var offset = 0
        while (offset < MAX_OFFSET) {
            val body = getJson(
                paramUrl("favorite/getUserFavorites", linkedMapOf(
                    "app_id" to s.appId,
                    "user_id" to userId.toString(),
                    "type" to "tracks",
                    "limit" to "500",
                    "offset" to offset.toString(),
                    "user_auth_token" to s.userAuthToken,
                ))
            )
            val page = parse<FavoritesResponse>(body).tracks ?: break
            val items = page.items
            if (items.isEmpty()) break
            out += items
            offset += items.size
            val total = page.total ?: break
            if (out.size >= total) break
        }
        out
    }

    // ---------------------------------------------------------------- search

    suspend fun searchAlbums(query: String, limit: Int = 50, offset: Int = 0): AlbumPage =
        withContext(Dispatchers.IO) {
            val s = requireSession()
            val res = parse<AlbumSearchResult>(
                getJson(
                    paramUrl("album/search", linkedMapOf(
                        "app_id" to s.appId,
                        "user_auth_token" to s.userAuthToken,
                        "query" to query,
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                    ))
                )
            )
            res.albums ?: AlbumPage()
        }

    suspend fun searchTracks(query: String, limit: Int = 50, offset: Int = 0): TrackPage =
        withContext(Dispatchers.IO) {
            val s = requireSession()
            val res = parse<TrackSearchResult>(
                getJson(
                    paramUrl("track/search", linkedMapOf(
                        "app_id" to s.appId,
                        "user_auth_token" to s.userAuthToken,
                        "query" to query,
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                    ))
                )
            )
            res.tracks ?: TrackPage()
        }

    suspend fun searchArtists(query: String, limit: Int = 50, offset: Int = 0): ArtistPage =
        withContext(Dispatchers.IO) {
            val s = requireSession()
            val res = parse<ArtistSearchResult>(
                getJson(
                    paramUrl("artist/search", linkedMapOf(
                        "app_id" to s.appId,
                        "user_auth_token" to s.userAuthToken,
                        "query" to query,
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                    ))
                )
            )
            res.artists ?: ArtistPage()
        }

    suspend fun searchPlaylists(query: String, limit: Int = 50, offset: Int = 0): PlaylistPage =
        withContext(Dispatchers.IO) {
            val s = requireSession()
            val res = parse<PlaylistSearchResult>(
                getJson(
                    paramUrl("playlist/search", linkedMapOf(
                        "app_id" to s.appId,
                        "user_auth_token" to s.userAuthToken,
                        "query" to query,
                        "limit" to limit.toString(),
                        "offset" to offset.toString(),
                    ))
                )
            )
            res.playlists ?: PlaylistPage()
        }

    // ------------------------------------------------------------ stream URL

    /**
     * Resolves a signed CDN URL for a track.
     *
     * Signature recipe taken verbatim from QopenAPI.TrackGetFileUrl:
     *   md5("trackgetFileUrlformat_id{format_id}intentstreamtrack_id{track_id}{ts}{app_secret}")
     * The parameter order in the query string must not be altered.
     */
    suspend fun getFileUrl(trackId: String, formatId: String): FileUrlResponse =
        withContext(Dispatchers.IO) {
            val s = requireSession()
            val ts = (System.currentTimeMillis() / 1000L).toString()
            val signatureKey =
                "trackgetFileUrlformat_id" + formatId +
                    "intentstreamtrack_id" + trackId +
                    ts + s.appSecret
            val sig = md5Hex(signatureKey)

            val params = linkedMapOf(
                "request_ts" to ts,
                "request_sig" to sig,
                "track_id" to trackId,
                "format_id" to formatId,
                "intent" to "stream",
                "app_id" to s.appId,
                "user_auth_token" to s.userAuthToken,
            )
            val body = getJson(paramUrl("track/getFileUrl", params))
            val parsed = parse<FileUrlResponse>(body)
            if (parsed.url.isNullOrBlank()) {
                // Qobuz reports unavailable quality/user restrictions in the body.
                val msg = Regex(""""message"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
                throw QobuzApiException.QualityUnavailable(
                    msg ?: "Qobuz did not return a stream URL for this track at format_id=$formatId."
                )
            }
            parsed
        }

    // --------------------------------------------------------------- plumbing

    private fun publicAppId(): String =
        session?.appId ?: throw QobuzApiException.Auth("No app_id available yet.")

    private fun paramUrl(endpoint: String, params: Map<String, String>): String =
        BASE_URL + endpoint + "?" + params.entries.joinToString("&") { "${it.key}=${it.value}" }

    private fun formParamUrl(endpoint: String, params: Map<String, String>): String =
        BASE_URL + endpoint + "?" + params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }

    private fun getJson(url: String): String = rawGet(url)

    private fun rawGet(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", QobuzCredentials.USER_AGENT)
            .header("Accept", "application/json")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }

        val response = try {
            http.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw QobuzApiException.Network("Network error calling Qobuz: ${e.message}", e)
        }

        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val apiMsg = Regex(""""message"\s*:\s*"([^"]*)"""").find(body)?.groupValues?.get(1)
                throw QobuzApiException.Http(
                    resp.code,
                    when (resp.code) {
                        401 -> "Not authorised (401). Your session may have expired — sign in again."
                        403 -> "Access denied (403). ${apiMsg ?: "Your subscription may not allow this."}"
                        404 -> "Not found (404). ${apiMsg ?: ""}".trim()
                        429 -> "Rate limited by Qobuz (429). Wait a moment and retry."
                        else -> "Qobuz returned HTTP ${resp.code}. ${apiMsg ?: ""}".trim()
                    },
                )
            }
            return body
        }
    }

    private inline fun <reified T> parse(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: Exception) {
        throw QobuzApiException.Parsing(
            "Could not parse the Qobuz response (${e.javaClass.simpleName}: ${e.message}).",
            e,
        )
    }

    private fun checkApiError(isError: Boolean, detail: String) {
        if (isError) {
            val msg = Regex(""""message"\s*:\s*"([^"]*)"""").find(detail)?.groupValues?.get(1)
            throw QobuzApiException.Api("error", msg ?: "Unexpected empty response from Qobuz.")
        }
    }

    companion object {
        const val BASE_URL = "https://www.qobuz.com/api.json/0.2/"
        private const val MAX_OFFSET = 100_000
        private const val TAG = "QbdlxApi"

        fun md5Hex(input: String): String {
            val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) sb.append("%02x".format(b))
            return sb.toString()
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
