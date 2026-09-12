package com.qbdlx.mobile

import androidx.media3.common.Player
import com.qbdlx.mobile.playback.PlaybackState
import com.qbdlx.mobile.playback.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue and transport state the UI reads derives a few values; these pin the
 * edges that are easy to get wrong (end of queue, empty queue, unknown duration).
 */
class PlaybackStateTest {

    private fun item(id: String, title: String = "Track $id") = QueueItem(
        trackId = id,
        title = title,
        artist = "Artist $id",
        albumTitle = "Album",
        artworkUrl = null,
        durationSeconds = 200,
        track = null,
    )

    private val queue = listOf(item("a"), item("b"), item("c"))

    private fun state(
        index: Int = 0,
        queue: List<QueueItem> = this.queue,
        playing: Boolean = false,
        position: Long = 0L,
        duration: Long = 200_000L,
        repeatMode: Int = Player.REPEAT_MODE_OFF,
    ) = PlaybackState(
        current = queue.getOrNull(index),
        isPlaying = playing,
        positionMs = position,
        durationMs = duration,
        queueIndex = index,
        queueSize = queue.size,
        queue = queue,
        repeatMode = repeatMode,
    )

    // -------------------------------------------------------------- up next

    @Test
    fun `up next is the following track`() {
        assertEquals("b", state(index = 0).upNext?.trackId)
        assertEquals("c", state(index = 1).upNext?.trackId)
    }

    @Test
    fun `up next is null at the end of the queue`() {
        assertNull("nothing follows the last track", state(index = 2).upNext)
    }

    @Test
    fun `up next is null for an empty queue`() {
        assertNull(state(index = 0, queue = emptyList()).upNext)
    }

    @Test
    fun `up next is null when the index is past the end`() {
        // Defensive: a stale index can outlive a shortened queue.
        assertNull(state(index = 9).upNext)
    }

    // -------------------------------------------------------------- progress

    @Test
    fun `progress is the position over the duration`() {
        assertEquals(0.5f, state(position = 100_000L).progress, 0.001f)
        assertEquals(0f, state(position = 0L).progress, 0.001f)
        assertEquals(1f, state(position = 200_000L).progress, 0.001f)
    }

    @Test
    fun `unknown duration reports zero progress rather than dividing by zero`() {
        assertEquals(0f, state(duration = 0L, position = 50_000L).progress, 0.001f)
    }

    @Test
    fun `progress is clamped when position overshoots duration`() {
        // ExoPlayer can briefly report a position past a stale duration.
        assertEquals(1f, state(position = 500_000L).progress, 0.001f)
    }

    // ---------------------------------------------------------- repeat label

    @Test
    fun `repeat label matches the mode`() {
        assertEquals("Repeat off", state(repeatMode = Player.REPEAT_MODE_OFF).repeatLabel)
        assertEquals("Repeat all", state(repeatMode = Player.REPEAT_MODE_ALL).repeatLabel)
        assertEquals("Repeat one", state(repeatMode = Player.REPEAT_MODE_ONE).repeatLabel)
    }

    // ------------------------------------------------------------ has item

    @Test
    fun `has item is false when nothing is loaded`() {
        val empty = PlaybackState()
        assertEquals(false, empty.hasItem)
        assertNull(empty.upNext)
    }

    @Test
    fun `has item is true once a track is current`() {
        assertEquals(true, state().hasItem)
    }
}
