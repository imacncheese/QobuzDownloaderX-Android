package com.qbdlx.mobile.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.lyrics.LyricsResult
import com.qbdlx.mobile.lyrics.LyricsUi
import kotlinx.coroutines.flow.StateFlow

/**
 * Lyrics for the playing track.
 *
 * When the source had timings the lines scroll themselves and the current one
 * is highlighted; tapping a line seeks there, which is what makes synced lyrics
 * worth having rather than just nice to look at. When only a plain block was
 * available it is shown as scrollable text instead of pretending to sync.
 *
 * [positionMs] is passed as a flow and collected down here rather than at the
 * player, so the four-times-a-second tick only invalidates this list. It has to
 * be collected rather than read: reading `.value` inside a composable is
 * invisible to Compose, which is why the highlight used to lag until some
 * unrelated player event forced a recomposition.
 */
@Composable
fun LyricsPanel(
    state: LyricsUi,
    positionMs: StateFlow<Long>,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val lyrics = state.lyrics
        when {
            state.loading -> CircularProgressIndicator()

            // Nothing has been asked for yet: stay blank rather than claim the
            // track has no lyrics before the lookup has finished.
            state.result == null -> Unit

            lyrics != null && lyrics.hasSynced -> SyncedLyrics(
                ui = state,
                positionMs = positionMs,
                onSeek = onSeek,
            )

            lyrics != null -> PlainLyrics(lyrics.plainText)

            else -> Unavailable(state.result, onRetry)
        }
    }
}

@Composable
private fun SyncedLyrics(
    ui: LyricsUi,
    positionMs: StateFlow<Long>,
    onSeek: (Long) -> Unit,
) {
    val lyrics = ui.lyrics ?: return
    val listState = rememberLazyListState()
    val position by positionMs.collectAsStateWithLifecycle()
    val active = lyrics.indexAt(position)

    // The active line sits a third of the way down rather than at the top edge,
    // so the line that is about to arrive is already on screen. Before the
    // first line there is nothing to follow, and the top of the lyrics is the
    // only sensible place to be: without this, seeking back past the first
    // timestamp left the list stranded wherever it had scrolled to.
    LaunchedEffect(ui.trackId, active) {
        if (active >= 0) {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollToItem(active, scrollOffset = -(viewport / 3))
        } else {
            listState.animateScrollToItem(0)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 56.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lyrics.synced) { index, line ->
            val isActive = index == active
            val colour by animateColorAsState(
                targetValue = if (isActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                label = "lyricColour",
            )
            Text(
                text = line.text.ifBlank { " " },
                style = if (isActive) {
                    MaterialTheme.typography.titleMedium
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = colour,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = line.text.isNotBlank()) { onSeek(line.timeMs) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun PlainLyrics(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 56.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Unavailable(result: LyricsResult?, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when (result) {
                is LyricsResult.Instrumental -> "This track is instrumental"
                is LyricsResult.Error -> result.message
                // Naming the source matters. An empty result is not a broken
                // feature: it is a community database that does not have this
                // particular track, and saying so stops it reading as one.
                else -> "No lyrics for this track on LRCLIB"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // A genuine miss is not worth retrying; a service problem is.
        if (result is LyricsResult.Error) {
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onRetry) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Try again")
                }
            }
        }
    }
}
