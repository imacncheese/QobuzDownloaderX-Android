package com.qbdlx.mobile.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Qobuz API models, mirrored from the QopenAPI C# library used by
 * ImAiiR/QobuzDownloaderX. Everything is optional because Qobuz omits fields
 * freely and the search vs. detail endpoints return different shapes.
 */

@Serializable
data class Image(
    val thumbnail: String? = null,
    val small: String? = null,
    val large: String? = null,
    val back: String? = null,
)

@Serializable
data class ArtistRef(
    val id: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
    val image: JsonElement? = null,
    val picture: String? = null,
    val albums_count: Int? = null,
    /**
     * Role annotations such as ["main-artist"], ["featured-artist"] or
     * ["main-artist","composer"]. Album-level artist entries carry these; the
     * ARTIST / ALBUMARTIST tags are derived from them.
     */
    val roles: List<String>? = null,
) {
    val idString: String? get() = id.asStringOrNull()
}

@Serializable
data class LabelRef(
    val id: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
    val supplier_id: Int? = null,
)

@Serializable
data class Genre(
    val id: Int? = null,
    val name: String? = null,
    val slug: String? = null,
    val color: String? = null,
)

@Serializable
data class AudioInfo(
    val replaygain_track_gain: String? = null,
    val replaygain_track_peak: String? = null,
)

@Serializable
data class Goody(
    val id: Int? = null,
    val url: String? = null,
    val original_url: String? = null,
    val description: String? = null,
    val name: String? = null,
    val file_format_id: Int? = null,
)

@Serializable
data class Track(
    val id: JsonElement? = null,
    val title: String? = null,
    val duration: Int? = null,
    val track_number: Int? = null,
    val media_number: Int? = null,
    val media_count: Int? = null,
    val performers: String? = null,
    val copyright: String? = null,
    val isrc: String? = null,
    val upc: String? = null,
    val version: JsonElement? = null,
    val work: String? = null,
    val hires: Boolean? = null,
    val maximum_bit_depth: Int? = null,
    val maximum_sampling_rate: Double? = null,
    val parental_warning: Boolean? = null,
    val release_date_original: String? = null,
    val audio_info: AudioInfo? = null,
    val album: Album? = null,
    val artist: ArtistRef? = null,
    val performer: ArtistRef? = null,
    val composer: ArtistRef? = null,
    val label: LabelRef? = null,
    val genre: Genre? = null,
    val image: Image? = null,
    val playlist_track_id: JsonElement? = null,
    val streamable: Boolean? = null,
    val downloadable: Boolean? = null,
) {
    val idString: String? get() = id.asStringOrNull()
    val versionString: String? get() = version.asStringOrNull()
    val inPlaylist: Boolean get() = playlist_track_id.asStringOrNull() != null
}

@Serializable
data class TrackPage(
    val offset: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val items: List<Track> = emptyList(),
)

@Serializable
data class Album(
    val id: JsonElement? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val slug: String? = null,
    val version: String? = null,
    val upc: String? = null,
    val duration: Int? = null,
    val tracks_count: Int? = null,
    val media_count: Int? = null,
    val release_date_original: String? = null,
    val release_date_download: String? = null,
    val release_date_stream: String? = null,
    val copyright: String? = null,
    val description: String? = null,
    val parental_warning: Boolean? = null,
    val hires: Boolean? = null,
    val hires_streamable: Boolean? = null,
    val maximum_bit_depth: Int? = null,
    val maximum_sampling_rate: Double? = null,
    val streamable: Boolean? = null,
    val downloadable: Boolean? = null,
    val genre: Genre? = null,
    val label: LabelRef? = null,
    val artist: ArtistRef? = null,
    val artists: List<ArtistRef> = emptyList(),
    val image: Image? = null,
    val goodies: List<Goody>? = null,
    val tracks: TrackPage? = null,
    val url: String? = null,
    val product_type: String? = null,
) {
    val idString: String? get() = id.asStringOrNull()
}

@Serializable
data class AlbumPage(
    val offset: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val items: List<Album> = emptyList(),
)

@Serializable
data class Artist(
    val id: JsonElement? = null,
    val name: String? = null,
    val slug: String? = null,
    val image: JsonElement? = null,
    val picture: String? = null,
    val albums_count: Int? = null,
    val albums: AlbumPage? = null,
) {
    val idString: String? get() = id.asStringOrNull()
}

@Serializable
data class ArtistPage(
    val offset: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val items: List<Artist> = emptyList(),
)

@Serializable
data class Playlist(
    val id: JsonElement? = null,
    val name: String? = null,
    val description: String? = null,
    val tracks_count: Int? = null,
    val duration: Int? = null,
    val is_public: Boolean? = null,
    val owner: Owner? = null,
    val images: List<String> = emptyList(),
    val image_rectangle: List<String> = emptyList(),
    val tracks: TrackPage? = null,
) {
    val idString: String? get() = id.asStringOrNull()
}

@Serializable
data class Owner(val id: Int? = null, val name: String? = null)

@Serializable
data class PlaylistPage(
    val offset: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val items: List<Playlist> = emptyList(),
)

@Serializable
data class AlbumSearchResult(
    val query: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null,
    val albums: AlbumPage? = null,
)

@Serializable
data class TrackSearchResult(
    val query: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null,
    val tracks: TrackPage? = null,
)

@Serializable
data class ArtistSearchResult(
    val query: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null,
    val artists: ArtistPage? = null,
)

@Serializable
data class PlaylistSearchResult(
    val query: String? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val total: Int? = null,
    val playlists: PlaylistPage? = null,
)

@Serializable
data class CatalogSearchResult(
    val albums: AlbumSearchResult? = null,
    val tracks: TrackSearchResult? = null,
    val artists: ArtistSearchResult? = null,
    val playlists: PlaylistSearchResult? = null,
)

@Serializable
data class FavoritesResponse(
    val tracks: TrackPage? = null,
    val albums: AlbumPage? = null,
    val artists: ArtistPage? = null,
)

@Serializable
data class FileUrlResponse(
    val track_id: JsonElement? = null,
    val duration: Int? = null,
    val url: String? = null,
    val format_id: JsonElement? = null,
    val mime_type: String? = null,
    val sampling_rate: Double? = null,
    val bit_depth: Int? = null,
    val codec: String? = null,
) {
    val formatIdString: String? get() = format_id.asStringOrNull()
}

@Serializable
data class UserAccount(
    val id: Long? = null,
    val publicId: String? = null,
    val email: String? = null,
    val login: String? = null,
    val display_name: String? = null,
    val firstname: String? = null,
    val lastname: String? = null,
    val country_code: String? = null,
    val language_code: String? = null,
    val zone: String? = null,
    val store: String? = null,
    val avatar: String? = null,
    val credential: Credential? = null,
    val subscription: Subscription? = null,
)

@Serializable
data class Credential(
    val id: JsonElement? = null,
    val description: String? = null,
    val label: JsonElement? = null,
    val parameters: CredentialParameters? = null,
)

@Serializable
data class CredentialParameters(
    val lossy_streaming: Boolean? = null,
    val lossless_streaming: Boolean? = null,
    val hires_streaming: Boolean? = null,
    val hires_purchases_streaming: Boolean? = null,
    val mobile_streaming: Boolean? = null,
    val offline_streaming: Boolean? = null,
    val short_label: String? = null,
    val label: String? = null,
    val source: String? = null,
    val included_format_group_ids: List<Int> = emptyList(),
)

@Serializable
data class Subscription(
    val offer: String? = null,
    val periodicity: String? = null,
    val end_date: String? = null,
    val is_canceled: Boolean? = null,
)

@Serializable
data class LoginResponse(
    val user_auth_token: String? = null,
    val status: String? = null,
    val user: UserAccount? = null,
    @SerialName("id") val id: Long? = null,
    val login: String? = null,
    val message: String? = null,
    val code: String? = null,
)
