package com.qbdlx.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.ui.AppViewModel
import com.qbdlx.mobile.ui.artworkUrl
import com.qbdlx.mobile.ui.screens.AlbumScreen
import com.qbdlx.mobile.ui.screens.DownloadsScreen
import com.qbdlx.mobile.ui.screens.LoginScreen
import com.qbdlx.mobile.ui.screens.SearchScreen
import com.qbdlx.mobile.ui.screens.SettingsScreen
import com.qbdlx.mobile.settings.SettingsStore
import com.qbdlx.mobile.ui.components.FullPlayer
import com.qbdlx.mobile.ui.components.MiniPlayerBar
import com.qbdlx.mobile.ui.theme.QobuzDlxTheme
import com.qbdlx.mobile.ui.theme.rememberArtworkAccent

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Downloads work regardless; the notification is simply hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        askForNotificationPermission()

        setContent {
            // The theme wraps the whole tree, including the login screen, so the
            // chosen preset applies everywhere rather than only after sign-in.
            val settings by vm.settingsState.collectAsStateWithLifecycle()

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

@Composable
private fun AppRoot(vm: AppViewModel, settings: SettingsStore.Settings) {
    val session by vm.signedIn.collectAsStateWithLifecycle()

    // Artwork-derived accent, resolved once for the currently open album and fed
    // into the theme so the whole UI picks up the cover's colour.
    val albumState by vm.album.collectAsStateWithLifecycle()
    val coverUrl = artworkUrl(
        albumState.album?.image?.large ?: albumState.album?.image?.small,
        size = 300,
    )
    val accent by rememberArtworkAccent(
        artworkUrl = coverUrl,
        enabled = settings.tintFromArtwork,
    )

    QobuzDlxTheme(
        preset = settings.themePreset,
        mode = settings.themeMode,
        cornerScale = settings.cornerScale,
        accentOverride = accent,
    ) {
        if (session == null) {
            LoginScreen(vm)
            return@QobuzDlxTheme
        }

        var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
        var openAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
        var playerExpanded by rememberSaveable { mutableStateOf(false) }

        val playback by vm.playback.collectAsStateWithLifecycle()

        // The media session service is only started when something can play, so
        // launching the app without signing in does not spin up a service.
        LaunchedEffect(session) { vm.connectPlayer() }
        DisposableEffect(Unit) { onDispose { vm.releasePlayer() } }

        // Reset the detail view when the user switches tabs.
        LaunchedEffect(tab) { openAlbumId = null }

        if (playerExpanded && playback.hasItem) {
            FullPlayer(
                state = playback,
                onTogglePlay = vm::togglePlayPause,
                onNext = vm::nextTrack,
                onPrevious = vm::previousTrack,
                onSeek = vm::seekTo,
                onCollapse = { playerExpanded = false },
            )
            return@QobuzDlxTheme
        }

        val albumId = openAlbumId
        AnimatedContent(
            targetState = albumId,
            transitionSpec = {
                // Slide the detail view in over the list, and back out on close.
                val forward = targetState != null
                val offset = if (forward) { full: Int -> full / 6 } else { full: Int -> -full / 6 }
                (slideInHorizontally(animationSpec = tween(280)) { offset(it) } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(animationSpec = tween(280)) { -offset(it) } + fadeOut(tween(160)))
            },
            label = "album",
        ) { detailId ->
            if (detailId != null) {
                AlbumScreen(vm, onBack = { openAlbumId = null })
            } else {
                Scaffold(
                    bottomBar = {
                        Column {
                            // Mini player rides above the navigation bar so it is
                            // reachable from every tab.
                            MiniPlayerBar(
                                state = playback,
                                onTogglePlay = vm::togglePlayPause,
                                onNext = vm::nextTrack,
                                onPrevious = vm::previousTrack,
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
                                onOpenAlbum = { openAlbumId = it },
                                onOpenPlaylist = { /* playlist detail is not implemented yet */ },
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
