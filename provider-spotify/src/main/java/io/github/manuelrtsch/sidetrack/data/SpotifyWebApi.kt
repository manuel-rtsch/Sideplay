package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.CATALOG_PAGE_SIZE
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
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.mediaIdOrNull
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
        limit: Int = CATALOG_PAGE_SIZE
    ): Page<LibraryEntry> {
        val page = { items: List<LibraryEntry>, total: Int? -> Page.of(items, offset, limit, total) }
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
        type: CollectionType,
        id: String,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): Collection? = when (type) {
        CollectionType.Album -> runCatching { albumCollection(id, offset, limit) }.getOrNull()
        CollectionType.Playlist -> runCatching { playlistCollection(id, offset, limit) }.getOrNull()
        CollectionType.Show -> runCatching { showCollection(id, offset, limit) }.getOrNull()
    }

    suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): SearchResults {
        if (query.trim().length < 2) return SearchResults()
        val types = buildList {
            if (SearchScope.Tracks in scopes) add("track")
            if (SearchScope.Artists in scopes) add("artist")
            if (SearchScope.Albums in scopes) add("album")
            if (SearchScope.Playlists in scopes) add("playlist")
            if (SearchScope.Shows in scopes) add("show")
        }
        if (types.isEmpty()) return SearchResults()
        val json = get(
            "$SEARCH?q=${enc(query.trim())}&type=${types.joinToString(",")}&limit=${limit.coerceIn(1, 50)}&offset=$offset&market=from_token",
            token()
        )
        return SearchResults(
            tracks = json.optJSONObject("tracks")?.optJSONArray("items").tracks(),
            artists = json.optJSONObject("artists")?.optJSONArray("items").artists(),
            albums = json.optJSONObject("albums")?.optJSONArray("items").albums(),
            playlists = json.optJSONObject("playlists")?.optJSONArray("items").playlists(),
            shows = json.optJSONObject("shows")?.optJSONArray("items").shows()
        )
    }

    suspend fun artist(id: String): Artist? = runCatching {
        artist(get("$ARTISTS/${enc(id)}", token()))
    }.getOrNull()

    suspend fun albumsBy(
        artist: Artist,
        offset: Int = 0,
        limit: Int = CATALOG_PAGE_SIZE
    ): Page<Album> {
        val json = runCatching {
            get(
                "$ARTISTS/${enc(artist.id)}/albums?include_groups=album,single&limit=$limit&offset=$offset&market=from_token",
                token()
            )
        }.getOrNull()
        val albums = json?.optJSONArray("items").albums().orEmpty()
        return Page.of(albums, offset, limit, json?.optInt("total")?.takeIf { it > 0 })
    }

    suspend fun topTracks(artist: Artist, offset: Int = 0, limit: Int = CATALOG_PAGE_SIZE): Page<Track> {
        val json = runCatching {
            get("$ARTISTS/${enc(artist.id)}/top-tracks?market=from_token", token())
        }.getOrNull()
        val tracks = json?.optJSONArray("tracks").tracks().orEmpty().drop(offset).take(limit)
        return Page.of(tracks, offset, limit)
    }

    suspend fun songRadio(trackId: String, limit: Int = CATALOG_PAGE_SIZE): List<Track> {
        val json = get(
            "$RECOMMENDATIONS?seed_tracks=${enc(trackId)}&limit=${limit.coerceIn(1, 100)}&market=from_token",
            token()
        )
        return json.optJSONArray("tracks").tracks()
    }

    suspend fun artistRadio(artistId: String, limit: Int = CATALOG_PAGE_SIZE): List<Track> {
        val json = get(
            "$RECOMMENDATIONS?seed_artists=${enc(artistId)}&limit=${limit.coerceIn(1, 100)}&market=from_token",
            token()
        )
        return json.optJSONArray("tracks").tracks()
    }

    /** Visible for tests: maps Spotify's search envelope without touching the network. */
    internal fun parseSearchTracks(json: JSONObject): List<Track> = json.optJSONObject("tracks")?.optJSONArray("items").tracks()

    private fun tracks(json: JSONObject): List<Track> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("track")?.let(::track)?.let(::add)
            }
        }
    }

    private suspend fun savedAlbums(offset: Int, limit: Int): Page<LibraryEntry> {
        val json = get("$ME_ALBUMS?limit=$limit&offset=$offset", token())
        val items = json.optJSONArray("items") ?: JSONArray()
        val albums = buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("album")?.let(::album)?.let(::add)
            }
        }
        return Page.of(albums, offset, limit, json.optInt("total").takeIf { it > 0 })
    }

    private suspend fun followedArtists(offset: Int, limit: Int): Page<LibraryEntry> {
        val json = get("$ME_FOLLOWING?type=artist&limit=$limit&offset=$offset", token())
        val page = json.optJSONObject("artists")
        val artists = page?.optJSONArray("items").artists().orEmpty()
        return Page.of(artists, offset, limit, page?.optInt("total")?.takeIf { it > 0 })
    }

    private suspend fun savedShows(offset: Int, limit: Int): Page<LibraryEntry> {
        val json = get("$ME_SHOWS?limit=$limit&offset=$offset", token())
        val items = json.optJSONArray("items") ?: JSONArray()
        val shows = buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.optJSONObject("show")?.let(::show)?.let(::add)
            }
        }
        return Page.of(shows, offset, limit, json.optInt("total").takeIf { it > 0 })
    }

    private suspend fun albumCollection(id: String, offset: Int, limit: Int): Collection? {
        val json = get("$ALBUMS/${enc(id)}", token())
        val album = album(json) ?: return null
        val page = get("$ALBUMS/${enc(id)}/tracks?limit=$limit&offset=$offset", token())
        val tracks = page.optJSONArray("items").tracks()
        return Collection(
            type = CollectionType.Album,
            id = album.id,
            title = album.title,
            subtitle = "${album.artist} · ${album.year} · ${album.trackCount} tracks",
            artRes = 0,
            items = tracks,
            artistId = album.artistId.mediaIdOrNull(),
            artUrl = album.artUrl,
            totalItems = album.trackCount,
            hasMore = offset + tracks.size < album.trackCount,
            service = ServiceId.Spotify
        )
    }

    private suspend fun playlistCollection(id: String, offset: Int, limit: Int): Collection? {
        val json = get("$PLAYLISTS/${enc(id)}", token())
        val playlist = playlist(json) ?: return null
        val page = get("$PLAYLISTS/${enc(id)}/tracks?limit=$limit&offset=$offset&market=from_token", token())
        val tracks = tracks(page)
        return Collection(
            type = CollectionType.Playlist,
            id = playlist.id,
            title = playlist.name,
            subtitle = "${playlist.owner} · ${playlist.trackCount} tracks",
            artRes = 0,
            items = tracks,
            artistId = playlist.ownerId.mediaIdOrNull(),
            artUrl = playlist.artUrl,
            totalItems = playlist.trackCount,
            hasMore = offset + tracks.size < playlist.trackCount,
            service = ServiceId.Spotify
        )
    }

    private suspend fun showCollection(id: String, offset: Int, limit: Int): Collection? {
        val json = get("$SHOWS/${enc(id)}", token())
        val show = show(json) ?: return null
        val page = get("$SHOWS/${enc(id)}/episodes?limit=$limit&offset=$offset&market=from_token", token())
        val items = page.optJSONArray("items") ?: JSONArray()
        val episodes = buildList(items.length()) {
            for (i in 0 until items.length()) {
                items.optJSONObject(i)?.let { episode(it, show) }?.let(::add)
            }
        }
        return Collection(
            type = CollectionType.Show,
            id = show.id.mediaIdOrNull().orEmpty(),
            title = show.name,
            subtitle = "${show.publisher} · ${show.episodeCount} episodes",
            artRes = 0,
            items = episodes,
            artUrl = show.artUrl,
            totalItems = show.episodeCount,
            hasMore = offset + episodes.size < show.episodeCount,
            description = show.description,
            service = ServiceId.Spotify
        )
    }

    private fun track(json: JSONObject): Track? {
        val id = json.optString("id").mediaIdOrNull() ?: return null
        val album = json.optJSONObject("album")
        val artist = json.optJSONArray("artists")?.optJSONObject(0)
        return Track(
            id = id,
            title = json.optString("name", "Unknown track"),
            artist = artist?.optString("name").orEmpty().ifEmpty { "Unknown artist" },
            artistId = artist?.optString("id").mediaIdOrNull().orEmpty(),
            albumId = album?.optString("id").mediaIdOrNull().orEmpty(),
            artRes = 0,
            durationMs = json.optLong("duration_ms", 0L),
            explicit = json.optBoolean("explicit", false),
            playable = json.optBoolean("is_playable", true),
            uri = json.optString("uri").takeIf { it.isNotEmpty() },
            artUrl = imageUrl(album),
            service = ServiceId.Spotify
        )
    }

    private fun episode(json: JSONObject, show: Show? = null): Episode? {
        val id = json.optString("id").mediaIdOrNull() ?: return null
        val images = imageUrl(json) ?: show?.artUrl
        return Episode(
            id = id,
            title = json.optString("name", "Unknown episode"),
            show = show?.name ?: json.optString("show").ifEmpty { "Podcast" },
            showId = show?.id ?: json.optJSONObject("show")?.optString("id").mediaIdOrNull().orEmpty(),
            published = json.optString("release_date").ifEmpty { "Podcast episode" },
            artRes = 0,
            durationMs = json.optLong("duration_ms", 0L),
            video = false,
            uri = json.optString("uri").takeIf { it.isNotEmpty() },
            artUrl = images,
            description = json.optString("description"),
            explicit = json.optBoolean("explicit", false),
            service = ServiceId.Spotify
        )
    }

    private fun album(json: JSONObject): Album? {
        val id = json.optString("id").mediaIdOrNull() ?: return null
        val artist = json.optJSONArray("artists")?.optJSONObject(0)
        val tracks = json.optJSONObject("tracks")?.optJSONArray("items").tracks()
        return Album(
            id = id,
            title = json.optString("name", "Unknown album"),
            artist = artist?.optString("name").orEmpty().ifEmpty { "Unknown artist" },
            artistId = artist?.optString("id").mediaIdOrNull().orEmpty(),
            year = json.optString("release_date").take(4).toIntOrNull() ?: 0,
            artRes = 0,
            tracks = tracks,
            artUrl = imageUrl(json),
            trackCount = json.optJSONObject("tracks")?.optInt("total", tracks.size) ?: tracks.size,
            service = ServiceId.Spotify
        )
    }

    private fun playlist(json: JSONObject): Playlist? {
        val id = json.optString("id").mediaIdOrNull() ?: return null
        val owner = json.optJSONObject("owner")
        return Playlist(
            id = id,
            name = json.optString("name", "Unknown playlist"),
            owner = owner?.optString("display_name").orEmpty().ifEmpty { "Spotify" },
            ownerId = owner?.optString("id").mediaIdOrNull(),
            artRes = 0,
            tracks = emptyList(),
            artUrl = imageUrl(json),
            trackCount = json.optJSONObject("tracks")?.optInt("total", 0) ?: 0,
            service = ServiceId.Spotify
        )
    }

    private fun show(json: JSONObject): Show? {
        val id = json.optString("id").mediaIdOrNull() ?: return null
        val artUrl = imageUrl(json)
        val base = Show(
            id = id,
            name = json.optString("name", "Unknown show"),
            publisher = json.optString("publisher").ifEmpty { "Podcast" },
            artRes = 0,
            episodes = emptyList(),
            artUrl = artUrl,
            description = json.optString("description"),
            service = ServiceId.Spotify
        )
        val episodes = json.optJSONObject("episodes")?.optJSONArray("items").episodes(this, base)
        return base.copy(
            episodes = episodes,
            episodeCount = json.optJSONObject("episodes")?.optInt("total", episodes.size) ?: episodes.size
        )
    }

    private fun artist(json: JSONObject): Artist? {
        val id = json.optString("id").mediaIdOrNull() ?: return null
        return Artist(
            id = id,
            name = json.optString("name", "Unknown artist"),
            artRes = 0,
            albumIds = emptyList(),
            artUrl = imageUrl(json),
            service = ServiceId.Spotify
        )
    }

    private fun JSONArray?.tracks(): List<Track> = buildList {
        if (this@tracks == null) return@buildList
        for (i in 0 until this@tracks.length()) {
            this@tracks.optJSONObject(i)?.let(::track)?.let(::add)
        }
    }

    private fun JSONArray?.albums(): List<Album> = buildList {
        if (this@albums == null) return@buildList
        for (i in 0 until this@albums.length()) {
            this@albums.optJSONObject(i)?.let(::album)?.let(::add)
        }
    }

    private fun JSONArray?.artists(): List<Artist> = buildList {
        if (this@artists == null) return@buildList
        for (i in 0 until this@artists.length()) {
            this@artists.optJSONObject(i)?.let(::artist)?.let(::add)
        }
    }

    private fun JSONArray?.playlists(): List<Playlist> = buildList {
        if (this@playlists == null) return@buildList
        for (i in 0 until this@playlists.length()) {
            this@playlists.optJSONObject(i)?.let(::playlist)?.let(::add)
        }
    }

    private fun JSONArray?.shows(): List<Show> = buildList {
        if (this@shows == null) return@buildList
        for (i in 0 until this@shows.length()) {
            this@shows.optJSONObject(i)?.let(::show)?.let(::add)
        }
    }

    private fun JSONArray?.episodes(api: SpotifyWebApi, show: Show): List<Episode> = buildList {
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
                    android.util.Log.w("SidetrackApi", "$url -> 429 retryAfter=${connection.getHeaderField("Retry-After")} ${text.take(300)}")
                    fail(SpotifyFailure.RateLimited, "Spotify is rate-limiting requests")
                }

                status !in 200..299 -> {
                    android.util.Log.w("SidetrackApi", "$url -> $status ${text.take(300)}")
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
        const val RECOMMENDATIONS = "$API/recommendations"
        const val TIMEOUT_MS = 15_000
    }
}
