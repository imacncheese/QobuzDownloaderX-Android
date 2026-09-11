package com.qbdlx.mobile.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.Artist
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.ui.AppViewModel
import com.qbdlx.mobile.ui.artworkUrl
import com.qbdlx.mobile.ui.components.CoverArt
import com.qbdlx.mobile.ui.formatDuration
import com.qbdlx.mobile.ui.theme.LocalShapes
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit = {},
) {
    val state by vm.search.collectAsStateWithLifecycle()
    val albumDownload by vm.albumDownload.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQuery,
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.tab == AppViewModel.SearchTab.ALBUMS,
                onClick = { vm.onTab(AppViewModel.SearchTab.ALBUMS) },
                label = { Text(stringResource(R.string.search_tab_albums)) },
            )
            FilterChip(
                selected = state.tab == AppViewModel.SearchTab.TRACKS,
                onClick = { vm.onTab(AppViewModel.SearchTab.TRACKS) },
                label = { Text(stringResource(R.string.search_tab_tracks)) },
            )
            FilterChip(
                selected = state.tab == AppViewModel.SearchTab.ARTISTS,
                onClick = { vm.onTab(AppViewModel.SearchTab.ARTISTS) },
                label = { Text(stringResource(R.string.search_tab_artists)) },
            )
            FilterChip(
                selected = state.tab == AppViewModel.SearchTab.PLAYLISTS,
                onClick = { vm.onTab(AppViewModel.SearchTab.PLAYLISTS) },
                label = { Text(stringResource(R.string.search_tab_playlists)) },
            )
        }

        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.error != null -> {
                ErrorPane(message = state.error!!, onRetry = vm::retrySearch)
            }

            !state.hasSearched -> {
                EmptyPane(icon = Icons.Filled.Search, text = stringResource(R.string.search_empty))
            }

            else -> {
                val empty = when (state.tab) {
                    AppViewModel.SearchTab.ALBUMS -> state.albums.isEmpty()
                    AppViewModel.SearchTab.TRACKS -> state.tracks.isEmpty()
                    AppViewModel.SearchTab.ARTISTS -> state.artists.isEmpty()
                    AppViewModel.SearchTab.PLAYLISTS -> state.playlists.isEmpty()
                }
                if (empty) {
                    EmptyPane(
                        icon = Icons.Filled.Search,
                        text = stringResource(R.string.search_no_results, state.query),
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        when (state.tab) {
                            AppViewModel.SearchTab.ALBUMS -> items(state.albums, key = { it.idString ?: it.hashCode().toString() }) { album ->
                                AlbumRow(
                                    album = album,
                                    busy = albumDownload.loading &&
                                        albumDownload.albumId == album.idString,
                                    onOpen = { album.idString?.let(onOpenAlbum) },
                                    onDownload = { album.idString?.let(vm::downloadAlbumById) },
                                )
                            }
                            AppViewModel.SearchTab.TRACKS -> items(state.tracks, key = { it.idString ?: it.hashCode().toString() }) { track ->
                                TrackRow(track, onDownload = { vm.downloadTrack(track) }, onOpenAlbum = onOpenAlbum)
                            }
                            AppViewModel.SearchTab.ARTISTS -> items(state.artists, key = { it.idString ?: it.hashCode().toString() }) { artist ->
                                ArtistRow(artist)
                            }
                            AppViewModel.SearchTab.PLAYLISTS -> items(state.playlists, key = { it.idString ?: it.hashCode().toString() }) { playlist ->
                                PlaylistRow(
                                    playlist = playlist,
                                    onOpen = { playlist.idString?.let(onOpenPlaylist) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Album-download feedback: the full track list is fetched on demand,
        // so the user needs to see that it is working, or why it failed.
        albumDownload.error?.let { err ->
            Snackbar(
                modifier = Modifier.padding(16.dp),
                action = {
                    TextButton(onClick = vm::clearAlbumDownloadError) {
                        Text(stringResource(R.string.ok))
                    }
                },
            ) { Text(err) }
        }
        if (albumDownload.queuedCount > 0) {
            LaunchedEffect(albumDownload.queuedCount, albumDownload.albumId) {
                delay(2500)
                vm.clearAlbumDownloadError()
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: Album,
    busy: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(artworkUrl(album.image?.thumbnail ?: album.image?.small), 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.title ?: "Untitled",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    album.artist?.name,
                    album.release_date_original?.take(4),
                    album.tracks_count?.let { "$it tracks" },
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (busy) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = onDownload) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.download_album),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, onDownload: () -> Unit, onOpenAlbum: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(artworkUrl(track.album?.image?.thumbnail ?: track.image?.thumbnail), 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(
            Modifier
                .weight(1f)
                .clickable { track.album?.idString?.let(onOpenAlbum) },
        ) {
            Text(
                text = track.title ?: "Untitled",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    track.artist?.name ?: track.performer?.name,
                    track.album?.title,
                    formatDuration(track.duration),
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

@Composable
private fun ArtistRow(artist: Artist) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(null, 52.dp, fallback = Icons.Filled.Person)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = artist.name ?: "Unknown artist",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            artist.albums_count?.let {
                Text(
                    text = "$it albums",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlaylistRow(
    playlist: com.qbdlx.mobile.api.Playlist,
    onOpen: () -> Unit,
) {
    val cover = playlist.images.firstOrNull()
        ?: playlist.image_rectangle.firstOrNull()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LocalShapes.current.card)
            .clickable(onClick = onOpen)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(artworkUrl(cover), 56.dp, fallback = Icons.Filled.QueueMusic)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name ?: "Untitled playlist",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(
                    playlist.owner?.name,
                    playlist.tracks_count?.let { "$it tracks" },
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyPane(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun ErrorPane(message: String, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (onRetry != null) {
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.download_retry))
                }
            }
        }
    }
}
