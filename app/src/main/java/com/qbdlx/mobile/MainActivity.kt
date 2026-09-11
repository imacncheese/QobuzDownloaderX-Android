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
import androidx.compose.foundation.layout.Box
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
import com.qbdlx.mobile.ui.screens.AlbumScreen
import com.qbdlx.mobile.ui.screens.DownloadsScreen
import com.qbdlx.mobile.ui.screens.LoginScreen
import com.qbdlx.mobile.ui.screens.SearchScreen
import com.qbdlx.mobile.ui.screens.SettingsScreen
import com.qbdlx.mobile.ui.theme.QobuzDlxTheme

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
            QobuzDlxTheme {
                AppRoot(vm)
            }
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
private fun AppRoot(vm: AppViewModel) {
    val session by vm.signedIn.collectAsStateWithLifecycle()

    if (session == null) {
        LoginScreen(vm)
        return
    }

    var tab by rememberSaveable { mutableStateOf(Tab.SEARCH) }
    var openAlbumId by rememberSaveable { mutableStateOf<String?>(null) }

    // Reset the detail view when the user switches tabs.
    LaunchedEffect(tab) { openAlbumId = null }

    val albumId = openAlbumId
    if (albumId != null) {
        AlbumScreen(vm, onBack = { openAlbumId = null })
        return
    }

    Scaffold(
        bottomBar = {
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
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.SEARCH -> SearchScreen(vm, onOpenAlbum = { openAlbumId = it })
                Tab.DOWNLOADS -> DownloadsScreen(vm)
                Tab.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}
