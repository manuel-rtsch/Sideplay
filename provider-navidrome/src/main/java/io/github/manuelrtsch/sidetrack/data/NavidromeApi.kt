package io.github.manuelrtsch.sidetrack.data

import android.util.Log
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the user typed on the Navidrome sign-in form. */
data class NavidromeCredentials(
    val serverUrl: String,
    val username: String,
    val password: String
)

/**
 * Normalises a server address into the Subsonic base URL.
 *
 * Navidrome is commonly reached as `host:4533` on a LAN, so a missing scheme becomes `http://`.
 * The trailing slash is dropped so endpoint paths join predictably.
 */
fun NavidromeCredentials.normalized(): NavidromeCredentials {
    val trimmed = serverUrl.trim().trimEnd('/')
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val parsed = runCatching { URL(withScheme) }.getOrNull()
    if (parsed == null || parsed.host.isNullOrEmpty() || (parsed.protocol != "http" && parsed.protocol != "https")) {
        throw ServiceException(ServiceFailure.NotConnected, "Enter the Navidrome server address")
    }
    return copy(serverUrl = withScheme, username = username.trim())
}

/** One HTTP call, kept as a seam so the catalog and auth tests need no real server. */
fun interface NavidromeTransport {
    suspend fun get(url: String): JSONObject
}

/**
 * The Subsonic/OpenSubsonic client Navidrome exposes at `/rest`.
 *
 * Authentication follows the Subsonic 1.13 token rule: every request gets a fresh random salt and
 * sends `t = md5(password + salt)`, so the password itself never travels over the wire. The
 * password stays in the encrypted credential store because the token cannot be reused without it.
 */
class NavidromeApi(
    credentials: NavidromeCredentials,
    private val transport: NavidromeTransport = HttpNavidromeTransport(),
    private val salt: () -> String = defaultSalt()
) {
    private val credentials = credentials.normalized()

    suspend fun ping() = call("ping")

    suspend fun artists(): List<Artist> = call("getArtists").optJSONObject("artists")
        .objects("index")
        .flatMap { it.objects("artist") }
        .map { it.toArtist { id -> coverUrl(id) } }

    suspend fun artist(id: String): Artist? = call("getArtist", "id" to id).optJSONObject("artist")?.toArtist { id -> coverUrl(id) }

    suspend fun album(id: String): Album? = call("getAlbum", "id" to id).optJSONObject("album")?.toAlbum { id -> coverUrl(id) }

    suspend fun albumList(offset: Int, limit: Int): Page<Album> = pageOf(
        call(
            "getAlbumList2",
            "type" to "alphabeticalByName",
            "offset" to offset.toString(),
            "size" to limit.toString()
        ).optJSONObject("albumList2").objects("album").map { it.toAlbum { id -> coverUrl(id) } },
        offset,
        limit
    )

    suspend fun starred(): List<LibraryEntry> {
        val starred = call("getStarred2").optJSONObject("starred2") ?: return emptyList()
        return starred.objects("song").map { it.toTrack { id -> coverUrl(id) } } +
            starred.objects("album").map { it.toAlbum { id -> coverUrl(id) } } +
            starred.objects("artist").map { it.toArtist { id -> coverUrl(id) } }
    }

    suspend fun playlists(): List<Playlist> = call("getPlaylists").optJSONObject("playlists").objects("playlist").map { it.toPlaylist { id -> coverUrl(id) } }

    suspend fun playlist(id: String): Playlist? = call("getPlaylist", "id" to id).optJSONObject("playlist")?.toPlaylist { id -> coverUrl(id) }

    suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults {
        val result = call(
            "search3",
            "query" to query,
            "artistCount" to if (SearchScope.Artists in scopes) limit.toString() else "0",
            "artistOffset" to offset.toString(),
            "albumCount" to if (SearchScope.Albums in scopes) limit.toString() else "0",
            "albumOffset" to offset.toString(),
            "songCount" to if (SearchScope.Tracks in scopes) limit.toString() else "0",
            "songOffset" to offset.toString()
        ).optJSONObject("searchResult3") ?: return SearchResults()
        return SearchResults(
            tracks = if (SearchScope.Tracks in scopes) result.objects("song").map { it.toTrack { id -> coverUrl(id) } } else emptyList(),
            artists = if (SearchScope.Artists in scopes) result.objects("artist").map { it.toArtist { id -> coverUrl(id) } } else emptyList(),
            albums = if (SearchScope.Albums in scopes) result.objects("album").map { it.toAlbum { id -> coverUrl(id) } } else emptyList(),
            playlists = if (SearchScope.Playlists in scopes) {
                // Subsonic search has no playlist scope, so filter the user's playlists locally.
                playlists().filter { it.name.contains(query, ignoreCase = true) }.drop(offset).take(limit)
            } else {
                emptyList()
            }
        )
    }

    suspend fun similarSongs(seed: MediaKey, limit: Int): List<Track> {
        val response = if (seed.type == MediaType.Artist) {
            call("getSimilarSongs2", "id" to seed.id, "count" to limit.toString())
                .optJSONObject("similarSongs2")
        } else {
            call("getSimilarSongs", "id" to seed.id, "count" to limit.toString())
                .optJSONObject("similarSongs")
        }
        return response.objects("song").map { it.toTrack { id -> coverUrl(id) } }
    }

    suspend fun randomSongs(limit: Int): Page<Track> = pageOf(
        call("getRandomSongs", "size" to limit.toString()).optJSONObject("randomSongs").objects("song").map { it.toTrack { id -> coverUrl(id) } },
        0,
        limit
    )

    suspend fun setStarred(item: MediaKey, starred: Boolean) {
        val method = if (starred) "star" else "unstar"
        call(method, item.starParameter() to item.id)
    }

    suspend fun isStarred(item: MediaKey): Boolean = when (item.type) {
        MediaType.Album -> call("getAlbum", "id" to item.id).optJSONObject("album").starred()
        MediaType.Artist -> call("getArtist", "id" to item.id).optJSONObject("artist").starred()
        else -> call("getSong", "id" to item.id).optJSONObject("song").starred()
    }

    suspend fun createPlaylist(name: String): Playlist {
        val playlist = call("createPlaylist", "name" to name).optJSONObject("playlist")
            ?.toPlaylist { coverUrl(it) }
            ?: throw ServiceException(ServiceFailure.Unknown, "Navidrome did not return the new playlist")
        return playlist
    }

    suspend fun addToPlaylist(playlistId: String, songId: String): Int {
        val before = playlist(playlistId)?.tracks?.size ?: 0
        call("updatePlaylist", "playlistId" to playlistId, "songIdToAdd" to songId)
        return before
    }

    /**
     * Removes one occurrence of [songId], by position.
     *
     * Subsonic removes by index, so which occurrence is removed is decided here. The row the caller
     * named wins when it still holds that song; otherwise the **last** match, because an append —
     * the Undo case — leaves its copy at the end. This used to be `indexOfFirst`, so with
     * `[A, B, C, A]` removing the second `A` deleted the first one: size preserved, order rotated.
     */
    suspend fun removeFromPlaylist(playlistId: String, songId: String, index: Int? = null) {
        val tracks = playlist(playlistId)?.tracks.orEmpty()
        val target = index?.takeIf { tracks.getOrNull(it)?.id == songId }
            ?: tracks.indexOfLast { it.id == songId }
        if (target < 0) return
        call("updatePlaylist", "playlistId" to playlistId, "songIndexToRemove" to target.toString())
    }

    suspend fun streamUrl(
        songId: String,
        maxBitRateKbps: Int? = null,
        replayGain: Boolean = false
    ): String {
        val params = buildList {
            add("id" to songId)
            if (maxBitRateKbps != null) add("maxBitRate" to maxBitRateKbps.toString())
            if (replayGain) add("replayGain" to "true")
        }
        return url("stream", *params.toTypedArray())
    }

    fun coverUrl(coverArt: String?, size: Int = 600): String? {
        if (coverArt.isNullOrBlank()) return null
        return url("getCoverArt", "id" to coverArt, "size" to size.toString())
    }

    private suspend fun call(method: String, vararg params: Pair<String, String>): JSONObject {
        val response = transport.get(url(method, *params))
        val envelope = response.optJSONObject("subsonic-response") ?: response
        if (envelope.optString("status", "ok") == "ok") return envelope
        val error = envelope.optJSONObject("error")
        val code = error?.optInt("code") ?: 0
        val message = error?.optString("message").orEmpty().ifEmpty { "Navidrome rejected the request" }
        throw ServiceException(code.toFailure(), message)
    }

    /** Builds a fully authenticated endpoint URL. */
    private fun url(method: String, vararg params: Pair<String, String>): String {
        val salt = salt()
        val token = md5(credentials.password + salt)
        val query = buildList {
            add("u" to credentials.username)
            add("t" to token)
            add("s" to salt)
            add("v" to API_VERSION)
            add("c" to CLIENT_NAME)
            add("f" to "json")
            addAll(params)
        }.joinToString("&") { (key, value) -> "${enc(key)}=${enc(value)}" }
        return "${credentials.serverUrl}/rest/$method?$query"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun MediaKey.starParameter(): String = when (type) {
        MediaType.Album -> "albumId"
        MediaType.Artist -> "artistId"
        else -> "id"
    }

    private fun Int.toFailure(): ServiceFailure = when (this) {
        40, 41 -> ServiceFailure.AuthenticationExpired
        50 -> ServiceFailure.AuthenticationExpired
        70 -> ServiceFailure.TrackUnavailable
        else -> ServiceFailure.Unknown
    }

    private companion object {
        const val API_VERSION = "1.16.1"
        const val CLIENT_NAME = "Sidetrack"
        const val TIMEOUT_MS = 15_000

        fun defaultSalt(): () -> String = {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** The real transport; all parsing stays in [NavidromeApi]. */
class HttpNavidromeTransport : NavidromeTransport {
    override suspend fun get(url: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Log.w("SidetrackNavidrome", "HTTP $status ${url.substringBefore('?')}")
                throw ServiceException(ServiceFailure.Network, "Navidrome returned HTTP $status")
            }
            runCatching { JSONObject(text) }
                .getOrElse { throw ServiceException(ServiceFailure.Unknown, "Navidrome returned an unreadable response", it) }
        } catch (e: ServiceException) {
            throw e
        } catch (e: IOException) {
            throw ServiceException(ServiceFailure.Network, "Couldn't reach Navidrome", e)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}

private fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun <T> pageOf(items: List<T>, offset: Int, limit: Int, total: Int? = null): Page<T> = Page(items = items, total = total, hasMore = if (total != null) offset + items.size < total else items.size >= limit)

private fun JSONObject?.starred(): Boolean = this?.optString("starred").orEmpty().isNotEmpty()

private fun JSONObject?.objects(name: String): List<JSONObject> {
    val array = this?.optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

private fun JSONObject.toTrack(cover: (String?) -> String?): Track {
    val id = optString("id")
    val albumId = optString("albumId").ifEmpty { optString("parent").ifEmpty { optString("album") } }
    return Track(
        id = id,
        title = optString("title").ifEmpty { optString("name") },
        artist = optString("artist").ifEmpty { optString("albumArtist") },
        artistId = optString("artistId"),
        albumId = albumId,
        artRes = 0,
        durationMs = optLong("duration", 0L) * 1_000L,
        uri = null,
        artUrl = cover(optString("coverArt").takeIf { it.isNotEmpty() }),
        service = ServiceId.Navidrome
    )
}

private fun JSONObject.toAlbum(cover: (String?) -> String?): Album {
    val songs = objects("song").map { it.toTrack(cover) }
    return Album(
        id = optString("id"),
        title = optString("name").ifEmpty { optString("title") },
        artist = optString("artist"),
        artistId = optString("artistId"),
        year = optInt("year", 0),
        artRes = 0,
        tracks = songs,
        artUrl = cover(optString("coverArt").takeIf { it.isNotEmpty() }),
        trackCount = optInt("songCount", songs.size),
        service = ServiceId.Navidrome
    )
}

private fun JSONObject.toArtist(cover: (String?) -> String?): Artist {
    val albums = objects("album")
    // OpenSubsonic's artistImageUrl is already absolute; coverArt is an id this client wraps.
    val image = optString("artistImageUrl").takeIf { it.startsWith("http") }
    return Artist(
        id = optString("id"),
        name = optString("name"),
        artRes = 0,
        albumIds = albums.map { it.optString("id") },
        artUrl = image ?: cover(optString("coverArt").takeIf { it.isNotEmpty() }),
        albumCount = optInt("albumCount", albums.size),
        service = ServiceId.Navidrome
    )
}

private fun JSONObject.toPlaylist(cover: (String?) -> String?): Playlist {
    val entries = objects("entry").map { it.toTrack(cover) }
    val owner = optString("owner").ifEmpty { optString("username") }
    return Playlist(
        id = optString("id"),
        name = optString("name"),
        owner = owner,
        ownerId = optString("owner").takeIf { it.isNotEmpty() },
        artRes = 0,
        tracks = entries,
        artUrl = cover(optString("coverArt").takeIf { it.isNotEmpty() }),
        trackCount = optInt("songCount", entries.size),
        editable = true,
        service = ServiceId.Navidrome
    )
}

/**
 * The read side of Navidrome behind the neutral [MediaCatalog] contract.
 *
 * Artwork and stream URLs are minted per call with a fresh token; the persisted identity is always
 * the Subsonic id inside [MediaKey], never the URL.
 */
class NavidromeCatalog(
    private val api: () -> NavidromeApi?,
    private val settings: () -> PlaybackSettings = { PlaybackSettings() }
) : MediaCatalog,
    MediaLibraryWriter {
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Navidrome

    private fun client(): NavidromeApi = api() ?: throw ServiceException(ServiceFailure.NotConnected, "Connect Navidrome in Settings")

    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = when (section) {
        LibrarySection.Liked -> {
            val all: List<LibraryEntry> = client().starred().filterIsInstance<Track>()
            pageOf<LibraryEntry>(all.drop(offset).take(limit), offset, limit, all.size)
        }

        LibrarySection.Playlists -> pageOf<LibraryEntry>(client().playlists().drop(offset).take(limit), offset, limit)

        LibrarySection.Albums -> client().albumList(offset, limit).let {
            Page<LibraryEntry>(it.items, it.total, it.hasMore)
        }

        LibrarySection.Artists -> pageOf<LibraryEntry>(client().artists().drop(offset).take(limit), offset, limit)

        LibrarySection.Shows -> Page<LibraryEntry>()
    }

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? = when (key.type) {
        MediaType.Album -> client().album(key.id)?.let { album ->
            Collection(
                type = CollectionType.Album,
                id = album.id,
                title = album.title,
                subtitle = album.artist,
                artRes = 0,
                items = album.tracks,
                artistId = album.artistId.takeIf { it.isNotEmpty() },
                artUrl = album.artUrl,
                totalItems = album.trackCount,
                saveable = true,
                service = ServiceId.Navidrome
            )
        }

        MediaType.Playlist -> client().playlist(key.id)?.let { playlist ->
            val items = playlist.tracks.drop(offset).take(limit)
            Collection(
                type = CollectionType.Playlist,
                id = playlist.id,
                title = playlist.name,
                subtitle = playlist.owner,
                artRes = 0,
                items = items,
                artUrl = playlist.artUrl,
                totalItems = playlist.trackCount,
                hasMore = offset + items.size < playlist.trackCount,
                saveable = false,
                service = ServiceId.Navidrome
            )
        }

        else -> null
    }

    override suspend fun artist(key: MediaKey): Artist? = client().artist(key.id)

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> {
        val albumIds = client().artist(artist.id)?.albumIds.orEmpty()
        val all = albumIds.drop(offset).take(limit).mapNotNull { client().album(it) }
        return pageOf(all, offset, limit, albumIds.size)
    }

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> {
        val albums = client().artist(artist.id)?.albumIds.orEmpty()
        val tracks = mutableListOf<Track>()
        var fetched = 0
        for (id in albums) {
            if (tracks.size >= offset + limit || fetched >= MAX_ARTIST_ALBUMS) break
            client().album(id)?.tracks?.let(tracks::addAll)
            fetched++
        }
        return pageOf(tracks.drop(offset).take(limit), offset, limit, tracks.size)
    }

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults = client().search(query, scopes, offset, limit)

    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource {
        if (item.service != ServiceId.Navidrome) {
            throw ServiceException(ServiceFailure.TrackUnavailable, "This item belongs to another service")
        }
        val current = settings()
        // High is the app default; leave the server free to pick its best bitrate so an
        // existing FLAC stream is not silently capped at 160 kbps. Only an explicit Normal or
        // Max choice forces a transcode ceiling. The selected ceiling applies to the next track;
        // the playing one keeps the source it started with.
        val maxBitRateKbps = when (current.audioQuality) {
            AudioQuality.Normal -> 96
            AudioQuality.High -> null
            AudioQuality.VeryHigh -> 320
        }
        return PlaybackSource(
            id = item.key.encode(),
            uri = client().streamUrl(
                item.id,
                maxBitRateKbps = maxBitRateKbps,
                replayGain = current.normalization
            )
        )
    }

    override suspend fun radio(seed: MediaKey, offset: Int, limit: Int): Page<Track> {
        val tracks = runCatching { client().similarSongs(seed, offset + limit) }
            .getOrElse {
                // Similar songs needs Last.fm on the server; random songs keep radio usable without it.
                if (it is ServiceException && it.failure == ServiceFailure.Unknown) emptyList() else throw it
            }
            .drop(offset)
            .take(limit)
        return if (tracks.isNotEmpty()) pageOf(tracks, offset, limit) else client().randomSongs(limit)
    }

    override suspend fun isSaved(item: MediaKey): Boolean? = runCatching {
        client().isStarred(item)
    }.getOrNull()

    override suspend fun isFollowed(artist: MediaKey): Boolean? = null

    override suspend fun setSaved(item: MediaKey, saved: Boolean) {
        if (item.type == MediaType.Playlist || item.type == MediaType.Show) return
        client().setStarred(item, saved)
    }

    override suspend fun setFollowed(artist: MediaKey, followed: Boolean) = Unit

    override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? = client().addToPlaylist(playlist.id, item.id)

    override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
        client().removeFromPlaylist(playlist.id, item.id, index)
    }

    override suspend fun createPlaylist(name: String): Playlist? = runCatching {
        client().createPlaylist(name)
    }.getOrNull()

    private companion object {
        const val MAX_ARTIST_ALBUMS = 25
    }
}
