package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibrarySection
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource

/**
 * The read and write side of Jellyfin behind the neutral catalog contracts.
 *
 * Only ids cross this boundary: artwork and stream URLs are minted per call, so the persisted
 * identity is always the Jellyfin item id inside [MediaKey], never a URL or a token.
 *
 * Playback follows decision D11: `getPostedPlaybackInfo` with a music [org.jellyfin.sdk.model.api.DeviceProfile]
 * decides direct play vs transcode. The resolved [JellyfinPlaybackInfo] is kept for the runtime's
 * progress reporter, which needs the server's `playSessionId` and media source id.
 */
class JellyfinCatalog(
    private val api: () -> JellyfinApi?,
    private val settings: () -> PlaybackSettings = { PlaybackSettings() }
) : MediaCatalog,
    MediaLibraryWriter {
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Jellyfin

    /** The last resolved playback per item, so the reporter can quote the server's own ids. */
    private val playbackByItem = mutableMapOf<String, JellyfinPlaybackInfo>()

    private fun client(): JellyfinApi = api()
        ?: throw ServiceException(ServiceFailure.NotConnected, "Connect Jellyfin in Settings")

    fun playbackInfo(itemId: String): JellyfinPlaybackInfo? = synchronized(playbackByItem) {
        playbackByItem[itemId]
    }

    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = when (section) {
        LibrarySection.Liked -> client().likedSongs(offset, limit).toTracks().asEntries()
        LibrarySection.Playlists -> client().playlists(offset, limit).toPlaylists().asEntries()
        LibrarySection.Albums -> client().albums(offset, limit).toAlbums().asEntries()
        LibrarySection.Artists -> client().favoriteArtists(offset, limit).toArtists().asEntries()
        LibrarySection.Shows -> Page()
    }

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? = when (key.type) {
        MediaType.Album -> {
            val album = client().item(key.id)?.toAlbum(client())
            if (album == null) {
                null
            } else {
                val page = client().albumTracks(key.id, offset, limit)
                val total = album.trackCount.takeIf { it > 0 } ?: page.total
                Collection(
                    type = CollectionType.Album,
                    id = album.id,
                    title = album.title,
                    subtitle = album.artist,
                    artRes = 0,
                    items = page.items.map { it.toTrack(client()) },
                    artistId = album.artistId.takeIf { it.isNotEmpty() },
                    artUrl = album.artUrl,
                    totalItems = total,
                    hasMore = offset + page.items.size < total,
                    saveable = true,
                    service = ServiceId.Jellyfin
                )
            }
        }

        MediaType.Playlist -> {
            val playlist = client().playlist(key.id) ?: return null
            val page = client().playlistItems(key.id, offset, limit)
            val total = playlist.item.childCount?.takeIf { it > 0 } ?: page.total
            Collection(
                type = CollectionType.Playlist,
                id = playlist.item.id,
                title = playlist.item.name,
                subtitle = PLAYLIST_OWNER,
                artRes = 0,
                items = page.items.map { it.toTrack(client()) },
                artUrl = playlist.item.artUrl(client()),
                totalItems = total,
                hasMore = offset + page.items.size < total,
                saveable = false,
                service = ServiceId.Jellyfin
            )
        }

        else -> null
    }

    override suspend fun artist(key: MediaKey): Artist? = client().item(key.id)?.toArtist(client())

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> = client().artistAlbums(artist.id, offset, limit).toAlbums()

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> = client().artistTopTracks(artist.id, offset, limit).toTracks()

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults {
        val result = client().search(query, scopes, offset, limit)
        val client = client()
        return SearchResults(
            tracks = result.hints.filter { it.kind == JellyfinKind.Audio }.map { it.toTrack(client) },
            artists = result.hints.filter { it.kind == JellyfinKind.Artist }.map { it.toArtist(client) },
            albums = result.hints.filter { it.kind == JellyfinKind.Album }.map { it.toAlbum(client) },
            playlists = result.hints.filter { it.kind == JellyfinKind.Playlist }.map { it.toPlaylist(client) }
        )
    }

    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource {
        if (item.service != ServiceId.Jellyfin) {
            throw ServiceException(ServiceFailure.TrackUnavailable, "This item belongs to another service")
        }
        val client = client()
        val quality = settings().audioQuality
        // Jellyfin states bitrates in bits per second, unlike Subsonic's kbps `maxBitRate`.
        val info = client.playback(item.id, maxStreamingBitrate = quality.jellyfinBitrateKbps()?.times(1_000))
        synchronized(playbackByItem) {
            playbackByItem.clear()
            playbackByItem[item.id] = info
        }
        return PlaybackSource(
            id = item.key.encode(),
            uri = info.url,
            headers = info.headers
        )
    }

    override suspend fun radio(seed: MediaKey, offset: Int, limit: Int): Page<Track> {
        val page = client().instantMix(seed.id, seed.type, offset + limit)
        return Page.of(page.items.drop(offset).take(limit).map { it.toTrack(client()) }, offset, limit, page.total)
    }

    override suspend fun isSaved(item: MediaKey): Boolean? = when (item.type) {
        MediaType.Playlist, MediaType.Show -> null
        else -> runCatching { client().userData(item.id)?.isFavorite }.getOrNull()
    }

    override suspend fun isFollowed(artist: MediaKey): Boolean? = runCatching {
        client().userData(artist.id)?.isFavorite
    }.getOrNull()

    override suspend fun setSaved(item: MediaKey, saved: Boolean) {
        // Jellyfin favourites playlists too, but Sidetrack does not offer saving them.
        if (item.type == MediaType.Playlist || item.type == MediaType.Show) return
        client().setFavorite(item.id, saved)
    }

    override suspend fun setFollowed(artist: MediaKey, followed: Boolean) {
        client().setFavorite(artist.id, followed)
    }

    override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? = client().addToPlaylist(playlist.id, item.id)

    override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
        client().removeFromPlaylist(playlist.id, item.id, index)
    }

    override suspend fun createPlaylist(name: String): Playlist? {
        val client = client()
        val id = client.createPlaylist(name) ?: return null
        return client.item(id)?.toPlaylist(client)
    }

    private fun JellyfinPage.toTracks(): Page<Track> {
        val client = client()
        return Page(items.map { it.toTrack(client) }, total, startIndex + items.size < total)
    }

    private fun JellyfinPage.toAlbums(): Page<Album> {
        val client = client()
        return Page(items.map { it.toAlbum(client) }, total, startIndex + items.size < total)
    }

    private fun JellyfinPage.toArtists(): Page<Artist> {
        val client = client()
        return Page(items.map { it.toArtist(client) }, total, startIndex + items.size < total)
    }

    private fun JellyfinPage.toPlaylists(): Page<Playlist> {
        val client = client()
        return Page(items.map { it.toPlaylist(client) }, total, startIndex + items.size < total)
    }

    /** Widens a provider page to the entry type the library screen holds. */
    private fun <T : LibraryEntry> Page<T>.asEntries(): Page<LibraryEntry> = Page(items, total, hasMore)

    private companion object {
        /** Jellyfin has no owner display name for a playlist; Sidetrack shows the service name. */
        const val PLAYLIST_OWNER = "Jellyfin"
    }
}

/** Jellyfin's quality mapping (D4): Normal caps at 96, Max at 320, High leaves it to the server. */
internal fun AudioQuality.jellyfinBitrateKbps(): Int? = when (this) {
    AudioQuality.Normal -> 96
    AudioQuality.High -> null
    AudioQuality.VeryHigh -> 320
}

internal fun JellyfinItem.artUrl(client: JellyfinApi): String? = imageTag?.let { client.imageUrl(artItemId, it) }

/** A hint without a primary image tag names an item whose artwork the server does not have. */
internal fun JellyfinSearchHint.artUrl(client: JellyfinApi): String? = imageTag?.let { client.imageUrl(imageItemId ?: itemId, it) }

internal fun JellyfinItem.toTrack(client: JellyfinApi): Track = Track(
    id = id,
    title = name,
    artist = albumArtist ?: artists.firstOrNull().orEmpty(),
    artistId = albumArtistId ?: artistIds.firstOrNull().orEmpty(),
    albumId = albumId.orEmpty(),
    artRes = 0,
    durationMs = (runTimeTicks ?: 0L).ticksToMs(),
    artUrl = artUrl(client),
    service = ServiceId.Jellyfin
)

internal fun JellyfinItem.toAlbum(client: JellyfinApi): Album = Album(
    id = id,
    title = name,
    artist = albumArtist ?: artists.joinToString(", "),
    artistId = albumArtistId ?: artistIds.firstOrNull().orEmpty(),
    year = productionYear ?: 0,
    artRes = 0,
    tracks = emptyList(),
    artUrl = artUrl(client),
    trackCount = childCount ?: recursiveItemCount ?: 0,
    service = ServiceId.Jellyfin
)

internal fun JellyfinItem.toArtist(client: JellyfinApi): Artist = Artist(
    id = id,
    name = name,
    artRes = 0,
    albumIds = emptyList(),
    artUrl = artUrl(client),
    albumCount = childCount ?: recursiveItemCount ?: 0,
    service = ServiceId.Jellyfin
)

/**
 * Whether the UI offers editing on a Jellyfin playlist.
 *
 * The server does not answer it: a playlist's own creator comes back with `OpenAccess:false` and
 * `Shares:[]` (measured on 12.4), and no field names the owner, so `openAccess`/`shares` can only
 * ever say "no". Every playlist is therefore offered as editable — the library list has always
 * done so — and the server still rejects a write the account may not make, which the UI reports.
 */
private const val PLAYLIST_EDITABLE = true

internal fun JellyfinItem.toPlaylist(client: JellyfinApi, editable: Boolean = PLAYLIST_EDITABLE): Playlist = Playlist(
    id = id,
    name = name,
    owner = "Jellyfin",
    ownerId = id,
    artRes = 0,
    tracks = emptyList(),
    artUrl = artUrl(client),
    trackCount = childCount ?: recursiveItemCount ?: 0,
    editable = editable,
    service = ServiceId.Jellyfin
)

internal fun JellyfinSearchHint.toTrack(client: JellyfinApi): Track = Track(
    id = itemId,
    title = name,
    artist = albumArtist ?: artists.firstOrNull().orEmpty(),
    artistId = "",
    albumId = albumId.orEmpty(),
    artRes = 0,
    durationMs = (runTimeTicks ?: 0L).ticksToMs(),
    artUrl = artUrl(client),
    service = ServiceId.Jellyfin
)

internal fun JellyfinSearchHint.toAlbum(client: JellyfinApi): Album = Album(
    id = itemId,
    title = name,
    artist = albumArtist ?: artists.joinToString(", "),
    artistId = "",
    year = productionYear ?: 0,
    artRes = 0,
    tracks = emptyList(),
    artUrl = artUrl(client),
    trackCount = songCount ?: 0,
    service = ServiceId.Jellyfin
)

internal fun JellyfinSearchHint.toArtist(client: JellyfinApi): Artist = Artist(
    id = itemId,
    name = name,
    artRes = 0,
    albumIds = emptyList(),
    artUrl = artUrl(client),
    albumCount = songCount ?: 0,
    service = ServiceId.Jellyfin
)

internal fun JellyfinSearchHint.toPlaylist(client: JellyfinApi, editable: Boolean = PLAYLIST_EDITABLE): Playlist = Playlist(
    id = itemId,
    name = name,
    owner = "Jellyfin",
    ownerId = itemId,
    artRes = 0,
    tracks = emptyList(),
    artUrl = artUrl(client),
    trackCount = songCount ?: 0,
    editable = editable,
    service = ServiceId.Jellyfin
)
