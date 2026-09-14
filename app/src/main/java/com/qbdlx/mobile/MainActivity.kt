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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.qbdlx.mobile.lyrics.LyricsUi
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

    // Only the artwork of the playing track is needed up here, for the tint.
    // Collecting the whole playback state would recompose the entire app, theme
    // and backdrop included, on every position tick.
    val playingArtwork by remember(vm) {
        vm.playback.map { it.current?.artworkUrl }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)

    val openAlbumArtwork = artworkUrl(
        albumState.album?.image?.large ?: albumState.album?.image?.small,
        size = 300,
    )

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
                AppContent(vm)
            }
        }
    }
}

@Composable
private fun AppContent(vm: AppViewModel) {
        val lyricsState by vm.lyrics.collectAsStateWithLifecycle()
        var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
        var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
        var playerExpanded by rememberSaveable { mutableStateOf(false) }
        var queueOpen by rememberSaveable { mutableStateOf(false) }

        // Only whether something is playing, not the position, so the shell is
        // not recomposed once a second while music plays.
        val hasItem by remember(vm) {
            vm.playback.map { it.hasItem }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = false)

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

        // The player and the queue slide in over the list instead of replacing it.
        // Keeping the list composed underneath means its scroll position and any
        // half-typed search survive a trip to the player, which used to reset them
        // because the whole screen was removed and rebuilt.
        //
        // Two things have to be handled because the list is still there: it is
        // faded out while a layer covers it (the glass theme's background is
        // deliberately transparent, so otherwise it would show through), and a
        // blocker sits between the two so a tap that misses the player's own
        // controls cannot land on a row underneath and start another track.
        val overlayOpen = (playerExpanded && hasItem) || (queueOpen && hasItem)
        val contentAlpha by animateFloatAsState(
            targetValue = if (overlayOpen) 0f else 1f,
            animationSpec = tween(260),
            label = "contentAlpha",
        )

        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }) {
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
                    MainTabs(
                        vm = vm,
                        tab = tab,
                        onSelectTab = { tab = it },
                        onOpenDetail = { open(it) },
                        onExpandPlayer = { playerExpanded = true },
                    )
                }
            }
            }

            if (overlayOpen) {
                // Swallows anything the layer above does not handle itself.
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitPointerEvent().changes.forEach { it.consume() }
                                }
                            }
                        },
                )
            }

            AnimatedVisibility(
                visible = playerExpanded && hasItem,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(180)),
                label = "player",
            ) {
                FullPlayerHost(
                    vm = vm,
                    lyrics = lyricsState,
                    onOpenQueue = { queueOpen = true },
                    onCollapse = { playerExpanded = false },
                )
            }

            AnimatedVisibility(
                visible = queueOpen && hasItem,
                enter = slideInHorizontally(tween(300)) { it } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(180)),
                label = "queue",
            ) {
                QueueScreen(vm, onBack = { queueOpen = false })
            }
        }
}

/**
 * The tab screens and their chrome.
 *
 * Split out so the playback state is only collected by the mini player, rather
 * than by everything above it.
 */
@Composable
private fun MainTabs(
    vm: AppViewModel,
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
    onOpenDetail: (DetailTarget) -> Unit,
    onExpandPlayer: () -> Unit,
) {
    Scaffold(
        // Transparent so the tinted artwork backdrop behind the
        // Scaffold is visible; the window itself paints the base
        // colour, so nothing shows through to the launcher.
        containerColor = Color.Transparent,
        bottomBar = {
            Column {
                // Mini player rides above the navigation bar so it is
                // reachable from every tab.
                MiniPlayerHost(vm = vm, onExpand = onExpandPlayer)
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { onSelectTab(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Crossfade rather than cut, so switching tabs reads as a change of
            // place instead of a flash.
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "tab",
            ) { current ->
                when (current) {
                    Tab.SEARCH -> SearchScreen(
                        vm = vm,
                        onOpenAlbum = { onOpenDetail(DetailTarget.Album(it)) },
                        onOpenArtist = { onOpenDetail(DetailTarget.Artist(it)) },
                        onOpenPlaylist = { onOpenDetail(DetailTarget.Playlist(it)) },
                    )
                    Tab.DOWNLOADS -> DownloadsScreen(vm)
                    Tab.SETTINGS -> SettingsScreen(vm)
                }
            }
        }
    }
}

/** Collects the playback state for the mini player only. */
@Composable
private fun MiniPlayerHost(vm: AppViewModel, onExpand: () -> Unit) {
    val playback by vm.playback.collectAsStateWithLifecycle()

    // Slide in and out rather than appearing abruptly above the nav bar.
    AnimatedVisibility(
        visible = playback.hasItem,
        enter = slideInVertically(tween(260)) { it } + fadeIn(tween(180)),
        exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(140)),
        label = "miniPlayer",
    ) {
        MiniPlayerBar(
            state = playback,
            onTogglePlay = vm::togglePlayPause,
            onNext = vm::nextTrack,
            onPrevious = vm::previousTrack,
            onDownload = vm::downloadCurrentTrack,
            onStop = vm::stopPlayback,
            onExpand = onExpand,
        )
    }
}

/** Collects the playback state for the full player only. */
@Composable
private fun FullPlayerHost(
    vm: AppViewModel,
    lyrics: LyricsUi,
    onOpenQueue: () -> Unit,
    onCollapse: () -> Unit,
) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    FullPlayer(
        state = playback,
        lyrics = lyrics,
        lyricsPositionMs = vm.lyricsPositionMs,
        onTogglePlay = vm::togglePlayPause,
        onNext = vm::nextTrack,
        onPrevious = vm::previousTrack,
        onSeek = vm::seekTo,
        onToggleShuffle = vm::toggleShuffle,
        onCycleRepeat = vm::cycleRepeatMode,
        onOpenQueue = onOpenQueue,
        onDownload = vm::downloadCurrentTrack,
        onToggleLyrics = vm::toggleLyrics,
        onReloadLyrics = vm::reloadLyrics,
        onCollapse = onCollapse,
    )
}
