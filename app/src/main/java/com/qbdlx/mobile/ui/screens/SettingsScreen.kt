package com.qbdlx.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qbdlx.mobile.R
import com.qbdlx.mobile.download.Quality
import com.qbdlx.mobile.ui.AppViewModel

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val settings by vm.settingsState.collectAsStateWithLifecycle()
    val session by vm.signedIn.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist read/write permission for the chosen tree.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setCustomTreeUri(uri)
        }
    }

    var artistTpl by remember(settings.artistTemplate) { mutableStateOf(settings.artistTemplate) }
    var albumTpl by remember(settings.albumTemplate) { mutableStateOf(settings.albumTemplate) }
    var trackTpl by remember(settings.trackTemplate) { mutableStateOf(settings.trackTemplate) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ------------------------------------------------------------- account
        SectionCard(title = stringResource(R.string.settings_account)) {
            Text(
                text = session?.displayName?.takeIf { it.isNotBlank() }
                    ?: session?.email
                    ?: stringResource(R.string.not_signed_in),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            session?.shortLabel?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.settings_subscription, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            session?.email?.takeIf { it.isNotBlank() && it != session?.displayName }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = vm::signOut) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.logout))
            }
        }

        // ------------------------------------------------------------- quality
        SectionCard(title = stringResource(R.string.settings_quality)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Quality.entries.forEach { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(
                            selected = settings.quality == q,
                            onClick = { vm.setQuality(q) },
                        )
                        Column {
                            Text(q.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "format_id ${q.formatId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.settings_quality_locked),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // -------------------------------------------------------------- folder
        SectionCard(title = stringResource(R.string.settings_folder)) {
            Text(
                text = settings.customTreeUri ?: stringResource(R.string.settings_folder_default),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { folderPicker.launch(null) }) {
                    Icon(Icons.Filled.Folder, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_folder_pick))
                }
                if (settings.customTreeUri != null) {
                    OutlinedButton(onClick = { vm.setCustomTreeUri(null) }) {
                        Text(stringResource(R.string.settings_folder_clear))
                    }
                }
            }
        }

        // ----------------------------------------------------------- templates
        SectionCard(title = stringResource(R.string.settings_templates)) {
            OutlinedTextField(
                value = artistTpl,
                onValueChange = { artistTpl = it; vm.setArtistTemplate(it) },
                label = { Text(stringResource(R.string.settings_template_artist)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = albumTpl,
                onValueChange = { albumTpl = it; vm.setAlbumTemplate(it) },
                label = { Text(stringResource(R.string.settings_template_album)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = trackTpl,
                onValueChange = { trackTpl = it; vm.setTrackTemplate(it) },
                label = { Text(stringResource(R.string.settings_template_track)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.settings_templates_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------- tagging
        SectionCard(title = stringResource(R.string.settings_tags)) {
            SwitchRow(
                label = stringResource(R.string.settings_cover_file),
                checked = settings.saveCoverToFolder,
                onChange = vm::setSaveCover,
            )
            SwitchRow(
                label = stringResource(R.string.settings_embed_cover),
                checked = settings.tag.writeCoverArt,
                onChange = { vm.setTagOption("tag_cover", it) },
            )
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            TagSwitch("tag_album", R.string.tag_album, settings.tag.writeAlbumTitle, vm)
            TagSwitch("tag_album_artist", R.string.tag_album_artist, settings.tag.writeAlbumArtist, vm)
            TagSwitch("tag_artist", R.string.tag_artist, settings.tag.writeTrackArtist, vm)
            TagSwitch("tag_composer", R.string.tag_composer, settings.tag.writeComposer, vm)
            TagSwitch("tag_label", R.string.tag_label, settings.tag.writeLabel, vm)
            TagSwitch("tag_genre", R.string.tag_genre, settings.tag.writeGenre, vm)
            TagSwitch("tag_track", R.string.tag_track, settings.tag.writeTrackNumber, vm)
            TagSwitch("tag_track_total", R.string.tag_track_total, settings.tag.writeTrackTotal, vm)
            TagSwitch("tag_disc", R.string.tag_disc, settings.tag.writeDiscNumber, vm)
            TagSwitch("tag_disc_total", R.string.tag_disc_total, settings.tag.writeDiscTotal, vm)
            TagSwitch("tag_isrc", R.string.tag_isrc, settings.tag.writeIsrc, vm)
            TagSwitch("tag_upc", R.string.tag_upc, settings.tag.writeUpc, vm)
            TagSwitch("tag_release_date", R.string.tag_release_date, settings.tag.writeReleaseDate, vm)
            TagSwitch("tag_year", R.string.tag_year, settings.tag.writeYear, vm)
            TagSwitch("tag_copyright", R.string.tag_copyright, settings.tag.writeCopyright, vm)
            TagSwitch("tag_type", R.string.tag_type, settings.tag.writeReleaseType, vm)
            TagSwitch("tag_explicit", R.string.tag_explicit, settings.tag.writeExplicit, vm)
            TagSwitch("tag_url", R.string.tag_url, settings.tag.writeUrl, vm)
            TagSwitch("tag_replaygain", R.string.tag_replaygain, settings.tag.writeReplayGain, vm)
        }

        // ---------------------------------------------------------- concurrency
        SectionCard(title = stringResource(R.string.settings_concurrency)) {
            Text(
                text = "${settings.concurrentDownloads}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = settings.concurrentDownloads.toFloat(),
                onValueChange = { vm.setConcurrency(it.toInt()) },
                valueRange = 1f..4f,
                steps = 2,
            )
        }

        // ---------------------------------------------------------------- about
        SectionCard(title = stringResource(R.string.settings_about)) {
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TagSwitch(key: String, labelRes: Int, checked: Boolean, vm: AppViewModel) {
    SwitchRow(
        label = stringResource(labelRes),
        checked = checked,
        onChange = { vm.setTagOption(key, it) },
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
