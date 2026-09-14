package com.qbdlx.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.R
import com.qbdlx.mobile.ui.AppViewModel
import com.qbdlx.mobile.ui.formatDuration
import com.qbdlx.mobile.ui.theme.LocalShapes

/**
 * The play queue.
 *
 * Reordering is done with up/down buttons rather than drag and drop. Long-press
 * dragging fights the scroll for the gesture and needs a fair bit of state to do
 * properly; arrows are unambiguous and work one-handed, which matters more on a
 * phone than matching a desktop interaction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val state by vm.playback.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.queue_title))
                        if (state.queueSize > 0) {
                            Text(
                                text = stringResource(R.string.queue_position, state.queueIndex + 1, state.queueSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (state.queue.isNotEmpty()) {
                        TextButton(onClick = vm::clearUpcomingTracks) {
                            Text(stringResource(R.string.queue_clear_upcoming))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (state.queue.isEmpty()) {
                EmptyPane(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.queue_empty))
                return@Box
            }

            // Keys have to be stable across a reorder for the list to animate a
            // move rather than treat the row as a new one. A track can legitimately
            // appear twice in a queue, so duplicates get a suffix; otherwise the
            // key would clash and the list would throw.
            val keys = remember(state.queue) {
                val seen = HashMap<String, Int>()
                state.queue.map { item ->
                    val n = seen.getOrDefault(item.trackId, 0)
                    seen[item.trackId] = n + 1
                    if (n == 0) item.trackId else "${item.trackId}#$n"
                }
            }

            LazyColumn(
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    state.queue,
                    key = { index, _ -> keys.getOrElse(index) { index.toString() } },
                ) { index, item ->
                    val isCurrent = index == state.queueIndex
                    QueueRow(
                        index = index,
                        title = item.title,
                        artist = item.artist,
                        durationSeconds = item.durationSeconds,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && state.isPlaying,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.queue.lastIndex,
                        onJump = { vm.jumpToQueueIndex(index) },
                        onMoveUp = { vm.moveQueueItem(index, index - 1) },
                        onMoveDown = { vm.moveQueueItem(index, index + 1) },
                        onRemove = { vm.removeQueueItem(index) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    title: String,
    artist: String,
    durationSeconds: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onJump: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(LocalShapes.current.card)
            .background(
                if (isCurrent) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surface
                }
            )
            .clickable(onClick = onJump)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(artist.takeIf { it.isNotBlank() }, formatDuration(durationSeconds))
                    .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Reorder controls. Compact so three of them still leave room for the title.
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(32.dp)) {
            Text("↑", style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(32.dp)) {
            Text("↓", style = MaterialTheme.typography.titleMedium)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.queue_remove),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
