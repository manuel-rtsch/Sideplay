package io.github.manuelrtsch.sideplay.data

import io.github.manuelrtsch.sideplay.spotifyIdOrNull
import io.github.manuelrtsch.sideplay.LibraryFilter
import io.github.manuelrtsch.sideplay.SPOTIFY_PAGE_SIZE
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Spotify Web API reads the app needs. It deliberately returns UI models, not raw JSON: the
 * screens never learn an endpoint or a field name.
 */
class SpotifyWebApi(private val accessToken: suspend () -> String?) {
    suspend fun library(
        filter: LibraryFilter,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyPage<Any> {
        val page = { items: List<Any>, total: Int? -> SpotifyPage.of(items, offset, limit, total) }
        return when (filter) {
            LibraryFilter.LikedSongs -> {
                val json = get("$ME_TRACKS?limit=$limit&offset=$offset", token())
                page(tracks(json), json.optInt("total").takeIf { it > 0 })
            }

            LibraryFilter.Playlists -> {
                val json = get("$ME_PLAYLISTS?limit=$limit&offset=$offset", token())
                page(json.optJSONArray("items").playlists(), json.optInt("total").takeIf { it > 0 })
            }

            LibraryFilter.Albums -> savedAlbums(offset, limit)

            LibraryFilter.Artists -> followedArtists(offset, limit)

            LibraryFilter.Shows -> savedShows(offset, limit)
        }
    }

    suspend fun collection(
        type: SpotifyCollectionType,
        id: String,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyCollection? = when (type) {
        SpotifyCollectionType.Album -> runCatching { albumCollection(id, offset, limit) }.getOrNull()
        SpotifyCollectionType.Playlist -> runCatching { playlistCollection(id, offset, limit) }.getOrNull()
        SpotifyCollectionType.Show -> runCatching { showCollection(id, offset, limit) }.getOrNull()
    }

    suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifySearchResults {
        if (query.trim().length < 2) return SpotifySearchResults()
        val types = buildList {
            if (SearchScope.Tracks in scopes) add("track")
            if (SearchScope.Artists in scopes) add("artist")
            if (SearchScope.Albums in scopes) add("album")
            if (SearchScope.Playlists in scopes) add("playlist")
            if (SearchScope.Shows in scopes) add("show")
        }
        if (types.isEmpty()) return SpotifySearchResults()
        val json = get(
            "$SEARCH?q=${enc(query.trim())}&type=${types.joinToString(",")}&limit=${limit.coerceIn(1, 50)}&offset=$offset&market=from_token",
            token()
        )
        return SpotifySearchResults(
            tracks = json.optJSONObject("tracks")?.optJSONArray("items").tracks(),
            artists = json.optJSONObject("artists")?.optJSONArray("items").artists(),
            albums = json.optJSONObject("albums")?.optJSONArray("items").albums(),
            playlists = json.optJSONObject("playlists")?.optJSONArray("items").playlists(),
            shows = json.optJSONObject("shows")?.optJSONArray("items").shows()
        )
    }

    suspend fun artist(id: String): SpotifyArtist? = runCatching {
        artist(get("$ARTISTS/${enc(id)}", token()))
    }.getOrNull()

    suspend fun albumsBy(
        artist: SpotifyArtist,
        offset: Int = 0,
        limit: Int = SPOTIFY_PAGE_SIZE
    ): SpotifyPage<SpotifyAlbum> {
        val json = runCatching {
            get(
                "$ARTISTS/${enc(artist.id)}/albums?include_groups=album,single&limit=$limit&offset=$offset&market=from_token",
                token()
            )
        }.getOrNull()
        val albums = json?.optJSONArray("items").albums().orEmpty()
        return SpotifyPage.of(albums, offset, limit, json?.optInt("total")?.takeIf { it > 0 })
    }

    suspend fun topTracks(artist: SpotifyArtist, offset: Int = 0, limit: Int = SPOTIFY_PAGE_SIZE): SpotifyPage<SpotifyTrack> {
        val json = runCatching {
            get("$ARTISTS/${enc(artist.id)}/top-tracks?market=from_token", token())
        }.getOrNull()
        val tracks = json?.optJSONArray("tracks").tracks().orEmpty().drop(offset).take(limit)
        return SpotifyPage.of(tracks, offset, limit)
    }

    /** Visible for tests: maps Spotify's search envelope without touching the network. */
    internal fun parseSearchTracks(json: JSONObject): List<SpotifyTrack> = json.optJSONObject("tracks")?.optJSONArray("items").tracks()

    private fun tracks(json: JSONObject): List<SpotifyTrack> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("track")?.let(::track)?.let(::add)
            }
        }
    }

    private suspend fun savedAlbums(offset: Int, limit: Int): SpotifyPage<Any> {
        val json = get("$ME_ALBUMS?limit=$limit&offset=$offset", token())
        val items = json.optJSONArray("items") ?: JSONArray()
        val albums = buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("album")?.let(::album)?.let(::add)
            }
        }
        return SpotifyPage.of(albums, offset, limit, json.optInt("total").takeIf { it > 0 })
    }

    private suspend fun followedArtists(offset: Int, limit: Int): SpotifyPage<Any> {
        val json = get("$ME_FOLLOWING?type=artist&limit=$limit&offset=$offset", token())
        val page = json.optJSONObject("artists")
        val artists = page?.optJSONArray("items").artists().orEmpty()
        return SpotifyPage.of(artists, offset, limit, page?.optInt("total")?.takeIf { it > 0 })
    }

    private suspend fun savedShows(offset: Int, limit: Int): SpotifyPage<Any> {
        val json = get("$ME_SHOWS?limit=$limit&offset=$offset", token())
        val items = json.optJSONArray("items") ?: JSONArray()
        val shows = buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("show")?.let(::show)?.let(::add)
            }
        }
        return SpotifyPage.of(shows, offset, limit, json.optInt("total").takeIf { it > 0 })
    }

    private suspend fun albumCollection(id: String, offset: Int, limit: Int): SpotifyCollection? {
        val json = get("$ALBUMS/${enc(id)}", token())
        val album = album(json) ?: return null
        val page = get("$ALBUMS/${enc(id)}/tracks?limit=$limit&offset=$offset", token())
        val tracks = page.optJSONArray("items").tracks()
        return SpotifyCollection(
            type = SpotifyCollectionType.Album,
            id = album.id,
            title = album.title,
            subtitle = "${album.artist} · ${album.year} · ${album.trackCount} tracks",
            artRes = 0,
            items = tracks,
            artistId = album.artistId.spotifyIdOrNull(),
            artUrl = album.artUrl,
            totalItems = album.trackCount,
            hasMore = offset + tracks.size < album.trackCount
        )
    }

    private suspend fun playlistCollection(id: String, offset: Int, limit: Int): SpotifyCollection? {
        val json = get("$PLAYLISTS/${enc(id)}", token())
        val playlist = playlist(json) ?: return null
        val page = get("$PLAYLISTS/${enc(id)}/tracks?limit=$limit&offset=$offset&market=from_token", token())
        val tracks = tracks(page)
        return SpotifyCollection(
            type = SpotifyCollectionType.Playlist,
            id = playlist.id,
            title = playlist.name,
            subtitle = "${playlist.owner} · ${playlist.trackCount} tracks",
            artRes = 0,
            items = tracks,
            artistId = playlist.ownerId.spotifyIdOrNull(),
            artUrl = playlist.artUrl,
            totalItems = playlist.trackCount,
            hasMore = offset + tracks.size < playlist.trackCount
        )
    }

    private suspend fun showCollection(id: String, offset: Int, limit: Int): SpotifyCollection? {
        val json = get("$SHOWS/${enc(id)}", token())
        val show = show(json) ?: return null
        val page = get("$SHOWS/${enc(id)}/episodes?limit=$limit&offset=$offset&market=from_token", token())
        val items = page.optJSONArray("items") ?: JSONArray()
        val episodes = buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { episode(it, show) }?.let(::add)
            }
        }
        return SpotifyCollection(
            type = SpotifyCollectionType.Show,
            id = show.id.spotifyIdOrNull().orEmpty(),
            title = show.name,
            subtitle = "${show.publisher} · ${show.episodeCount} episodes",
            artRes = 0,
            items = episodes,
            artUrl = show.artUrl,
            totalItems = show.episodeCount,
            hasMore = offset + episodes.size < show.episodeCount
        )
    }

    private fun track(json: JSONObject): SpotifyTrack? {
        val id = json.optString("id").spotifyIdOrNull() ?: return null
        val album = json.optJSONObject("album")
        val artist = json.optJSONArray("artists")?.optJSONObject(0)
        return SpotifyTrack(
            id = id,
            title = json.optString("name", "Unknown track"),
            artist = artist?.optString("name").orEmpty().ifEmpty { "Unknown artist" },
            artistId = artist?.optString("id").spotifyIdOrNull().orEmpty(),
            albumId = album?.optString("id").spotifyIdOrNull().orEmpty(),
            artRes = 0,
            durationMs = json.optLong("duration_ms", 0L),
            explicit = json.optBoolean("explicit", false),
            playable = json.optBoolean("is_playable", true),
            uri = json.optString("uri").takeIf { it.isNotEmpty() },
            artUrl = imageUrl(album)
        )
    }

    private fun episode(json: JSONObject, show: SpotifyShow? = null): SpotifyEpisode? {
        val id = json.optString("id").spotifyIdOrNull() ?: return null
        val images = imageUrl(json) ?: show?.artUrl
        return SpotifyEpisode(
            id = id,
            title = json.optString("name", "Unknown episode"),
            show = show?.name ?: json.optString("show").ifEmpty { "Podcast" },
            showId = show?.id ?: json.optJSONObject("show")?.optString("id").spotifyIdOrNull().orEmpty(),
            published = json.optString("release_date").ifEmpty { "Podcast episode" },
            artRes = 0,
            durationMs = json.optLong("duration_ms", 0L),
            video = false,
            uri = json.optString("uri").takeIf { it.isNotEmpty() },
            artUrl = images
        )
    }

    private fun album(json: JSONObject): SpotifyAlbum? {
        val id = json.optString("id").spotifyIdOrNull() ?: return null
        val artist = json.optJSONArray("artists")?.optJSONObject(0)
        val tracks = json.optJSONObject("tracks")?.optJSONArray("items").tracks()
        return SpotifyAlbum(
            id = id,
            title = json.optString("name", "Unknown album"),
            artist = artist?.optString("name").orEmpty().ifEmpty { "Unknown artist" },
            artistId = artist?.optString("id").spotifyIdOrNull().orEmpty(),
            year = json.optString("release_date").take(4).toIntOrNull() ?: 0,
            artRes = 0,
            tracks = tracks,
            artUrl = imageUrl(json),
            trackCount = json.optJSONObject("tracks")?.optInt("total", tracks.size) ?: tracks.size
        )
    }

    private fun playlist(json: JSONObject): SpotifyPlaylist? {
        val id = json.optString("id").spotifyIdOrNull() ?: return null
        val owner = json.optJSONObject("owner")
        return SpotifyPlaylist(
            id = id,
            name = json.optString("name", "Unknown playlist"),
            owner = owner?.optString("display_name").orEmpty().ifEmpty { "Spotify" },
            ownerId = owner?.optString("id").spotifyIdOrNull(),
            artRes = 0,
            tracks = emptyList(),
            artUrl = imageUrl(json),
            trackCount = json.optJSONObject("tracks")?.optInt("total", 0) ?: 0
        )
    }

    private fun show(json: JSONObject): SpotifyShow? {
        val id = json.optString("id").spotifyIdOrNull() ?: return null
        val artUrl = imageUrl(json)
        val base = SpotifyShow(
            id = id,
            name = json.optString("name", "Unknown show"),
            publisher = json.optString("publisher").ifEmpty { "Podcast" },
            artRes = 0,
            episodes = emptyList(),
            artUrl = artUrl
        )
        val episodes = json.optJSONObject("episodes")?.optJSONArray("items").episodes(this, base)
        return base.copy(
            episodes = episodes,
            episodeCount = json.optJSONObject("episodes")?.optInt("total", episodes.size) ?: episodes.size
        )
    }

    private fun artist(json: JSONObject): SpotifyArtist? {
        val id = json.optString("id").spotifyIdOrNull() ?: return null
        return SpotifyArtist(
            id = id,
            name = json.optString("name", "Unknown artist"),
            artRes = 0,
            albumIds = emptyList(),
            artUrl = imageUrl(json)
        )
    }

    private fun JSONArray?.tracks(): List<SpotifyTrack> = buildList {
        if (this@tracks == null) return@buildList
        for (i in 0 until this@tracks.length()) {
            this@tracks.optJSONObject(i)?.let(::track)?.let(::add)
        }
    }

    private fun JSONArray?.albums(): List<SpotifyAlbum> = buildList {
        if (this@albums == null) return@buildList
        for (i in 0 until this@albums.length()) {
            this@albums.optJSONObject(i)?.let(::album)?.let(::add)
        }
    }

    private fun JSONArray?.artists(): List<SpotifyArtist> = buildList {
        if (this@artists == null) return@buildList
        for (i in 0 until this@artists.length()) {
            this@artists.optJSONObject(i)?.let(::artist)?.let(::add)
        }
    }

    private fun JSONArray?.playlists(): List<SpotifyPlaylist> = buildList {
        if (this@playlists == null) return@buildList
        for (i in 0 until this@playlists.length()) {
            this@playlists.optJSONObject(i)?.let(::playlist)?.let(::add)
        }
    }

    private fun JSONArray?.shows(): List<SpotifyShow> = buildList {
        if (this@shows == null) return@buildList
        for (i in 0 until this@shows.length()) {
            this@shows.optJSONObject(i)?.let(::show)?.let(::add)
        }
    }

    private fun JSONArray?.episodes(api: SpotifyWebApi, show: SpotifyShow): List<SpotifyEpisode> = buildList {
        if (this@episodes == null) return@buildList
        for (i in 0 until this@episodes.length()) {
            this@episodes.optJSONObject(i)?.let { api.episode(it, show) }?.let(::add)
        }
    }

    private fun imageUrl(json: JSONObject?): String? {
        val images = json?.optJSONArray("images") ?: return null
        var best: String? = null
        var bestWidth = Int.MAX_VALUE
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            val url = image.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val width = image.optInt("width", Int.MAX_VALUE)
            if (width >= 64 && width < bestWidth) {
                best = url
                bestWidth = width
            }
        }
        return best ?: images.optJSONObject(0)?.optString("url")?.takeIf { it.isNotEmpty() }
    }

    private suspend fun token(): String = accessToken() ?: fail(SpotifyFailure.AuthenticationExpired, "Sign in again")

    private suspend fun get(url: String, token: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            when {
                status == 401 -> fail(SpotifyFailure.AuthenticationExpired, "Sign in again")

                status == 429 -> {
                    android.util.Log.w("SideplayApi", "$url -> 429 retryAfter=${connection.getHeaderField("Retry-After")} ${text.take(300)}")
                    fail(SpotifyFailure.RateLimited, "Spotify is rate-limiting requests")
                }

                status !in 200..299 -> {
                    android.util.Log.w("SideplayApi", "$url -> $status ${text.take(300)}")
                    fail(SpotifyFailure.Network, "Spotify returned HTTP $status")
                }

                else -> runCatching { JSONObject(text) }
                    .getOrElse { fail(SpotifyFailure.Unknown, "Spotify returned an unreadable response") }
            }
        } catch (e: SpotifyGatewayException) {
            throw e
        } catch (e: IOException) {
            throw SpotifyGatewayException(SpotifyFailure.Network, "Couldn't reach Spotify", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun fail(failure: SpotifyFailure, message: String): Nothing = throw SpotifyGatewayException(failure, message)

    private companion object {
        const val API = "https://api.spotify.com/v1"
        const val SEARCH = "$API/search"
        const val ME_TRACKS = "$API/me/tracks"
        const val ME_PLAYLISTS = "$API/me/playlists"
        const val ME_ALBUMS = "$API/me/albums"
        const val ME_FOLLOWING = "$API/me/following"
        const val ME_SHOWS = "$API/me/shows"
        const val ALBUMS = "$API/albums"
        const val ARTISTS = "$API/artists"
        const val PLAYLISTS = "$API/playlists"
        const val SHOWS = "$API/shows"
        const val TIMEOUT_MS = 15_000
    }
}
