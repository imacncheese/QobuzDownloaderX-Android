package com.qbdlx.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.AlbumPage
import com.qbdlx.mobile.api.Artist
import com.qbdlx.mobile.api.ArtistPage
import com.qbdlx.mobile.api.QobuzApiException
import com.qbdlx.mobile.api.QobuzClient
import com.qbdlx.mobile.api.Track
import com.qbdlx.mobile.api.TrackPage
import com.qbdlx.mobile.di.AppGraph
import com.qbdlx.mobile.download.ArtworkUrls
import com.qbdlx.mobile.download.DownloadItem
import com.qbdlx.mobile.download.DownloadQueue
import com.qbdlx.mobile.download.DownloadService
import com.qbdlx.mobile.download.DownloadState
import com.qbdlx.mobile.download.Quality
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val client: QobuzClient = AppGraph.client
    private val sessions = AppGraph.sessions
    private val settings = AppGraph.settings

    // ------------------------------------------------------------- auth state

    data class AuthUi(
        val email: String = "",
        val password: String = "",
        val token: String = "",
        val useToken: Boolean = false,
        val showAdvanced: Boolean = false,
        val appId: String = "",
        val appSecret: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val _auth = MutableStateFlow(
        AuthUi(email = sessions.lastEmail().orEmpty())
    )
    val auth: StateFlow<AuthUi> = _auth.asStateFlow()

    val signedIn: StateFlow<QobuzClient.Session?> = sessions.session

    fun onEmail(v: String) = _auth.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _auth.update { it.copy(password = v, error = null) }
    fun onToken(v: String) = _auth.update { it.copy(token = v, error = null) }
    fun toggleTokenMode() = _auth.update { it.copy(useToken = !it.useToken, error = null) }
    fun toggleAdvanced() = _auth.update { it.copy(showAdvanced = !it.showAdvanced) }
    fun onAppId(v: String) = _auth.update { it.copy(appId = v) }
    fun onAppSecret(v: String) = _auth.update { it.copy(appSecret = v) }

    fun signIn() {
        val state = _auth.value
        if (state.busy) return

        if (state.useToken) {
            if (state.token.isBlank()) {
                _auth.update { it.copy(error = "Enter your user auth token.") }
                return
            }
        } else {
            if (state.email.isBlank() || state.password.isBlank()) {
                _auth.update { it.copy(error = "Enter both your e-mail and password.") }
                return
            }
        }

        _auth.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val session = client.login(
                    email = state.email.takeIf { !state.useToken },
                    password = state.password.takeIf { !state.useToken },
                    token = state.token.takeIf { state.useToken },
                    appIdOverride = state.appId.takeIf { it.isNotBlank() },
                    appSecretOverride = state.appSecret.takeIf { it.isNotBlank() },
                )
                sessions.save(session)
                _auth.update { it.copy(busy = false, password = "", token = "", error = null) }
            } catch (e: Throwable) {
                _auth.update { it.copy(busy = false, error = friendly(e)) }
            }
        }
    }

    fun signOut() {
        client.logout()
        sessions.clear()
        _search.update { SearchUi() }
    }

    // ----------------------------------------------------------- search state

    enum class SearchTab { ALBUMS, TRACKS, ARTISTS }

    data class SearchUi(
        val query: String = "",
        val tab: SearchTab = SearchTab.ALBUMS,
        val loading: Boolean = false,
        val albums: List<Album> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val error: String? = null,
        val hasSearched: Boolean = false,
    )

    private val _search = MutableStateFlow(SearchUi())
    val search: StateFlow<SearchUi> = _search.asStateFlow()

    private var searchJob: Job? = null

    fun onQuery(v: String) {
        _search.update { it.copy(query = v) }
        searchJob?.cancel()
        if (v.isBlank()) {
            _search.update { it.copy(albums = emptyList(), tracks = emptyList(), artists = emptyList(), hasSearched = false, error = null) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(350) // debounce so we do not hammer the API on every keystroke
            runSearch(v, _search.value.tab)
        }
    }

    fun onTab(tab: SearchTab) {
        _search.update { it.copy(tab = tab) }
        val q = _search.value.query
        if (q.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(q, tab) }
        }
    }

    fun retrySearch() {
        val q = _search.value.query
        if (q.isNotBlank()) {
            searchJob?.cancel()
            searchJob = viewModelScope.launch { runSearch(q, _search.value.tab) }
        }
    }

    private suspend fun runSearch(query: String, tab: SearchTab) {
        _search.update { it.copy(loading = true, error = null) }
        try {
            when (tab) {
                SearchTab.ALBUMS -> {
                    val page: AlbumPage = client.searchAlbums(query)
                    _search.update { it.copy(albums = page.items, loading = false, hasSearched = true) }
                }
                SearchTab.TRACKS -> {
                    val page: TrackPage = client.searchTracks(query)
                    _search.update { it.copy(tracks = page.items, loading = false, hasSearched = true) }
                }
                SearchTab.ARTISTS -> {
                    val page: ArtistPage = client.searchArtists(query)
                    _search.update { it.copy(artists = page.items, loading = false, hasSearched = true) }
                }
            }
        } catch (e: Throwable) {
            _search.update { it.copy(loading = false, error = friendly(e), hasSearched = true) }
        }
    }

    // ------------------------------------------------------------ album state

    data class AlbumUi(
        val albumId: String? = null,
        val loading: Boolean = false,
        val album: Album? = null,
        val error: String? = null,
    )

    private val _album = MutableStateFlow(AlbumUi())
    val album: StateFlow<AlbumUi> = _album.asStateFlow()

    fun openAlbum(albumId: String) {
        if (_album.value.albumId == albumId && _album.value.album != null) return
        _album.value = AlbumUi(albumId = albumId, loading = true)
        viewModelScope.launch {
            try {
                val full = client.getFullAlbum(albumId)
                _album.value = AlbumUi(albumId = albumId, loading = false, album = full)
            } catch (e: Throwable) {
                _album.value = AlbumUi(albumId = albumId, loading = false, error = friendly(e))
            }
        }
    }

    fun refreshAlbum() {
        _album.value.albumId?.let { id ->
            _album.value = AlbumUi(albumId = id, loading = true)
            viewModelScope.launch {
                try {
                    _album.value = AlbumUi(albumId = id, loading = false, album = client.getFullAlbum(id))
                } catch (e: Throwable) {
                    _album.value = AlbumUi(albumId = id, loading = false, error = friendly(e))
                }
            }
        }
    }

    /** Downloads an album by id, used from the search list before it is opened. */
    fun downloadAlbumById(albumId: String) = downloadAlbum(Album(id = JsonPrimitive(albumId)))

    // --------------------------------------------------------- download state

    val downloads: StateFlow<List<DownloadState>> = DownloadQueue.items

    /**
     * Album-level download state, so the search list can show a spinner and
     * surface errors while it fetches a release's full track list.
     */
    data class AlbumDownloadUi(
        val albumId: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val queuedCount: Int = 0,
    )

    private val _albumDownload = MutableStateFlow(AlbumDownloadUi())
    val albumDownload: StateFlow<AlbumDownloadUi> = _albumDownload.asStateFlow()

    /**
     * Queues every track of [album].
     *
     * Search results carry only a preview of the track list, so the full album is
     * fetched first when needed. A release that genuinely has no tracks reports
     * that instead of silently doing nothing.
     */
    fun downloadAlbum(album: Album) {
        val albumId = album.idString
        val existing = album.tracks?.items.orEmpty()

        if (existing.isNotEmpty()) {
            queueAlbum(album, existing)
            return
        }

        if (albumId == null) {
            _albumDownload.value = AlbumDownloadUi(error = "This album has no id, so it cannot be downloaded.")
            return
        }

        _albumDownload.value = AlbumDownloadUi(albumId = albumId, loading = true)
        viewModelScope.launch {
            try {
                val full = client.getFullAlbum(albumId)
                val tracks = full.tracks?.items.orEmpty()
                if (tracks.isEmpty()) {
                    _albumDownload.value = AlbumDownloadUi(
                        albumId = albumId,
                        error = "Qobuz returned no tracks for this album.",
                    )
                } else {
                    queueAlbum(full, tracks)
                    _albumDownload.value = AlbumDownloadUi(albumId = albumId, queuedCount = tracks.size)
                }
            } catch (e: Throwable) {
                _albumDownload.value = AlbumDownloadUi(albumId = albumId, error = friendly(e))
            }
        }
    }

    private fun queueAlbum(album: Album, tracks: List<Track>) {
        val items = tracks.map { it.toDownloadItem(album) }
        _albumDownload.value = AlbumDownloadUi(albumId = album.idString, queuedCount = items.size)
        enqueueAndStart(items)
    }

    fun clearAlbumDownloadError() {
        _albumDownload.value = AlbumDownloadUi()
    }

    fun downloadTrack(track: Track) {
        enqueueAndStart(listOf(track.toDownloadItem(track.album)))
    }

    private fun enqueueAndStart(items: List<DownloadItem>) {
        if (items.isEmpty()) return
        DownloadQueue.enqueue(items)
        DownloadService.start(getApplication())
    }

    private fun Track.toDownloadItem(album: Album?): DownloadItem {
        val cover = image?.large
            ?: album?.image?.large
            ?: album?.image?.small
        return DownloadItem(
            trackId = idString.orEmpty(),
            title = title ?: "Untitled",
            artist = artist?.name ?: performer?.name ?: album?.artist?.name ?: "Unknown artist",
            albumTitle = album?.title ?: "",
            albumId = album?.idString,
            trackNumber = track_number ?: 0,
            discNumber = media_number ?: 1,
            durationSeconds = duration ?: 0,
            coverUrl = cover,
            track = this,
            album = album,
        )
    }

    fun cancelDownload(id: String) = DownloadQueue.cancel(id)
    fun retryDownload(id: String) {
        DownloadQueue.retry(id)
        DownloadService.start(getApplication())
    }

    fun clearFinishedDownloads() = DownloadQueue.clearFinished()

    // ------------------------------------------------------------- preference

    val settingsState = settings.state

    fun setQuality(q: Quality) = settings.setQuality(q)
    fun setArtistTemplate(v: String) = settings.setArtistTemplate(v)
    fun setAlbumTemplate(v: String) = settings.setAlbumTemplate(v)
    fun setTrackTemplate(v: String) = settings.setTrackTemplate(v)
    fun setSaveCover(v: Boolean) = settings.setSaveCoverToFolder(v)
    fun setConcurrency(v: Int) = settings.setConcurrency(v)
    fun setArtworkSize(size: ArtworkUrls.Size) = settings.setArtworkSize(size)

    // ------------------------------------------------------------------ theme
    fun setThemePreset(preset: com.qbdlx.mobile.ui.theme.ThemePreset) =
        settings.setThemePreset(preset)

    fun setThemeMode(mode: com.qbdlx.mobile.ui.theme.ThemeMode) = settings.setThemeMode(mode)
    fun setCornerScale(scale: Float) = settings.setCornerScale(scale)
    fun setTintFromArtwork(enabled: Boolean) = settings.setTintFromArtwork(enabled)
    fun setCustomTreeUri(uri: android.net.Uri?) = settings.setCustomTreeUri(uri)
    fun setTagOption(key: String, value: Boolean) = settings.setTagOption(key, value)

    private fun friendly(e: Throwable): String = when (e) {
        is QobuzApiException.Http -> e.message ?: "Request failed"
        is QobuzApiException.Auth -> e.message ?: "Authentication failed"
        is QobuzApiException.Network -> e.message ?: "Network error"
        is QobuzApiException.CredentialsUnavailable ->
            "Could not fetch Qobuz API credentials automatically. " +
                "Open Advanced and paste an app_id / app_secret pair."
        is QobuzApiException.QualityUnavailable -> e.message ?: "Quality unavailable"
        is QobuzApiException.Parsing -> "Unexpected response from Qobuz. ${e.message ?: ""}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
