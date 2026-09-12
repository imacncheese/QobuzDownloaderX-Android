package com.qbdlx.mobile.lyrics

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * One record from LRCLIB.
 *
 * Every field is optional because the service returns partial records for
 * tracks it only half knows: a hit may carry a duration and an album but no
 * lyrics at all.
 */
@Serializable
internal data class LrclibRecord(
    val id: Long = 0,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * Synchronised lyrics from LRCLIB.
 *
 * Qobuz exposes no lyrics through its own API, so this goes to
 * [lrclib.net](https://lrclib.net), a free service with no authentication that
 * returns both plain and LRC-timed lyrics. The lookup is best effort: it never
 * throws, and a failure must not affect a download or playback.
 *
 * Results are cached for the session, including negative results, so skipping
 * through a queue does not re-ask for the same track.
 */
class LyricsRepository(
    private val http: OkHttpClient = defaultHttpClient(),
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val baseUrl: String = BASE_URL,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** Keyed by track id. A stored [LyricsResult.NotFound] is a real answer. */
    private val cache = ConcurrentHashMap<String, LyricsResult>()

    /** The last answer for a track, without touching the network. */
    fun cached(trackId: String): LyricsResult? = cache[trackId]

    fun forget(trackId: String) {
        cache.remove(trackId)
    }

    /**
     * Looks up lyrics for one track.
     *
     * @param durationSeconds the track length, used to reject a same-titled
     *   song by a different artist or a live version of the wrong length. Pass 0
     *   when unknown.
     */
    suspend fun lyricsFor(
        trackId: String,
        artist: String,
        title: String,
        album: String? = null,
        durationSeconds: Int = 0,
    ): LyricsResult {
        cache[trackId]?.let { return it }
        if (title.isBlank()) return LyricsResult.NotFound

        val result = withContext(Dispatchers.IO) { lookup(artist, title, album, durationSeconds) }
        cache[trackId] = result
        return result
    }

    private fun lookup(
        artist: String,
        title: String,
        album: String?,
        durationSeconds: Int,
    ): LyricsResult {
        // The exact-match endpoint is authoritative: it only answers when the
        // names and the duration line up, so a hit needs no second-guessing.
        val exact = runCatching {
            get(
                "/api/get?" + query(
                    "artist_name" to artist,
                    "track_name" to title,
                    "album_name" to album.orEmpty(),
                    "duration" to durationSeconds.takeIf { it > 0 }?.toString().orEmpty(),
                )
            )
        }.getOrElse { return errorFor(it) }

        if (exact != null) {
            val record = runCatching { json.decodeFromString<LrclibRecord>(exact) }.getOrNull()
            if (record != null) return toResult(record)
        }

        // Nothing exact, so fall back to a search and pick the best candidate.
        val searched = runCatching {
            get("/api/search?" + query("artist_name" to artist, "track_name" to title))
        }.getOrElse { return errorFor(it) }

        val candidates = searched
            ?.let { runCatching { json.decodeFromString<List<LrclibRecord>>(it) }.getOrNull() }
            .orEmpty()

        val best = chooseBest(candidates, durationSeconds)
        Log.i(TAG, "search '$artist - $title' -> ${candidates.size} candidates, best=${best?.id}")
        return best?.let { toResult(it) } ?: LyricsResult.NotFound
    }

    /**
     * Returns null when the endpoint has no exact match, which is a 404 rather
     * than a failure.
     */
    private fun get(pathAndQuery: String): String? {
        val request = Request.Builder()
            .url(baseUrl + pathAndQuery)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw LyricsHttpException(response.code, "LRCLIB returned HTTP ${response.code}")
            }
            return response.body?.string()
        }
    }

    private fun errorFor(e: Throwable): LyricsResult {
        val message = when {
            e is LyricsHttpException && e.code == 429 ->
                "Lyrics service is rate limiting, try again in a minute"
            e is LyricsHttpException -> "Lyrics service returned HTTP ${e.code}"
            else -> "Could not reach the lyrics service"
        }
        Log.w(TAG, "lyrics lookup failed: ${e.message}")
        return LyricsResult.Error(message)
    }

    companion object {
        private const val TAG = "QbdlxLyrics"
        private const val BASE_URL = "https://lrclib.net"

        /**
         * LRCLIB asks callers to identify themselves. A generic agent string
         * gets blocked, so the app name and repository are included.
         */
        const val DEFAULT_USER_AGENT =
            "QobuzDLX-Android (https://github.com/imacncheese/QobuzDownloaderX-Android)"

        /**
         * How far a candidate's length may differ before it is treated as a
         * different recording. Live and remastered versions of the same song
         * routinely differ by a few seconds, but a same-titled track by another
         * artist is usually far off.
         */
        const val DURATION_TOLERANCE_SECONDS = 5.0

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        private fun query(vararg pairs: Pair<String, String>): String =
            pairs.filter { it.second.isNotBlank() }
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        /**
         * Picks the best candidate from a search result.
         *
         * A candidate whose length is known and is not within
         * [DURATION_TOLERANCE_SECONDS] of ours is rejected outright, even when
         * it is the only one. The search was already scoped by artist and
         * title, so a large length difference means a live take, an extended
         * mix or a different song that shares a name, and lyrics for the wrong
         * recording are worse than none at all. A candidate with no stored
         * duration is kept, because there is nothing to judge it by.
         *
         * Among the survivors, timed lyrics beat a plain block, because a
         * second of length difference is not worth losing the sync over.
         */
        internal fun chooseBest(
            candidates: List<LrclibRecord>,
            durationSeconds: Int,
        ): LrclibRecord? {
            val usable = candidates.filter {
                it.instrumental || !it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank()
            }
            if (usable.isEmpty()) return null

            val known = durationSeconds > 0
            val inRange = if (!known) {
                usable
            } else {
                usable.filter {
                    it.duration == null || abs(it.duration - durationSeconds) <= DURATION_TOLERANCE_SECONDS
                }
            }
            if (inRange.isEmpty()) return null

            return inRange.sortedWith(
                compareBy(
                    { if (it.syncedLyrics.isNullOrBlank()) 1 else 0 },
                    {
                        if (known && it.duration != null) abs(it.duration - durationSeconds) else 0.0
                    },
                )
            ).first()
        }

        internal fun toResult(record: LrclibRecord): LyricsResult {
            if (record.instrumental) return LyricsResult.Instrumental

            val synced = Lrc.parse(record.syncedLyrics.orEmpty())

            // Parsing the synced text as a plain block would be wrong: its time
            // stamps are not metadata tags, so they would survive into the text.
            // When there is no plain block, Lyrics.plainText rebuilds it from
            // the parsed lines instead.
            val plain = Lrc.parsePlain(record.plainLyrics.orEmpty())

            val lyrics = Lyrics(
                synced = synced,
                plain = plain?.takeIf { it.isNotBlank() && it != synced.joinToString("\n") { l -> l.text } },
            )
            return if (lyrics.isEmpty) LyricsResult.NotFound else LyricsResult.Found(lyrics)
        }
    }
}

/** A non-404 HTTP failure from the lyrics service. */
class LyricsHttpException(val code: Int, message: String) : Exception(message)
