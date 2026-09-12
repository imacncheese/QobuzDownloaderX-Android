package com.qbdlx.mobile

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.ui.screens.TrackRow
import com.qbdlx.mobile.ui.theme.QobuzDlxTheme
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Tapping a track in the search results has to play it.
 *
 * Regression: the row had a nested clickable over the title and subtitle that
 * opened the album. It covered most of the row, so the row's own play handler
 * almost never fired and search results looked unplayable. Nothing below the UI
 * can catch that, which is the whole reason this test exists.
 */
@RunWith(AndroidJUnit4::class)
class TrackRowTapAndroidTest {

    @get:Rule
    val compose = createComposeRule()

    private val track = Track(
        id = JsonPrimitive("t1"),
        title = "So What",
        track_number = 1,
        album = com.qbdlx.mobile.api.Album(
            id = JsonPrimitive("a1"),
            title = "Kind of Blue",
        ),
    )

    @Test
    fun tappingTheTitlePlaysTheTrack() {
        var played = false
        var downloaded = false

        compose.setContent {
            QobuzDlxTheme {
                TrackRow(
                    track = track,
                    onPlay = { played = true },
                    onDownload = { downloaded = true },
                )
            }
        }

        compose.onNodeWithText("So What").performClick()
        compose.waitForIdle()

        assertTrue("tapping the title must play the track", played)
        assertFalse("tapping the title must not download", downloaded)
    }

    @Test
    fun tappingTheSubtitlePlaysTheTrack() {
        var played = false

        compose.setContent {
            QobuzDlxTheme {
                TrackRow(track = track, onPlay = { played = true }, onDownload = {})
            }
        }

        // The subtitle carries the artist and the album name, which is where the
        // album-opening hotspot used to be.
        compose.onNodeWithText("Kind of Blue", substring = true).performClick()
        compose.waitForIdle()

        assertTrue("tapping the subtitle must play the track", played)
    }
}
