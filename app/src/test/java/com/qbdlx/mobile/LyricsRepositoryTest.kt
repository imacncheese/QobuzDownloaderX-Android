package com.qbdlx.mobile

import com.qbdlx.mobile.lyrics.LyricsRepository
import com.qbdlx.mobile.lyrics.LyricsResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The lyrics client against a real HTTP stack.
 *
 * Stubbing the repository would prove nothing here: the risk is in the request
 * shape, the status handling and the JSON, so a mock server stands in for the
 * service and the client runs unchanged.
 */
class LyricsRepositoryTest {

    private lateinit var server: MockWebServer

    private fun repository() = LyricsRepository(
        http = LyricsRepository.defaultHttpClient(),
        userAgent = "test-agent",
        baseUrl = server.url("/").toString().trimEnd('/'),
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    @Test
    fun `an exact hit returns the parsed lyrics`() = runBlocking {
        server.enqueue(
            json(
                """
                {"id":1,"trackName":"So What","artistName":"Miles Davis",
                 "albumName":"Kind of Blue","duration":545.0,"instrumental":false,
                 "plainLyrics":"a\nb","syncedLyrics":"[00:01.00]a\n[00:05.50]b"}
                """.trimIndent()
            )
        )

        val result = repository().lyricsFor("t1", "Miles Davis", "So What", "Kind of Blue", 545)

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(2, lyrics.synced.size)
        assertEquals(1_000L, lyrics.synced[0].timeMs)
        assertEquals(5_500L, lyrics.synced[1].timeMs)
        assertEquals("So What", server.takeRequest().requestUrl!!.queryParameter("track_name"))
    }

    @Test
    fun `the query carries the artist album and duration`() = runBlocking {
        server.enqueue(json("""{"instrumental":false,"syncedLyrics":"[00:01.00]x"}"""))

        repository().lyricsFor("t1", "Miles Davis", "So What", "Kind of Blue", 545)

        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/get", url.encodedPath)
        assertEquals("Miles Davis", url.queryParameter("artist_name"))
        assertEquals("Kind of Blue", url.queryParameter("album_name"))
        assertEquals("545", url.queryParameter("duration"))
    }

    @Test
    fun `a request identifies the app`() = runBlocking {
        server.enqueue(json("""{"instrumental":false,"plainLyrics":"x"}"""))

        repository().lyricsFor("t1", "a", "b")

        assertEquals("test-agent", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `a miss on the exact endpoint falls back to search`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            json(
                """
                [{"id":9,"duration":240.0,"instrumental":false,
                  "syncedLyrics":"[00:02.00]found by search"}]
                """.trimIndent()
            )
        )

        val result = repository().lyricsFor("t1", "Someone", "Something", "Some Album", 240)

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(2_000L, lyrics.synced.single().timeMs)
        assertEquals("/api/get", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("/api/search", server.takeRequest().requestUrl!!.encodedPath)
    }

    @Test
    fun `an instrumental track is reported as instrumental`() = runBlocking {
        server.enqueue(json("""{"id":3,"instrumental":true,"duration":120.0}"""))

        val result = repository().lyricsFor("t1", "a", "b", null, 120)

        assertEquals(LyricsResult.Instrumental, result)
    }

    @Test
    fun `rate limiting is reported rather than swallowed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = repository().lyricsFor("t1", "a", "b")

        assertTrue("expected an Error, got $result", result is LyricsResult.Error)
        assertTrue(
            "the message should be readable: ${(result as LyricsResult.Error).message}",
            result.message.contains("rate"),
        )
    }

    @Test
    fun `a server fault is reported and does not throw`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = repository().lyricsFor("t1", "a", "b")

        assertTrue("expected an Error, got $result", result is LyricsResult.Error)
    }

    @Test
    fun `malformed json on the exact endpoint falls through to search`() = runBlocking {
        server.enqueue(json("not json at all"))
        server.enqueue(
            json(
                """
                [{"id":2,"duration":240.0,"instrumental":false,
                  "syncedLyrics":"[00:03.00]from search"}]
                """.trimIndent()
            )
        )

        val result = repository().lyricsFor("t1", "a", "b", null, 240)

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(3_000L, lyrics.synced.single().timeMs)
    }

    @Test
    fun `malformed json everywhere is a clean miss rather than a crash`() = runBlocking {
        server.enqueue(json("not json"))
        server.enqueue(json("also not json"))

        assertEquals(LyricsResult.NotFound, repository().lyricsFor("t1", "a", "b"))
    }

    @Test
    fun `a second lookup for the same track does not hit the network`() = runBlocking {
        server.enqueue(json("""{"instrumental":false,"syncedLyrics":"[00:01.00]x"}"""))
        val repo = repository()

        repo.lyricsFor("t1", "a", "b")
        val again = repo.lyricsFor("t1", "a", "b")

        assertNotNull(repo.cached("t1"))
        assertTrue("expected a cached hit, got $again", again is LyricsResult.Found)
        assertEquals("only one request should have been made", 1, server.requestCount)
    }

    @Test
    fun `a negative result is cached too`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(json("[]"))
        val repo = repository()

        repo.lyricsFor("t1", "a", "b")
        repo.lyricsFor("t1", "a", "b")

        assertEquals("a known miss must not be re-asked", 2, server.requestCount)
    }

    @Test
    fun `a blank title does not reach the network`() = runBlocking {
        val result = repository().lyricsFor("t1", "a", "")

        assertEquals(LyricsResult.NotFound, result)
        assertEquals(0, server.requestCount)
    }
}
