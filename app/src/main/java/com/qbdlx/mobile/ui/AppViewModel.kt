package com.qbdlx.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qbdlx.mobile.api.Album
import com.qbdlx.mobile.api.AlbumPage
import com.qbdlx.mobile.api.Artist
import com.qbdlx.mobile.api.ArtistPage
import com.qbdlx.mobile.api.Playlist
import com.qbdlx.mobile.api.PlaylistPage
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
import com.qbdlx.mobile.playback.PlaybackState
import com.qbdlx.mobile.playback.QueueItem
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

    enum class SearchTab { ALBUMS, TRACKS, ARTISTS, PLAYLISTS }

    data class SearchUi(
        val query: String = "",
        val tab: SearchTab = SearchTab.ALBUMS,
        val loading: Boolean = false,
        val albums: List<Album> = emptyList(),
        val tracks: List<Track> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val playlists: List<Playlist> = emptyList(),
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
            _search.update {
                it.copy(
                    albums = emptyList(),
                    tracks = emptyList(),
                    artists = emptyList(),
                    playlists = emptyList(),
                    hasSearched = false,
                    error = null,
                )
            }
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
                SearchTab.PLAYLISTS -> {
                    val page: PlaylistPage = client.searchPlaylists(query)
                    _search.update { it.copy(playlists = page.items, loading = false, hasSearched = true) }
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

    // ----------------------------------------------------------- artist state

    data class ArtistUi(
        val artistId: String? = null,
        val loading: Boolean = false,
        val artist: com.qbdlx.mobile.api.Artist? = null,
        val error: String? = null,
    )

    private val _artist = MutableStateFlow(ArtistUi())
    val artist: StateFlow<ArtistUi> = _artist.asStateFlow()

    /**
     * Loads an artist and their releases.
     *
     * Called on navigation rather than on tap, matching [openAlbum], so the screen
     * also works after process death or from a restored back stack.
     */
    fun openArtist(artistId: String) {
        android.util.Log.i("QbdlxArtist", "openArtist ENTER id=$artistId")
        if (_artist.value.artistId == artistId && _artist.value.artist != null) {
            android.util.Log.i("QbdlxArtist", "openArtist SKIPPED (already loaded)")
            return
        }
        _artist.value = ArtistUi(artistId = artistId, loading = true)
        viewModelScope.launch {
            try {
                val full = client.getArtist(artistId)
                android.util.Log.i(
                    "QbdlxArtist",
                    "openArtist OK id=$artistId name='${full.name}' albums=${full.albums?.items?.size ?: 0}",
                )
                _artist.value = ArtistUi(artistId = artistId, loading = false, artist = full)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("QbdlxArtist", "openArtist FAILED id=$artistId: $e", e)
                _artist.value = ArtistUi(artistId = artistId, loading = false, error = friendly(e))
            }
        }
    }

    fun refreshArtist() {
        _artist.value.artistId?.let { id ->
            _artist.value = ArtistUi(artistId = id, loading = true)
            viewModelScope.launch {
                try {
                    _artist.value = ArtistUi(
                        artistId = id,
                        loading = false,
                        artist = client.getArtist(id),
                    )
                } catch (e: Throwable) {
                    _artist.value = ArtistUi(artistId = id, loading = false, error = friendly(e))
                }
            }
        }
    }

    // --------------------------------------------------------- playlist state

    data class PlaylistUi(
        val playlistId: String? = null,
        val loading: Boolean = false,
        val playlist: Playlist? = null,
        val error: String? = null,
    )

    private val _playlist = MutableStateFlow(PlaylistUi())
    val playlist: StateFlow<PlaylistUi> = _playlist.asStateFlow()

    fun openPlaylist(playlistId: String) {
        android.util.Log.i("QbdlxPlaylist", "openPlaylist ENTER id=$playlistId")
        if (_playlist.value.playlistId == playlistId && _playlist.value.playlist != null) {
            android.util.Log.i("QbdlxPlaylist", "openPlaylist SKIPPED (already loaded)")
            return
        }
        _playlist.value = PlaylistUi(playlistId = playlistId, loading = true)
        viewModelScope.launch {
            try {
                val full = client.getPlaylist(playlistId)
                android.util.Log.i(
                    "QbdlxPlaylist",
                    "openPlaylist OK id=$playlistId name='${full.name}' tracks=${full.tracks?.items?.size ?: 0}",
                )
                _playlist.value = PlaylistUi(playlistId = playlistId, loading = false, playlist = full)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("QbdlxPlaylist", "openPlaylist FAILED id=$playlistId: $e", e)
                _playlist.value = PlaylistUi(playlistId = playlistId, loading = false, error = friendly(e))
            }
        }
    }

    fun refreshPlaylist() {
        _playlist.value.playlistId?.let { id ->
            _playlist.value = PlaylistUi(playlistId = id, loading = true)
            viewModelScope.launch {
                try {
                    _playlist.value = PlaylistUi(
                        playlistId = id,
                        loading = false,
                        playlist = client.getPlaylist(id),
                    )
                } catch (e: Throwable) {
                    _playlist.value = PlaylistUi(playlistId = id, loading = false, error = friendly(e))
                }
            }
        }
    }

    fun openAlbum(albumId: String) {
        android.util.Log.i(
            "QbdlxAlbum",
            "openAlbum ENTER id=$albumId previous=${_album.value.albumId} hasAlbum=${_album.value.album != null}",
        )
        if (_album.value.albumId == albumId && _album.value.album != null) {
            android.util.Log.i("QbdlxAlbum", "openAlbum SKIPPED (already loaded) id=$albumId")
            return
        }
        _album.value = AlbumUi(albumId = albumId, loading = true)
        viewModelScope.launch {
            android.util.Log.i("QbdlxAlbum", "openAlbum coroutine started id=$albumId")
            try {
                val full = client.getFullAlbum(albumId)
                val tracks = full.tracks?.items.orEmpty().size
                android.util.Log.i(
                    "QbdlxAlbum",
                    "openAlbum OK id=$albumId title='${full.title}' tracks=$tracks",
                )
                _album.value = AlbumUi(albumId = albumId, loading = false, album = full)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Rethrow: this is structured cancellation, not an app failure.
                android.util.Log.w("QbdlxAlbum", "openAlbum CANCELLED id=$albumId")
                throw e
            } catch (e: Throwable) {
                android.util.Log.w("QbdlxAlbum", "openAlbum FAILED id=$albumId: $e", e)
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

    /** Queues a whole list of tracks, used by playlist downloads. */
    fun downloadTracks(tracks: List<Track>) {
        val items = tracks.filter { !it.idString.isNullOrBlank() }.map { it.toDownloadItem(it.album) }
        enqueueAndStart(items)
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

    // ------------------------------------------------------------- playback

    private val playerController by lazy { AppGraph.player(app) }

    val playback: StateFlow<PlaybackState> get() = playerController.state

    /** Connects to the playback service. Call once from the UI. */
    fun connectPlayer() = playerController.connect()

    fun releasePlayer() = playerController.release()

    /**
     * Plays [tracks] starting at [startIndex].
     *
     * The whole list becomes the queue so next/previous work, matching what the
     * user sees on screen rather than playing a single detached track.
     */
    fun playTracks(tracks: List<Track>, startIndex: Int, album: Album? = null) {
        val usable = tracks.filter { !it.idString.isNullOrBlank() }
        android.util.Log.i(
            "QbdlxPlayer",
            "playTracks called: requested=$startIndex usable=${usable.size} of ${tracks.size}",
        )
        if (usable.isEmpty()) {
            android.util.Log.w("QbdlxPlayer", "playTracks: no tracks with ids, nothing to play")
            return
        }
        val start = usable.indexOfFirst { it.idString == tracks.getOrNull(startIndex)?.idString }
            .takeIf { it >= 0 } ?: 0

        val items = usable.map { t -> t.toQueueItem(t.album ?: album) }
        playerController.play(items, start)
    }

    fun togglePlayPause() = playerController.togglePlayPause()
    fun nextTrack() = playerController.next()
    fun previousTrack() = playerController.previous()
    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)
    fun seekToQueueIndex(index: Int) = playerController.seekToIndex(index)
    fun stopPlayback() = playerController.stop()
    fun clearPlaybackError() = playerController.clearError()

    private fun Track.toQueueItem(album: Album?): QueueItem {
        val resolvedAlbum = album ?: this.album
        return QueueItem(
            trackId = idString.orEmpty(),
            title = title ?: "Untitled",
            artist = artist?.name ?: performer?.name ?: resolvedAlbum?.artist?.name ?: "Unknown artist",
            albumTitle = resolvedAlbum?.title.orEmpty(),
            artworkUrl = image?.large ?: resolvedAlbum?.image?.large ?: resolvedAlbum?.image?.small,
            durationSeconds = duration ?: 0,
            track = this,
        )
    }

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
