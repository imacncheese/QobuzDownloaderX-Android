package com.qbdlx.mobile

import com.qbdlx.mobile.lyrics.LyricsRepository
import com.qbdlx.mobile.lyrics.LyricsResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lyrics client against a real HTTP stack.
 *
 * Stubbing the repository would prove nothing here: the risk is in the request
 * shape, the status handling and the JSON, so a mock server stands in for the
 * service and the client runs unchanged.
 *
 * Responses are chosen by path rather than queued in order. The client makes a
 * variable number of calls per lookup now (exact, search, a looser search, then
 * a fallback source), and an ordered queue made every test depend on that count.
 */
class LyricsRepositoryTest {

    private lateinit var server: MockWebServer

    /** Answers a request by path. Defaults to "service is unwell". */
    private var handler: (RecordedRequest) -> MockResponse = {
        MockResponse().setResponseCode(500)
    }

    private fun repository() = LyricsRepository(
        http = LyricsRepository.defaultHttpClient(),
        userAgent = "test-agent",
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = handler(request)
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun path(request: RecordedRequest) = request.requestUrl!!.encodedPath
    private fun query(request: RecordedRequest, name: String) =
        request.requestUrl!!.queryParameter(name)

    /** Everything answers "no such track", including the fallback source. */
    private fun nothingAnywhere() {
        handler = { request ->
            when {
                path(request) == "/api/get" -> MockResponse().setResponseCode(404)
                path(request).startsWith("/api/search") -> json("[]")
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private val syncedRecord = """
        {"id":1,"trackName":"So What","artistName":"Miles Davis",
         "albumName":"Kind of Blue","duration":545.0,"instrumental":false,
         "plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a\n[00:05.50]b"}
    """.trimIndent()

    @Test
    fun `an exact hit returns the parsed lyrics`() = runBlocking {
        handler = { json(syncedRecord) }

        val result = repository().lyricsFor("t1", "Miles Davis", "So What", "Kind of Blue", 545)

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(2, lyrics.synced.size)
        assertEquals(1_000L, lyrics.synced[0].timeMs)
        assertEquals(5_500L, lyrics.synced[1].timeMs)
    }

    @Test
    fun `the query carries the artist album and duration`() = runBlocking {
        var seen: RecordedRequest? = null
        handler = { request -> seen = request; json(syncedRecord) }

        repository().lyricsFor("t1", "Miles Davis", "So What", "Kind of Blue", 545)

        val request = seen!!
        assertEquals("/api/get", path(request))
        assertEquals("Miles Davis", query(request, "artist_name"))
        assertEquals("Kind of Blue", query(request, "album_name"))
        assertEquals("545", query(request, "duration"))
    }

    @Test
    fun `a request identifies the app`() = runBlocking {
        var agent: String? = null
        handler = { request ->
            agent = request.getHeader("User-Agent")
            json("""{"instrumental":false,"plainLyrics":"x"}""")
        }

        repository().lyricsFor("t1", "a", "b")

        assertEquals("test-agent", agent)
    }

    @Test
    fun `a miss on the exact endpoint falls back to search`() = runBlocking {
        handler = { request ->
            if (path(request) == "/api/get") {
                MockResponse().setResponseCode(404)
            } else {
                json("""[{"id":9,"duration":240.0,"instrumental":false,
                          "syncedLyrics":"[00:02.00]found by search"}]""")
            }
        }

        val result = repository().lyricsFor("t1", "Someone", "Something", "Some Album", 240)

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(2_000L, lyrics.synced.single().timeMs)
    }

    @Test
    fun `an instrumental track is reported as instrumental`() = runBlocking {
        handler = { json("""{"id":3,"instrumental":true,"duration":120.0}""") }

        val result = repository().lyricsFor("t1", "a", "b", null, 120)

        assertEquals(LyricsResult.Instrumental, result)
    }

    @Test
    fun `rate limiting is reported rather than swallowed`() = runBlocking {
        handler = { MockResponse().setResponseCode(429) }

        val result = repository().lyricsFor("t1", "a", "b")

        assertTrue("expected an Error, got $result", result is LyricsResult.Error)
        assertTrue(
            "the message should be readable: ${(result as LyricsResult.Error).message}",
            result.message.contains("rate"),
        )
    }

    @Test
    fun `a server fault is reported and does not throw`() = runBlocking {
        handler = { MockResponse().setResponseCode(503) }

        val result = repository().lyricsFor("t1", "a", "b")

        assertTrue("expected an Error, got $result", result is LyricsResult.Error)
    }

    @Test
    fun `a server fault is retried once before giving up`() = runBlocking {
        var attempts = 0
        handler = { request ->
            if (path(request) == "/api/get") {
                attempts++
                if (attempts == 1) MockResponse().setResponseCode(503) else json(syncedRecord)
            } else {
                json("[]")
            }
        }

        val result = repository().lyricsFor("t1", "Miles Davis", "So What", "Kind of Blue", 545)

        assertTrue("the retry should have produced a hit, got $result", result is LyricsResult.Found)
        assertEquals("one failure then one success", 2, attempts)
    }

    /**
     * Regression: a blip on the exact endpoint used to abort the lookup outright,
     * so a 503 on one endpoint meant "no lyrics" even though the search endpoint
     * was perfectly healthy.
     */
    @Test
    fun `a failure on the exact endpoint still tries the search`() = runBlocking {
        handler = { request ->
            when {
                path(request) == "/api/get" -> MockResponse().setResponseCode(500)
                path(request).startsWith("/api/search") ->
                    json("""[{"id":4,"duration":240.0,"instrumental":false,
                              "syncedLyrics":"[00:03.00]from search"}]""")
                else -> MockResponse().setResponseCode(404)
            }
        }

        val result = repository().lyricsFor("t1", "a", "b", null, 240)

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(3_000L, lyrics.synced.single().timeMs)
    }

    @Test
    fun `malformed json everywhere is a clean miss rather than a crash`() = runBlocking {
        handler = { request ->
            if (path(request) == "/api/get") json("not json") else json("also not json")
        }

        assertEquals(LyricsResult.NotFound, repository().lyricsFor("t1", "a", "b"))
    }

    @Test
    fun `a second lookup for the same track does not hit the network`() = runBlocking {
        handler = { json(syncedRecord) }
        val repo = repository()

        repo.lyricsFor("t1", "a", "b")
        val again = repo.lyricsFor("t1", "a", "b")

        assertNotNull(repo.cached("t1"))
        assertTrue("expected a cached hit, got $again", again is LyricsResult.Found)
        assertEquals("only one request should have been made", 1, server.requestCount)
    }

    /**
     * Regression: failures used to be cached like answers, so one bad moment from
     * the service stuck to that track for the rest of the session.
     */
    @Test
    fun `a failure is not remembered`() = runBlocking {
        handler = { MockResponse().setResponseCode(503) }
        val repo = repository()

        val first = repo.lyricsFor("t1", "a", "b")
        assertTrue("expected an Error, got $first", first is LyricsResult.Error)
        assertNull("a failure must not be cached", repo.cached("t1"))

        // The service recovers, and the same track can now be looked up again.
        handler = { json(syncedRecord) }
        val second = repo.lyricsFor("t1", "Miles Davis", "So What", null, 545)

        assertTrue("the retry should succeed, got $second", second is LyricsResult.Found)
    }

    @Test
    fun `a negative result is cached when every source answers`() = runBlocking {
        nothingAnywhere()
        val repo = repository()

        assertEquals(LyricsResult.NotFound, repo.lyricsFor("t1", "a", "b"))
        val before = server.requestCount
        assertEquals(LyricsResult.NotFound, repo.lyricsFor("t1", "a", "b"))

        assertEquals("a known miss must not be re-asked", before, server.requestCount)
    }

    @Test
    fun `a blank title does not reach the network`() = runBlocking {
        val result = repository().lyricsFor("t1", "a", "")

        assertEquals(LyricsResult.NotFound, result)
        assertEquals(0, server.requestCount)
    }
}
