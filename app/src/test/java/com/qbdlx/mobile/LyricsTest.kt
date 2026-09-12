package com.qbdlx.mobile

import com.qbdlx.mobile.lyrics.Lrc
import com.qbdlx.mobile.lyrics.LrclibRecord
import com.qbdlx.mobile.lyrics.LyricLine
import com.qbdlx.mobile.lyrics.Lyrics
import com.qbdlx.mobile.lyrics.LyricsRepository
import com.qbdlx.mobile.lyrics.LyricsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lyrics parsing and matching.
 *
 * Both are pure functions over text, which is where the real risk lives: lyric
 * files come from a free community database and are malformed in every way you
 * can imagine, and a mis-timed or wrong-song match is worse than no lyrics.
 */
class LyricsTest {

    // ------------------------------------------------------------------ LRC

    @Test
    fun `plain timestamps are milliseconds and minutes`() {
        val lines = Lrc.parse("[01:05.50]one\n[02:00.00]two")

        assertEquals(2, lines.size)
        assertEquals(65_500L, lines[0].timeMs)
        assertEquals("one", lines[0].text)
        assertEquals(120_000L, lines[1].timeMs)
    }

    @Test
    fun `one two and three digit fractions are all hundredths based`() {
        // "22" means 220 ms and "5" means 500 ms. Reading them as raw
        // milliseconds silently desyncs every lyric by up to a second.
        assertEquals(220L, Lrc.parse("[00:00.22]x").single().timeMs)
        assertEquals(500L, Lrc.parse("[00:00.5]x").single().timeMs)
        assertEquals(234L, Lrc.parse("[00:00.234]x").single().timeMs)
        assertEquals(0L, Lrc.parse("[00:00]x").single().timeMs)
    }

    @Test
    fun `a colon may separate the fraction`() {
        assertEquals(1_230L, Lrc.parse("[00:01:23]x").single().timeMs)
    }

    @Test
    fun `one line may carry several stamps`() {
        val lines = Lrc.parse("[00:10.00][01:20.00]chorus")

        assertEquals(2, lines.size)
        assertEquals(10_000L, lines[0].timeMs)
        assertEquals(80_000L, lines[1].timeMs)
        assertEquals("chorus", lines[0].text)
        assertEquals("chorus", lines[1].text)
    }

    @Test
    fun `metadata tags are not turned into lyric lines`() {
        val lines = Lrc.parse("[ar:Radiohead]\n[ti:Kid A]\n[00:12.00]real line")

        assertEquals(1, lines.size)
        assertEquals("real line", lines[0].text)
    }

    @Test
    fun `offset shifts every stamp`() {
        // A positive offset pulls the lyrics earlier.
        assertEquals(9_500L, Lrc.parse("[offset:+500]\n[00:10.00]x").single().timeMs)
        assertEquals(10_500L, Lrc.parse("[offset:-500]\n[00:10.00]x").single().timeMs)
    }

    @Test
    fun `a stamp shifted before zero is clamped`() {
        assertEquals(0L, Lrc.parse("[offset:+5000]\n[00:01.00]x").single().timeMs)
    }

    @Test
    fun `blank separator lines are kept`() {
        val lines = Lrc.parse("[00:01.00]a\n[00:02.00]\n[00:03.00]b")

        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `windows line endings do not leak into the text`() {
        val lines = Lrc.parse("[00:01.00]a\r\n[00:02.00]b\r\n")

        assertEquals(2, lines.size)
        assertEquals("a", lines[0].text)
        assertEquals("b", lines[1].text)
    }

    @Test
    fun `out of order stamps are sorted`() {
        val lines = Lrc.parse("[00:30.00]second\n[00:10.00]first")

        assertEquals("first", lines[0].text)
        assertEquals("second", lines[1].text)
    }

    @Test
    fun `text with no timestamps is not synced`() {
        assertTrue(Lrc.parse("just some words\nand more").isEmpty())
    }

    @Test
    fun `brackets that are not time stamps survive as lyrics`() {
        val lines = Lrc.parse("[00:01.00][Chorus] sing it")

        assertEquals(1, lines.size)
        assertEquals("[Chorus] sing it", lines[0].text)
    }

    @Test
    fun `plain parsing strips lrc headers but keeps verse markers`() {
        val plain = Lrc.parsePlain("[ar:Someone]\n[Chorus]\nhello")

        assertNotNull(plain)
        assertTrue("verse marker must survive: $plain", plain!!.contains("[Chorus]"))
        assertTrue("header must be gone: $plain", !plain.contains("ar:"))
        assertEquals("hello", plain.lines().last())
    }

    // -------------------------------------------------------------- matching

    @Test
    fun `the highlighted line follows the position`() {
        val lyrics = Lyrics(
            synced = listOf(
                LyricLine(10_000, "one"),
                LyricLine(20_000, "two"),
                LyricLine(30_000, "three"),
            )
        )

        assertEquals(-1, lyrics.indexAt(0))
        assertEquals(-1, lyrics.indexAt(9_999))
        assertEquals(0, lyrics.indexAt(10_000))
        assertEquals(0, lyrics.indexAt(19_999))
        assertEquals(1, lyrics.indexAt(20_000))
        assertEquals(2, lyrics.indexAt(999_999))
    }

    @Test
    fun `lrc is regenerated in the format players expect`() {
        val lyrics = Lyrics(synced = listOf(LyricLine(65_500, "hello"), LyricLine(120_000, "")))

        assertEquals("[01:05.50]hello\n[02:00.00]", lyrics.toLrc())
    }

    @Test
    fun `regenerated lrc parses back to the same lines`() {
        val original = Lyrics(
            synced = listOf(LyricLine(1_230, "a"), LyricLine(65_500, "b"), LyricLine(120_000, "c"))
        )

        assertEquals(original.synced, Lrc.parse(original.toLrc()))
    }

    @Test
    fun `a plain only result still yields embeddable text`() {
        val lyrics = Lyrics(plain = "just words")

        assertFalse(lyrics.hasSynced)
        assertEquals("just words", lyrics.plainText)
    }

    @Test
    fun `a synced only result yields plain text rebuilt from the lines`() {
        val lyrics = Lyrics(synced = listOf(LyricLine(0, "a"), LyricLine(1000, "b")))

        assertEquals("a\nb", lyrics.plainText)
    }

    // ------------------------------------------------------------ selection

    private fun record(
        id: Long,
        synced: String? = null,
        plain: String? = null,
        duration: Double? = null,
        instrumental: Boolean = false,
    ) = LrclibRecord(
        id = id,
        trackName = "t",
        artistName = "a",
        duration = duration,
        instrumental = instrumental,
        plainLyrics = plain,
        syncedLyrics = synced,
    )

    @Test
    fun `a timed candidate beats a plain one at the same length`() {
        val chosen = LyricsRepository.chooseBest(
            listOf(
                record(1, plain = "no timing", duration = 240.0),
                record(2, synced = "[00:01.00]timed", duration = 240.0),
            ),
            durationSeconds = 240,
        )

        assertEquals(2L, chosen?.id)
    }

    @Test
    fun `a candidate far from the requested length is rejected`() {
        val chosen = LyricsRepository.chooseBest(
            listOf(record(1, synced = "[00:01.00]x", duration = 400.0)),
            durationSeconds = 240,
        )

        assertNull(chosen)
    }

    @Test
    fun `a candidate with no stored length is still considered`() {
        val chosen = LyricsRepository.chooseBest(
            listOf(record(1, synced = "[00:01.00]x", duration = null)),
            durationSeconds = 240,
        )

        assertEquals(1L, chosen?.id)
    }

    @Test
    fun `the closest length wins among timed candidates`() {
        val chosen = LyricsRepository.chooseBest(
            listOf(
                record(1, synced = "[00:01.00]x", duration = 244.0),
                record(2, synced = "[00:01.00]x", duration = 240.0),
            ),
            durationSeconds = 240,
        )

        assertEquals(2L, chosen?.id)
    }

    @Test
    fun `candidates with no lyrics at all are ignored`() {
        assertNull(
            LyricsRepository.chooseBest(
                listOf(record(1, duration = 240.0), record(2, duration = 241.0)),
                durationSeconds = 240,
            )
        )
    }

    @Test
    fun `an unknown length does not disqualify every candidate`() {
        val chosen = LyricsRepository.chooseBest(
            listOf(record(1, synced = "[00:01.00]x", duration = 900.0)),
            durationSeconds = 0,
        )

        assertEquals(1L, chosen?.id)
    }

    // --------------------------------------------------------------- results

    @Test
    fun `an instrumental record is reported as instrumental`() {
        val result = LyricsRepository.toResult(record(1, instrumental = true))

        assertEquals(LyricsResult.Instrumental, result)
    }

    @Test
    fun `a synced record keeps both blocks`() {
        val result = LyricsRepository.toResult(
            record(1, synced = "[00:01.00]a\n[00:02.00]b", plain = "a\nb")
        )

        val lyrics = (result as LyricsResult.Found).lyrics
        assertEquals(2, lyrics.synced.size)
        // The plain block duplicates the synced text, so it is dropped rather
        // than stored twice.
        assertNull(lyrics.plain)
        assertEquals("a\nb", lyrics.plainText)
    }

    @Test
    fun `a record with nothing usable is not found rather than empty`() {
        assertEquals(LyricsResult.NotFound, LyricsRepository.toResult(record(1)))
        assertEquals(LyricsResult.NotFound, LyricsRepository.toResult(record(2, plain = "   ")))
    }
}
