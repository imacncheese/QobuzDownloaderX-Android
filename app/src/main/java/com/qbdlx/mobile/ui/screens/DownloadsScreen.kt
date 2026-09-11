package com.qbdlx.mobile.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.R
import com.qbdlx.mobile.download.DownloadService
import com.qbdlx.mobile.download.DownloadState
import com.qbdlx.mobile.download.DownloadStatus
import com.qbdlx.mobile.ui.AppViewModel
import com.qbdlx.mobile.ui.formatBytes
import com.qbdlx.mobile.ui.formatDuration

@Composable
fun DownloadsScreen(vm: AppViewModel) {
    val items by vm.downloads.collectAsStateWithLifecycle()

    if (items.isEmpty()) {
        EmptyPane(Icons.Filled.Download, stringResource(R.string.downloads_empty))
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (items.any { it.isTerminal }) {
                TextButton(onClick = vm::clearFinishedDownloads) {
                    Text(stringResource(R.string.downloads_clear_finished))
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.item.id }) { state ->
                DownloadCard(state, vm)
            }
        }
    }
}

@Composable
private fun DownloadCard(state: DownloadState, vm: AppViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    StatusIcon(state.status)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            state.item.artist.takeIf { it.isNotBlank() },
                            state.item.albumTitle.takeIf { it.isNotBlank() },
                            formatDuration(state.item.durationSeconds).takeIf { state.item.durationSeconds > 0 },
                        ).joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when (state.status) {
                    DownloadStatus.FAILED, DownloadStatus.CANCELLED -> IconButton(onClick = { vm.retryDownload(state.item.id) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.download_retry))
                    }
                    DownloadStatus.COMPLETED -> Unit
                    else -> IconButton(onClick = { vm.cancelDownload(state.item.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.download_cancel))
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            StatusLine(state)

            if (!state.isTerminal) {
                Spacer(Modifier.height(8.dp))
                if (state.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            state.savedTo?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.saved_to, it),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            state.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.warning?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: DownloadState) {
    val text = when (state.status) {
        DownloadStatus.QUEUED -> stringResource(R.string.status_queued)
        DownloadStatus.RESOLVING -> stringResource(R.string.status_resolving)
        DownloadStatus.DOWNLOADING -> {
            val q = state.resolvedQuality?.label
            val speed = if (state.speedBytesPerSecond > 0) {
                " • " + DownloadService.formatSpeed(state.speedBytesPerSecond)
            } else {
                ""
            }
            val sizes = if (state.totalBytes > 0) {
                " • ${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
            } else if (state.bytesDownloaded > 0) {
                " • ${formatBytes(state.bytesDownloaded)}"
            } else {
                ""
            }
            "${stringResource(R.string.status_downloading)} ${
                listOfNotNull(q, state.progressPercent.takeIf { it > 0 }?.let { "$it%" })
                    .joinToString(" • ")
            }$sizes$speed"
        }
        DownloadStatus.TAGGING -> stringResource(R.string.status_tagging)
        DownloadStatus.COMPLETED -> stringResource(R.string.status_completed)
        DownloadStatus.FAILED -> stringResource(R.string.status_failed)
        DownloadStatus.CANCELLED -> stringResource(R.string.status_cancelled)
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = when (state.status) {
            DownloadStatus.FAILED -> MaterialTheme.colorScheme.error
            DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun StatusIcon(status: DownloadStatus) {
    when (status) {
        DownloadStatus.COMPLETED -> Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        DownloadStatus.FAILED -> Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        DownloadStatus.CANCELLED -> Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DownloadStatus.TAGGING, DownloadStatus.RESOLVING -> Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
        )
        else -> Icon(
            Icons.Filled.Download,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
