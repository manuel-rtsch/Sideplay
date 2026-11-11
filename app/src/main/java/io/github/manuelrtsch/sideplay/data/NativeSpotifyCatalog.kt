package io.github.manuelrtsch.sideplay.data

import io.github.manuelrtsch.sideplay.LibraryFilter
import io.github.manuelrtsch.sideplay.SearchScope
import io.github.manuelrtsch.sideplay.SpotifyAlbum
import io.github.manuelrtsch.sideplay.SpotifyArtist
import io.github.manuelrtsch.sideplay.SpotifyCollection
import io.github.manuelrtsch.sideplay.SpotifyCollectionType
import io.github.manuelrtsch.sideplay.SpotifyEpisode
import io.github.manuelrtsch.sideplay.SpotifyPage
import io.github.manuelrtsch.sideplay.SpotifyPlaylist
import io.github.manuelrtsch.sideplay.SpotifySearchResults
import io.github.manuelrtsch.sideplay.SpotifyShow
import io.github.manuelrtsch.sideplay.SpotifyTrack
import io.github.manuelrtsch.sideplay.bridge.SpotifyNative
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
    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): SpotifyPage<Any> {
        val array = parseArray(callText { native.catalogLibrary(filter.name, offset.toLong(), limit.toLong()) })
        val items = when (filter) {
            LibraryFilter.LikedSongs -> array.tracks()
            LibraryFilter.Playlists -> array.playlists()
            LibraryFilter.Albums -> array.albums()
            LibraryFilter.Artists -> array.artists()
            LibraryFilter.Shows -> array.shows()
        }
        return SpotifyPage.of(items, offset, limit)
    }

    override suspend fun collection(
        type: SpotifyCollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): SpotifyCollection? {
        val json = call { native.catalogCollection(type.name, id, offset.toLong(), limit.toLong()) }
        return runCatching { json.collection(type) }.getOrNull()
    }

    override suspend fun artist(id: String): SpotifyArtist? {
        val json = call { native.catalogArtist(id) }
        return runCatching { artistFromNative(json) }.getOrNull()
    }

    override suspend fun albumsBy(artist: SpotifyArtist, offset: Int, limit: Int): SpotifyPage<SpotifyAlbum> {
        val array = parseArray(callText { native.catalogAlbumsBy(artist.id, offset.toLong(), limit.toLong()) })
        return SpotifyPage.of(array.albums(), offset, limit)
    }

    override suspend fun topTracks(artist: SpotifyArtist, offset: Int, limit: Int): SpotifyPage<SpotifyTrack> {
        val array = parseArray(callText { native.catalogTopTracks(artist.id, offset.toLong(), limit.toLong()) })
        return SpotifyPage.of(array.tracks(), offset, limit)
    }

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SpotifySearchResults {
        val scopeJson = JSONArray(scopes.map { it.name }).toString()
        val json = call { native.catalogSearch(query.trim(), scopeJson, offset.toLong(), limit.toLong()) }
        return SpotifySearchResults(
            tracks = json.optJSONArray("tracks").tracks(),
            artists = json.optJSONArray("artists").artists(),
            albums = json.optJSONArray("albums").albums(),
            playlists = json.optJSONArray("playlists").playlists(),
            shows = json.optJSONArray("shows").shows()
        )
    }

    override suspend fun isTrackSaved(id: String): Boolean? = contains("collection", "spotify:track:$id")

    override suspend fun isSaved(type: SpotifyCollectionType, id: String): Boolean? = when (type) {
        SpotifyCollectionType.Album -> contains("collection", "spotify:album:$id")
        SpotifyCollectionType.Show -> contains("show", "spotify:show:$id")
        SpotifyCollectionType.Playlist -> playlistSaved(id)
    }

    override suspend fun isArtistFollowed(id: String): Boolean? = contains("artist", "spotify:artist:$id")

    override suspend fun setTrackSaved(id: String, saved: Boolean) = writeCollection("collection", "spotify:track:$id", saved)

    override suspend fun setSaved(type: SpotifyCollectionType, id: String, saved: Boolean) {
        when (type) {
            SpotifyCollectionType.Album -> writeCollection("collection", "spotify:album:$id", saved)
            SpotifyCollectionType.Show -> writeCollection("show", "spotify:show:$id", saved)
            SpotifyCollectionType.Playlist -> writePlaylist(id, saved)
        }
    }

    override suspend fun setArtistFollowed(id: String, followed: Boolean) = writeCollection("artist", "spotify:artist:$id", followed)

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
                android.util.Log.w("SideplayCatalog", error)
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

    private fun fail(failure: SpotifyFailure, message: String): Nothing = throw SpotifyGatewayException(failure, message)
}

private fun JSONArray?.tracks(): List<SpotifyTrack> = buildList {
    if (this@tracks == null) return@buildList
    for (i in 0 until this@tracks.length()) {
        this@tracks.optJSONObject(i)?.let(::trackFromNative)?.let(::add)
    }
}

private fun JSONArray?.albums(): List<SpotifyAlbum> = buildList {
    if (this@albums == null) return@buildList
    for (i in 0 until this@albums.length()) {
        this@albums.optJSONObject(i)?.let(::albumFromNative)?.let(::add)
    }
}

private fun JSONArray?.artists(): List<SpotifyArtist> = buildList {
    if (this@artists == null) return@buildList
    for (i in 0 until this@artists.length()) {
        this@artists.optJSONObject(i)?.let(::artistFromNative)?.let(::add)
    }
}

private fun JSONArray?.playlists(): List<SpotifyPlaylist> = buildList {
    if (this@playlists == null) return@buildList
    for (i in 0 until this@playlists.length()) {
        this@playlists.optJSONObject(i)?.let(::playlistFromNative)?.let(::add)
    }
}

private fun JSONArray?.shows(): List<SpotifyShow> = buildList {
    if (this@shows == null) return@buildList
    for (i in 0 until this@shows.length()) {
        this@shows.optJSONObject(i)?.let(::showFromNative)?.let(::add)
    }
}

private fun JSONObject.collection(type: SpotifyCollectionType): SpotifyCollection? {
    val id = optString("id").takeIf { it.isNotEmpty() } ?: return null
    val items = when (type) {
        SpotifyCollectionType.Show -> optJSONArray("items").episodes(null)
        SpotifyCollectionType.Album, SpotifyCollectionType.Playlist -> optJSONArray("items").tracks()
    }
    return SpotifyCollection(
        type = type,
        id = id,
        title = optString("title", "Unknown"),
        subtitle = optString("subtitle"),
        artRes = 0,
        items = items,
        artistId = optString("artistId").takeIf { it.isNotEmpty() },
        artUrl = optString("artUrl").takeIf { it.isNotEmpty() },
        totalItems = optInt("totalItems", items.size),
        hasMore = optBoolean("hasMore", false)
    )
}

private fun trackFromNative(json: JSONObject): SpotifyTrack? {
    val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
    return SpotifyTrack(
        id = id,
        title = json.optString("title", "Unknown track"),
        artist = json.optString("artist", "Unknown artist"),
        artistId = json.optString("artistId"),
        albumId = json.optString("albumId"),
        artRes = 0,
        durationMs = json.optLong("durationMs", 0L),
        explicit = json.optBoolean("explicit", false),
        playable = json.optBoolean("playable", true),
        uri = json.optString("uri").takeIf { it.isNotEmpty() },
        artUrl = json.optString("artUrl").takeIf { it.isNotEmpty() }
    )
}

private fun albumFromNative(json: JSONObject): SpotifyAlbum? {
    val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
    return SpotifyAlbum(
        id = id,
        title = json.optString("title", "Unknown album"),
        artist = json.optString("artist", "Unknown artist"),
        artistId = json.optString("artistId"),
        year = json.optInt("year", 0),
        artRes = 0,
        tracks = json.optJSONArray("tracks").tracks(),
        artUrl = json.optString("artUrl").takeIf { it.isNotEmpty() },
        trackCount = json.optInt("trackCount", 0)
    )
}

private fun artistFromNative(json: JSONObject): SpotifyArtist? {
    val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
    val albumIds = json.optJSONArray("albumIds") ?: JSONArray()
    return SpotifyArtist(
        id = id,
        name = json.optString("name", "Unknown artist"),
        artRes = 0,
        albumIds = buildList {
            for (i in 0 until albumIds.length()) albumIds.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
        },
        artUrl = json.optString("artUrl").takeIf { it.isNotEmpty() },
        albumCount = json.optInt("albumCount", albumIds.length())
    )
}

private fun playlistFromNative(json: JSONObject): SpotifyPlaylist? {
    val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
    return SpotifyPlaylist(
        id = id,
        name = json.optString("name", "Unknown playlist"),
        owner = json.optString("owner", "Spotify"),
        ownerId = json.optString("ownerId").takeIf { it.isNotEmpty() },
        artRes = 0,
        tracks = json.optJSONArray("tracks").tracks(),
        artUrl = json.optString("artUrl").takeIf { it.isNotEmpty() },
        trackCount = json.optInt("trackCount", 0)
    )
}

private fun showFromNative(json: JSONObject): SpotifyShow? {
    val id = json.optString("id").takeIf { it.isNotEmpty() } ?: return null
    return SpotifyShow(
        id = id,
        name = json.optString("name", "Unknown show"),
        publisher = json.optString("publisher", "Podcast"),
        artRes = 0,
        episodes = json.optJSONArray("episodes").episodes(null),
        artUrl = json.optString("artUrl").takeIf { it.isNotEmpty() },
        episodeCount = json.optInt("episodeCount", 0)
    )
}

private fun JSONArray?.episodes(show: SpotifyShow?): List<SpotifyEpisode> = buildList {
    if (this@episodes == null) return@buildList
    for (i in 0 until this@episodes.length()) {
        val json = this@episodes.optJSONObject(i) ?: continue
        val id = json.optString("id").takeIf { it.isNotEmpty() } ?: continue
        add(
            SpotifyEpisode(
                id = id,
                title = json.optString("title", "Unknown episode"),
                show = json.optString("show").ifEmpty { show?.name ?: "Podcast" },
                showId = json.optString("showId").ifEmpty { show?.id.orEmpty() },
                published = json.optString("published"),
                artRes = 0,
                durationMs = json.optLong("durationMs", 0L),
                video = json.optBoolean("video", false),
                uri = json.optString("uri").takeIf { it.isNotEmpty() },
                artUrl = json.optString("artUrl").takeIf { it.isNotEmpty() }
            )
        )
    }
}
