package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Station
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import io.github.manuelrtsch.sidetrack.mediaIdOrNull
import io.github.manuelrtsch.sidetrack.mediaTextOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [SpotifyCatalog] over librespot's authenticated first-party endpoints.
 *
 * The public Web API shares a quota that ordinary desktop-client credentials exhaust quickly.
 * These calls use the same session that plays audio, so they avoid that quota entirely.
 */
class NativeSpotifyCatalog(
    private val native: SpotifyNative,
    private val ensureSession: suspend () -> Unit
) : SpotifyCatalog,
    SpotifyLibraryWriter {
    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<LibraryEntry> {
        val text = callText { native.catalogLibrary(filter.name, offset.toLong(), limit.toLong()) }
        // The native catalog answers with `{items, total}` so paging does not have to guess from
        // how many entries survived metadata hydration. A bare array is still accepted.
        val payload = if (text.trimStart().startsWith("{")) parseObject(text) else null
        val array = payload?.optJSONArray("items") ?: parseArray(text)
        val items = when (filter) {
            LibraryFilter.LikedSongs -> array.tracks()
            LibraryFilter.Playlists -> array.playlists()
            LibraryFilter.Albums -> array.albums()
            LibraryFilter.Artists -> array.artists()
            LibraryFilter.Shows -> array.shows()
        }
        val total = payload?.optInt("total", -1)?.takeIf { it >= 0 }
        return Page.of(items, offset, limit, total)
    }

    override suspend fun collection(
        type: CollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): Collection? {
        val json = call { native.catalogCollection(type.name, id, offset.toLong(), limit.toLong()) }
        return runCatching { json.collection(type) }.getOrNull()
    }

    override suspend fun artist(id: String): Artist? {
        val json = call { native.catalogArtist(id) }
        return runCatching { artistFromNative(json) }.getOrNull()
    }

    override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> {
        val array = parseArray(callText { native.catalogAlbumsBy(artist.id, offset.toLong(), limit.toLong()) })
        return Page.of(array.albums(), offset, limit)
    }

    override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> {
        val array = parseArray(callText { native.catalogTopTracks(artist.id, offset.toLong(), limit.toLong()) })
        return Page.of(array.tracks(), offset, limit)
    }

    override suspend fun songRadio(trackId: String, limit: Int): List<Track> = songRadioStation(trackId, limit).tracks

    override suspend fun artistRadio(artistId: String, limit: Int): List<Track> = artistRadioStation(artistId, limit).tracks

    override suspend fun songRadioStation(trackId: String, limit: Int): Station = parseStation(callText { native.catalogSongRadio(trackId, limit.toLong()) })

    override suspend fun artistRadioStation(artistId: String, limit: Int): Station = parseStation(callText { native.catalogArtistRadio(artistId, limit.toLong()) })

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SearchResults {
        val scopeJson = JSONArray(scopes.map { it.name }).toString()
        val json = call { native.catalogSearch(query.trim(), scopeJson, offset.toLong(), limit.toLong()) }
        return SearchResults(
            tracks = json.optJSONArray("tracks").tracks(),
            artists = json.optJSONArray("artists").artists(),
            albums = json.optJSONArray("albums").albums(),
            playlists = json.optJSONArray("playlists").playlists(),
            shows = json.optJSONArray("shows").shows()
        )
    }

    override suspend fun isTrackSaved(id: String): Boolean? = contains("collection", "spotify:track:$id")

    override suspend fun isSaved(type: CollectionType, id: String): Boolean? = when (type) {
        CollectionType.Album -> contains("collection", "spotify:album:$id")
        CollectionType.Show -> contains("show", "spotify:show:$id")
        CollectionType.Playlist -> playlistSaved(id)
    }

    override suspend fun isArtistFollowed(id: String): Boolean? = contains("artist", "spotify:artist:$id")

    override suspend fun setTrackSaved(id: String, saved: Boolean) = writeCollection("collection", "spotify:track:$id", saved)

    override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
        when (type) {
            CollectionType.Album -> writeCollection("collection", "spotify:album:$id", saved)
            CollectionType.Show -> writeCollection("show", "spotify:show:$id", saved)
            CollectionType.Playlist -> writePlaylist(id, saved)
        }
    }

    override suspend fun setArtistFollowed(id: String, followed: Boolean) = writeCollection("artist", "spotify:artist:$id", followed)

    override suspend fun addToPlaylist(playlistId: String, uri: String): Int? = withContext(Dispatchers.IO) {
        ensureSession()
        val text = native.playlistItemsAdd(playlistId, JSONArray(listOf(uri)).toString())
            ?: fail(SpotifyFailure.Network, "Spotify didn't accept the playlist change")
        runCatching { text.trim().toInt() }.getOrElse {
            fail(SpotifyFailure.Unknown, "Spotify returned an unreadable playlist response")
        }
    }

    override suspend fun removeFromPlaylist(playlistId: String, uri: String) {
        withContext(Dispatchers.IO) {
            ensureSession()
            if (!native.playlistItemsRemove(playlistId, JSONArray(listOf(uri)).toString())) {
                fail(SpotifyFailure.Network, "Spotify didn't accept the playlist change")
            }
        }
    }

    private suspend fun contains(set: String, uri: String): Boolean? {
        val text = callText {
            native.collectionContains(set, JSONArray(listOf(uri)).toString())
        }
        return runCatching { JSONArray(text).optBoolean(0) }.getOrNull()
    }

    private suspend fun playlistSaved(id: String): Boolean? {
        val text = callText { native.playlistSaved(id) }
        return runCatching { text.toBooleanStrict() }.getOrNull()
    }

    private suspend fun writeCollection(set: String, uri: String, saved: Boolean) {
        withContext(Dispatchers.IO) {
            ensureSession()
            if (!native.collectionWrite(set, JSONArray(listOf(uri)).toString(), !saved)) {
                fail(SpotifyFailure.Network, "Spotify didn't accept the library change")
            }
        }
    }

    private suspend fun writePlaylist(id: String, saved: Boolean) {
        withContext(Dispatchers.IO) {
            ensureSession()
            if (!native.playlistSave(id, saved)) {
                fail(SpotifyFailure.Network, "Spotify didn't accept the library change")
            }
        }
    }

    private suspend fun call(block: () -> String?): JSONObject {
        val text = callText(block)
        return runCatching { JSONObject(text) }.getOrElse {
            fail(SpotifyFailure.Unknown, "Spotify returned an unreadable catalog response")
        }
    }

    private suspend fun callText(block: () -> String?): String = withContext(Dispatchers.IO) {
        ensureSession()
        val text = block() ?: fail(SpotifyFailure.Network, "Spotify didn't return a catalog response")
        if (text.startsWith("{")) {
            val error = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
            if (error.isNotEmpty()) {
                android.util.Log.w("SidetrackCatalog", error)
                val failure = if (error.contains("SESSION", ignoreCase = true)) {
                    SpotifyFailure.AuthenticationExpired
                } else {
                    SpotifyFailure.Network
                }
                fail(failure, error)
            }
        }
        text
    }

    private fun parseArray(text: String): JSONArray = runCatching { JSONArray(text) }.getOrElse {
        fail(SpotifyFailure.Unknown, "Spotify returned an unreadable catalog response")
    }

    /**
     * The native station response is `{playlistId, tracks}`. A bare array is still accepted so an
     * older bridge (or a test fixture) keeps working; it simply has no playlist to save.
     */
    private fun parseStation(text: String): Station {
        if (!text.trimStart().startsWith("{")) return Station(parseArray(text).tracks())
        val json = parseObject(text)
        return Station(
            tracks = json.optJSONArray("tracks").tracks(),
            playlistId = json.optString("playlistId").mediaIdOrNull()
        )
    }

    private fun parseObject(text: String): JSONObject = runCatching { JSONObject(text) }.getOrElse {
        fail(SpotifyFailure.Unknown, "Spotify returned an unreadable catalog response")
    }

    private fun fail(failure: SpotifyFailure, message: String): Nothing = throw SpotifyGatewayException(failure, message)
}

private fun JSONArray?.tracks(): List<Track> = buildList {
    if (this@tracks == null) return@buildList
    for (i in 0 until this@tracks.length()) {
        this@tracks.optJSONObject(i)?.let(::trackFromNative)?.let(::add)
    }
}

private fun JSONArray?.albums(): List<Album> = buildList {
    if (this@albums == null) return@buildList
    for (i in 0 until this@albums.length()) {
        this@albums.optJSONObject(i)?.let(::albumFromNative)?.let(::add)
    }
}

private fun JSONArray?.artists(): List<Artist> = buildList {
    if (this@artists == null) return@buildList
    for (i in 0 until this@artists.length()) {
        this@artists.optJSONObject(i)?.let(::artistFromNative)?.let(::add)
    }
}

private fun JSONArray?.playlists(): List<Playlist> = buildList {
    if (this@playlists == null) return@buildList
    for (i in 0 until this@playlists.length()) {
        this@playlists.optJSONObject(i)?.let(::playlistFromNative)?.let(::add)
    }
}

private fun JSONArray?.shows(): List<Show> = buildList {
    if (this@shows == null) return@buildList
    for (i in 0 until this@shows.length()) {
        this@shows.optJSONObject(i)?.let(::showFromNative)?.let(::add)
    }
}

internal fun JSONObject.collection(type: CollectionType): Collection? {
    val id = optString("id").mediaIdOrNull() ?: return null
    val items = when (type) {
        CollectionType.Show -> optJSONArray("items").episodes(null)
        CollectionType.Album, CollectionType.Playlist -> optJSONArray("items").tracks()
    }
    return Collection(
        type = type,
        id = id,
        title = optString("title", "Unknown"),
        subtitle = optString("subtitle"),
        artRes = 0,
        items = items,
        artistId = optString("artistId").mediaIdOrNull(),
        artUrl = optString("artUrl").mediaTextOrNull(),
        totalItems = optInt("totalItems", items.size),
        hasMore = optBoolean("hasMore", false),
        description = optString("description"),
        service = ServiceId.Spotify
    )
}

private fun trackFromNative(json: JSONObject): Track? {
    val id = json.optString("id").mediaIdOrNull() ?: return null
    return Track(
        id = id,
        title = json.optString("title", "Unknown track"),
        artist = json.optString("artist", "Unknown artist"),
        artistId = json.optString("artistId").mediaIdOrNull().orEmpty(),
        albumId = json.optString("albumId").mediaIdOrNull().orEmpty(),
        artRes = 0,
        durationMs = json.optLong("durationMs", 0L),
        explicit = json.optBoolean("explicit", false),
        playable = json.optBoolean("playable", true),
        uri = json.optString("uri").takeIf { it.isNotEmpty() },
        artUrl = json.optString("artUrl").mediaTextOrNull(),
        service = ServiceId.Spotify
    )
}

private fun albumFromNative(json: JSONObject): Album? {
    val id = json.optString("id").mediaIdOrNull() ?: return null
    return Album(
        id = id,
        title = json.optString("title", "Unknown album"),
        artist = json.optString("artist", "Unknown artist"),
        artistId = json.optString("artistId").mediaIdOrNull().orEmpty(),
        year = json.optInt("year", 0),
        artRes = 0,
        tracks = json.optJSONArray("tracks").tracks(),
        artUrl = json.optString("artUrl").mediaTextOrNull(),
        trackCount = json.optInt("trackCount", 0),
        service = ServiceId.Spotify
    )
}

private fun artistFromNative(json: JSONObject): Artist? {
    val id = json.optString("id").mediaIdOrNull() ?: return null
    val albumIds = json.optJSONArray("albumIds") ?: JSONArray()
    return Artist(
        id = id,
        name = json.optString("name", "Unknown artist"),
        artRes = 0,
        albumIds = buildList {
            for (i in 0 until albumIds.length()) albumIds.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
        },
        artUrl = json.optString("artUrl").mediaTextOrNull(),
        albumCount = json.optInt("albumCount", albumIds.length()),
        service = ServiceId.Spotify
    )
}

private fun playlistFromNative(json: JSONObject): Playlist? {
    val id = json.optString("id").mediaIdOrNull() ?: return null
    return Playlist(
        id = id,
        name = json.optString("name", "Unknown playlist"),
        owner = json.optString("owner", "Spotify"),
        ownerId = json.optString("ownerId").mediaIdOrNull(),
        artRes = 0,
        tracks = json.optJSONArray("tracks").tracks(),
        artUrl = json.optString("artUrl").mediaTextOrNull(),
        trackCount = json.optInt("trackCount", 0),
        editable = json.optBoolean("editable", false),
        folder = json.optString("folder").takeIf { it.isNotEmpty() },
        service = ServiceId.Spotify
    )
}

private fun showFromNative(json: JSONObject): Show? {
    val id = json.optString("id").mediaIdOrNull() ?: return null
    return Show(
        id = id,
        name = json.optString("name", "Unknown show"),
        publisher = json.optString("publisher", "Podcast"),
        artRes = 0,
        episodes = json.optJSONArray("episodes").episodes(null),
        artUrl = json.optString("artUrl").mediaTextOrNull(),
        episodeCount = json.optInt("episodeCount", 0),
        description = json.optString("description"),
        service = ServiceId.Spotify
    )
}

private fun JSONArray?.episodes(show: Show?): List<Episode> = buildList {
    if (this@episodes == null) return@buildList
    for (i in 0 until this@episodes.length()) {
        val json = this@episodes.optJSONObject(i) ?: continue
        val id = json.optString("id").mediaIdOrNull() ?: continue
        add(
            Episode(
                id = id,
                title = json.optString("title", "Unknown episode"),
                show = json.optString("show").ifEmpty { show?.name ?: "Podcast" },
                showId = json.optString("showId").mediaIdOrNull() ?: show?.id.orEmpty(),
                published = json.optString("published"),
                artRes = 0,
                durationMs = json.optLong("durationMs", 0L),
                video = json.optBoolean("video", false),
                uri = json.optString("uri").takeIf { it.isNotEmpty() },
                artUrl = json.optString("artUrl").mediaTextOrNull(),
                description = json.optString("description"),
                episodeNumber = json.optInt("number", 0).takeIf { it > 0 },
                explicit = json.optBoolean("explicit", false),
                service = ServiceId.Spotify
            )
        )
    }
}
