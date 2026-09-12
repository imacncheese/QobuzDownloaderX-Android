package com.qbdlx.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.ui.AppViewModel
import com.qbdlx.mobile.ui.artworkUrl
import com.qbdlx.mobile.ui.screens.AlbumScreen
import com.qbdlx.mobile.ui.screens.ArtistScreen
import com.qbdlx.mobile.ui.screens.DownloadsScreen
import com.qbdlx.mobile.ui.screens.LoginScreen
import com.qbdlx.mobile.ui.screens.PlaylistScreen
import com.qbdlx.mobile.ui.screens.QueueScreen
import com.qbdlx.mobile.ui.screens.SearchScreen
import com.qbdlx.mobile.ui.screens.SettingsScreen
import com.qbdlx.mobile.settings.SettingsStore
import com.qbdlx.mobile.settings.TintSource
import com.qbdlx.mobile.ui.components.FullPlayer
import com.qbdlx.mobile.ui.components.MiniPlayerBar
import com.qbdlx.mobile.ui.theme.QobuzDlxTheme
import com.qbdlx.mobile.ui.theme.ThemeMode
import com.qbdlx.mobile.ui.theme.TintBackdrop
import com.qbdlx.mobile.ui.theme.rememberArtworkAccent

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Downloads work regardless; the notification is simply hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A placeholder edge-to-edge call; the real system bar styling is applied
        // from inside the composition once the resolved theme is known, because
        // the icon tint has to match the in-app theme rather than the device's.
        enableEdgeToEdge()
        askForNotificationPermission()

        setContent {
            // The theme wraps the whole tree, including the login screen, so the
            // chosen preset applies everywhere rather than only after sign-in.
            val settings by vm.settingsState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()

            // "Follow system" must actually follow it. This recomposes when the
            // device switches mode, and re-applies the bar styling to match.
            val dark = when (settings.themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                    navigationBarStyle = if (dark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                        )
                    },
                )
            }

            AppRoot(vm, settings)
        }
    }

    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    SEARCH(R.string.nav_search, Icons.Filled.Search),
    DOWNLOADS(R.string.nav_downloads, Icons.Filled.Download),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

/**
 * A screen pushed over the tab content.
 *
 * Modelled as one sealed value rather than three separate id fields so the
 * transition and back handling stay in one place, and so a detail screen cannot
 * be opened without its loader being triggered.
 */
private sealed interface DetailTarget {
    val id: String

    data class Album(override val id: String) : DetailTarget
    data class Artist(override val id: String) : DetailTarget
    data class Playlist(override val id: String) : DetailTarget
}

/** Saveable representation, so state survives process death. */
private fun DetailTarget.toKey(): String = when (this) {
    is DetailTarget.Album -> "album:$id"
    is DetailTarget.Artist -> "artist:$id"
    is DetailTarget.Playlist -> "playlist:$id"
}

private fun detailFromKey(key: String?): DetailTarget? {
    if (key.isNullOrBlank()) return null
    val parts = key.split(':', limit = 2)
    if (parts.size != 2 || parts[1].isBlank()) return null
    return when (parts[0]) {
        "album" -> DetailTarget.Album(parts[1])
        "artist" -> DetailTarget.Artist(parts[1])
        "playlist" -> DetailTarget.Playlist(parts[1])
        else -> null
    }
}

@Composable
private fun AppRoot(vm: AppViewModel, settings: SettingsStore.Settings) {
    val session by vm.signedIn.collectAsStateWithLifecycle()
    val albumState by vm.album.collectAsStateWithLifecycle()
    val playback by vm.playback.collectAsStateWithLifecycle()

    val openAlbumArtwork = artworkUrl(
        albumState.album?.image?.large ?: albumState.album?.image?.small,
        size = 300,
    )
    val playingArtwork = playback.current?.artworkUrl

    // The tint follows whichever source the user picked. Now-playing takes
    // priority so the colour moves with the music as the queue advances, rather
    // than staying on whichever album screen happens to be open.
    val tintArtwork = when (settings.tintSource) {
        TintSource.NOW_PLAYING -> playingArtwork ?: openAlbumArtwork
        TintSource.OPEN_ALBUM -> openAlbumArtwork ?: playingArtwork
        TintSource.OFF -> null
    }
    val accent by rememberArtworkAccent(
        artworkUrl = tintArtwork,
        enabled = settings.tintSource != TintSource.OFF,
    )

    QobuzDlxTheme(
        preset = settings.themePreset,
        mode = settings.themeMode,
        cornerScale = settings.cornerScale,
        accentOverride = accent,
        glass = settings.glass,
    ) {
        // The backdrop sits behind everything, including the login screen, so the
        // whole app shares one tint instead of only the signed-in screens.
        Box(Modifier.fillMaxSize()) {
            TintBackdrop(
                artworkUrl = tintArtwork,
                alpha = settings.glass.backdropAlpha,
            )
            if (session == null) {
                LoginScreen(vm)
            } else {
                AppContent(vm, playback)
            }
        }
    }
}

@Composable
private fun AppContent(
    vm: AppViewModel,
    playback: com.qbdlx.mobile.playback.PlaybackState,
) {
        var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
        var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
        var playerExpanded by rememberSaveable { mutableStateOf(false) }
        var queueOpen by rememberSaveable { mutableStateOf(false) }

        val detail = detailFromKey(detailKey)
        fun open(target: DetailTarget?) { detailKey = target?.toKey() }

        // The media session service is only started when something can play, so
        // launching the app without signing in does not spin up a service.
        LaunchedEffect(Unit) { vm.connectPlayer() }
        DisposableEffect(Unit) { onDispose { vm.releasePlayer() } }

        // Reset the detail view when the user switches tabs.
        LaunchedEffect(tab) { open(null) }

        // Back closes the topmost layer rather than finishing the activity.
        // Without this, pressing back from an album or the player exited the app
        // and took all the navigation state with it.
        BackHandler(enabled = queueOpen || playerExpanded || detail != null) {
            when {
                queueOpen -> queueOpen = false
                playerExpanded -> playerExpanded = false
                else -> open(null)
            }
        }

        // The player and the queue take over the screen when open. Written as
        // branches rather than early returns so this stays a single composable.
        if (queueOpen && playback.hasItem) {
            QueueScreen(vm, onBack = { queueOpen = false })
        } else if (playerExpanded && playback.hasItem) {
            FullPlayer(
                state = playback,
                onTogglePlay = vm::togglePlayPause,
                onNext = vm::nextTrack,
                onPrevious = vm::previousTrack,
                onSeek = vm::seekTo,
                onToggleShuffle = vm::toggleShuffle,
                onCycleRepeat = vm::cycleRepeatMode,
                onOpenQueue = { queueOpen = true },
                onDownload = vm::downloadCurrentTrack,
                onCollapse = { playerExpanded = false },
            )
        } else {
            AnimatedContent(
            targetState = detail,
            transitionSpec = {
                // Slide the detail view in over the list, and back out on close.
                val forward = targetState != null
                val offset = if (forward) { full: Int -> full / 6 } else { full: Int -> -full / 6 }
                (slideInHorizontally(animationSpec = tween(280)) { offset(it) } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(animationSpec = tween(280)) { -offset(it) } + fadeOut(tween(160)))
            },
            label = "detail",
        ) { target ->
            if (target != null) {
                // Each loader is triggered here rather than on tap, so the screens
                // also work after process death or from a restored back stack. This
                // is what was missing when tapping an album showed an empty screen.
                when (target) {
                    is DetailTarget.Album -> {
                        LaunchedEffect(target.id) { vm.openAlbum(target.id) }
                        AlbumScreen(vm, onBack = { open(null) })
                    }

                    is DetailTarget.Artist -> {
                        LaunchedEffect(target.id) { vm.openArtist(target.id) }
                        ArtistScreen(
                            vm = vm,
                            onBack = { open(null) },
                            onOpenAlbum = { open(DetailTarget.Album(it)) },
                        )
                    }

                    is DetailTarget.Playlist -> {
                        LaunchedEffect(target.id) { vm.openPlaylist(target.id) }
                        PlaylistScreen(vm, onBack = { open(null) })
                    }
                }
            } else {
                Scaffold(
                    // Transparent so the tinted artwork backdrop behind the
                    // Scaffold is visible; the window itself paints the base
                    // colour, so nothing shows through to the launcher.
                    containerColor = Color.Transparent,
                    bottomBar = {
                        Column {
                            // Mini player rides above the navigation bar so it is
                            // reachable from every tab.
                            MiniPlayerBar(
                                state = playback,
                                onTogglePlay = vm::togglePlayPause,
                                onNext = vm::nextTrack,
                                onPrevious = vm::previousTrack,
                                onDownload = vm::downloadCurrentTrack,
                                onStop = vm::stopPlayback,
                                onExpand = { playerExpanded = true },
                            )
                            NavigationBar {
                                Tab.entries.forEach { t ->
                                    NavigationBarItem(
                                        selected = tab == t,
                                        onClick = { tab = t },
                                        icon = { Icon(t.icon, contentDescription = null) },
                                        label = { Text(stringResource(t.labelRes)) },
                                    )
                                }
                            }
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        when (tab) {
                            Tab.SEARCH -> SearchScreen(
                                vm = vm,
                                onOpenAlbum = { open(DetailTarget.Album(it)) },
                                onOpenArtist = { open(DetailTarget.Artist(it)) },
                                onOpenPlaylist = { open(DetailTarget.Playlist(it)) },
                            )
                            Tab.DOWNLOADS -> DownloadsScreen(vm)
                            Tab.SETTINGS -> SettingsScreen(vm)
                        }
                    }
                }
            }
        }
    }
}