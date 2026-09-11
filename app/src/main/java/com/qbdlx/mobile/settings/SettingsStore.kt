package com.qbdlx.mobile.settings

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.qbdlx.mobile.download.MetadataTagger
import com.qbdlx.mobile.download.Quality
import com.qbdlx.mobile.download.RenameTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Small SharedPreferences-backed settings store exposed as a StateFlow so
 * Composables recompose automatically.
 *
 * Note: credentials/tokens live in [com.qbdlx.mobile.data.SessionStore], not here.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("qbdlx_settings", Context.MODE_PRIVATE)

    data class Settings(
        val quality: Quality = Quality.FLAC_HIGH,
        val artistTemplate: String = RenameTemplates.DEFAULT_ARTIST_TEMPLATE,
        val albumTemplate: String = RenameTemplates.DEFAULT_ALBUM_TEMPLATE,
        val trackTemplate: String = RenameTemplates.DEFAULT_TRACK_TEMPLATE,
        val saveCoverToFolder: Boolean = true,
        val customTreeUri: String? = null,
        val concurrentDownloads: Int = 2,
        val tag: MetadataTagger.Options = MetadataTagger.Options(),
        val saveLyricsFile: Boolean = false,
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<Settings> = _state.asStateFlow()

    val current: Settings get() = _state.value

    private fun load(): Settings = Settings(
        quality = Quality.fromId(prefs.getString(KEY_QUALITY, Quality.FLAC_HIGH.formatId)),
        artistTemplate = prefs.getString(KEY_ARTIST_TPL, RenameTemplates.DEFAULT_ARTIST_TEMPLATE)!!,
        albumTemplate = prefs.getString(KEY_ALBUM_TPL, RenameTemplates.DEFAULT_ALBUM_TEMPLATE)!!,
        trackTemplate = prefs.getString(KEY_TRACK_TPL, RenameTemplates.DEFAULT_TRACK_TEMPLATE)!!,
        saveCoverToFolder = prefs.getBoolean(KEY_SAVE_COVER, true),
        customTreeUri = prefs.getString(KEY_TREE_URI, null),
        concurrentDownloads = prefs.getInt(KEY_CONCURRENCY, 2).coerceIn(1, 4),
        saveLyricsFile = prefs.getBoolean(KEY_LYRICS, false),
        tag = MetadataTagger.Options(
            writeAlbumTitle = prefs.getBoolean("tag_album", true),
            writeAlbumArtist = prefs.getBoolean("tag_album_artist", true),
            writeTrackArtist = prefs.getBoolean("tag_artist", true),
            writeComposer = prefs.getBoolean("tag_composer", true),
            writeCopyright = prefs.getBoolean("tag_copyright", true),
            writeLabel = prefs.getBoolean("tag_label", true),
            writeDiscNumber = prefs.getBoolean("tag_disc", true),
            writeDiscTotal = prefs.getBoolean("tag_disc_total", true),
            writeGenre = prefs.getBoolean("tag_genre", true),
            writeIsrc = prefs.getBoolean("tag_isrc", true),
            writeUrl = prefs.getBoolean("tag_url", true),
            writeReleaseType = prefs.getBoolean("tag_type", true),
            writeExplicit = prefs.getBoolean("tag_explicit", true),
            writeTrackTitle = prefs.getBoolean("tag_title", true),
            writeTrackNumber = prefs.getBoolean("tag_track", true),
            writeTrackTotal = prefs.getBoolean("tag_track_total", true),
            writeUpc = prefs.getBoolean("tag_upc", true),
            writeReleaseDate = prefs.getBoolean("tag_release_date", true),
            writeYear = prefs.getBoolean("tag_year", true),
            writeCoverArt = prefs.getBoolean("tag_cover", true),
            writeComment = prefs.getBoolean("tag_comment", false),
            commentText = prefs.getString("tag_comment_text", "") ?: "",
            writeReplayGain = prefs.getBoolean("tag_replaygain", true),
        ),
    )

    fun setQuality(q: Quality) = edit { putString(KEY_QUALITY, q.formatId) }

    fun setArtistTemplate(v: String) = edit { putString(KEY_ARTIST_TPL, v) }
    fun setAlbumTemplate(v: String) = edit { putString(KEY_ALBUM_TPL, v) }
    fun setTrackTemplate(v: String) = edit { putString(KEY_TRACK_TPL, v) }
    fun setSaveCoverToFolder(v: Boolean) = edit { putBoolean(KEY_SAVE_COVER, v) }
    fun setConcurrency(v: Int) = edit { putInt(KEY_CONCURRENCY, v.coerceIn(1, 4)) }
    fun setSaveLyricsFile(v: Boolean) = edit { putBoolean(KEY_LYRICS, v) }

    fun setCustomTreeUri(uri: Uri?) = edit { putString(KEY_TREE_URI, uri?.toString()) }

    fun setTagOption(key: String, value: Boolean) = edit { putBoolean(key, value) }
    fun setTagCommentText(v: String) = edit { putString("tag_comment_text", v) }

    private inline fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit(commit = false) { block() }
        _state.update { load() }
    }

    companion object {
        private const val KEY_QUALITY = "quality_format_id"
        private const val KEY_ARTIST_TPL = "template_artist"
        private const val KEY_ALBUM_TPL = "template_album"
        private const val KEY_TRACK_TPL = "template_track"
        private const val KEY_SAVE_COVER = "save_cover_to_folder"
        private const val KEY_TREE_URI = "download_tree_uri"
        private const val KEY_CONCURRENCY = "concurrent_downloads"
        private const val KEY_LYRICS = "save_lyrics_file"
    }
}
