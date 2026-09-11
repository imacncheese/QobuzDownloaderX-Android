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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qbdlx.mobile.R
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.ui.AppViewModel
import com.qbdlx.mobile.ui.artworkUrl
import com.qbdlx.mobile.ui.formatBitDepthRate
import com.qbdlx.mobile.ui.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val state by vm.album.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.album?.title ?: stringResource(R.string.nav_search),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> ErrorPane(state.error!!, onRetry = vm::refreshAlbum)

                state.album != null -> AlbumContent(state.album!!, vm)

                else -> EmptyPane(Icons.Filled.Album, stringResource(R.string.search_empty))
            }
        }
    }
}

@Composable
private fun AlbumContent(album: com.qbdlx.mobile.api.Album, vm: AppViewModel) {
    val tracks = album.tracks?.items.orEmpty()
    val albumDownload by vm.albumDownload.collectAsStateWithLifecycle()
    val busy = albumDownload.loading && albumDownload.albumId == album.idString

    LazyColumn(
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            AlbumHeader(
                album = album,
                trackCount = tracks.size,
                busy = busy,
                error = albumDownload.error,
                onDownloadAll = { vm.downloadAlbum(album) },
                onDismissError = vm::clearAlbumDownloadError,
            )
        }
        items(tracks, key = { it.idString ?: it.hashCode().toString() }) { track ->
            AlbumTrackRow(track) { vm.downloadTrack(track) }
        }
    }
}

@Composable
private fun AlbumHeader(
    album: com.qbdlx.mobile.api.Album,
    trackCount: Int,
    busy: Boolean,
    error: String?,
    onDownloadAll: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Row {
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val art = artworkUrl(album.image?.large ?: album.image?.small, 600)
                if (art.isNullOrBlank()) {
                    Icon(
                        Icons.Filled.Album,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    AsyncImage(
                        model = art,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = album.title ?: "Untitled",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                album.version?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        album.artist?.name,
                        album.release_date_original?.take(4),
                        if (trackCount > 0) stringResource(R.string.tracks_count, trackCount) else null,
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                formatBitDepthRate(album.maximum_bit_depth, album.maximum_sampling_rate)?.let { spec ->
                    Spacer(Modifier.height(6.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(spec) },
                        leadingIcon = {
                            Icon(Icons.Filled.HighQuality, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                    )
                }
                album.label?.name?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onDownloadAll,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.loading_tracks))
            } else {
                Icon(Icons.Filled.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.download_album))
            }
        }

        if (trackCount == 0 && !busy) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.album_no_tracks),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismissError) {
                    Text(stringResource(R.string.ok))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AlbumTrackRow(track: Track, onDownload: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = (track.track_number ?: 0).toString().padStart(2, '0'),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(30.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title ?: "Untitled",
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        track.performer?.name?.takeIf { it != track.artist?.name },
                        formatDuration(track.duration),
                        track.maximum_bit_depth?.let { "$it-bit" },
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.download_track),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
