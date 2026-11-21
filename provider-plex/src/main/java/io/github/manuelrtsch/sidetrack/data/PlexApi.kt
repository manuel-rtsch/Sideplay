package io.github.manuelrtsch.sidetrack.data

import android.util.Log
import io.github.manuelrtsch.sidetrack.auth.PlexCredentials
import io.github.manuelrtsch.sidetrack.plex.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Plex operations Sidetrack needs, in Sidetrack's vocabulary.
 *
 * A fake implements this for catalog tests; [HttpPlexApi] is the only class that speaks the Plex
 * wire format. Music libraries are the only sections considered: Plex calls them `artist` sections.
 */
interface PlexApi {
    val baseUrl: String
    val headers: Map<String, String>

    suspend fun musicSections(): List<PlexSection>
    suspend fun tracks(sectionKey: String, offset: Int, limit: Int, likedOnly: Boolean = false): PlexPage
    suspend fun albums(sectionKey: String, offset: Int, limit: Int): PlexPage
    suspend fun artists(sectionKey: String, offset: Int, limit: Int): PlexPage
    suspend fun playlists(offset: Int, limit: Int): PlexPage
    suspend fun playlistItems(playlistKey: String, offset: Int, limit: Int): PlexPage
    suspend fun item(ratingKey: String): PlexItem?
    suspend fun albumTracks(albumKey: String, offset: Int, limit: Int): PlexPage
    suspend fun artistAlbums(artistKey: String, offset: Int, limit: Int): PlexPage
    suspend fun artistTracks(artistKey: String, offset: Int, limit: Int): PlexPage
    suspend fun search(query: String, limit: Int): PlexSearchResult

    suspend fun setRating(ratingKey: String, rating: Int)
    suspend fun addToPlaylist(playlistKey: String, ratingKey: String)
    suspend fun removeFromPlaylist(playlistKey: String, playlistItemId: String)

    suspend fun machineIdentifier(): String?
    suspend fun reportTimeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long)
    suspend fun reportScrobble(ratingKey: String)

    /** The direct file URL for one media part. Plex authenticates the request through [headers]. */
    fun streamUrl(partKey: String): String

    /** A relative Plex thumb path resolved against the server. Null when the item has no art. */
    fun artUrl(thumb: String?): String?
}

/** The one request seam, so the wire client can be tested without a live Plex server. */
interface PlexTransport {
    suspend fun get(url: String): String
    suspend fun send(method: String, url: String, body: String? = null): String?
}

/**
 * The HTTP Plex client.
 *
 * Plex answers JSON when asked, so the adapter reads `MediaContainer` directly instead of depending
 * on a generated SDK. The server URL and the token come from [PlexCredentials]; artwork and audio
 * both carry the same headers, which [ArtworkAccess] installs once for the app.
 */
class HttpPlexApi(
    private val credentials: PlexCredentials,
    private val transport: PlexTransport? = null
) : PlexApi {
    private val base = normalizePlexServerUrl(credentials.serverUrl)

    override val baseUrl: String get() = base

    override val headers: Map<String, String> = buildMap {
        put("X-Plex-Token", credentials.token)
        put("X-Plex-Client-Identifier", credentials.clientIdentifier)
        put("X-Plex-Product", PLEX_PRODUCT)
        put("X-Plex-Version", BuildConfig.VERSION_NAME)
        put("X-Plex-Platform", PLEX_PLATFORM)
        put("X-Plex-Device", PLEX_DEVICE)
        put("Accept", "application/json")
    }

    private val wire: PlexTransport = transport ?: HttpPlexTransport(headers)

    override suspend fun musicSections(): List<PlexSection> {
        val container = container(wire.get("$base/library/sections"))
        val directories = container.optJSONArray("Directory") ?: JSONArray()
        return (0 until directories.length()).mapNotNull { index ->
            val item = directories.optJSONObject(index) ?: return@mapNotNull null
            val type = item.stringOrNull("type").orEmpty()
            if (type != "artist") return@mapNotNull null
            PlexSection(
                key = item.stringOrNull("key").orEmpty(),
                title = item.stringOrNull("title").orEmpty(),
                type = type
            )
        }
    }

    override suspend fun tracks(sectionKey: String, offset: Int, limit: Int, likedOnly: Boolean): PlexPage {
        val path = buildString {
            append("$base/library/sections/")
            append(encode(sectionKey))
            append("/all?type=10")
            append("&X-Plex-Container-Start=")
            append(offset)
            append("&X-Plex-Container-Size=")
            append(limit)
            // Plex's filter language bakes the operator into the parameter name. `%3E` is the
            // encoding the server accepts; a fully encoded `%3D` is rejected.
            if (likedOnly) append("&userRating%3E=8")
        }
        return page(wire.get(path))
    }

    override suspend fun albums(sectionKey: String, offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/library/sections/${encode(sectionKey)}/all?type=9&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun artists(sectionKey: String, offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/library/sections/${encode(sectionKey)}/all?type=8&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun playlists(offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/playlists?playlistType=audio&X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun playlistItems(playlistKey: String, offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/playlists/${encode(playlistKey)}/items?X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun item(ratingKey: String): PlexItem? {
        val container = container(wire.get("$base/library/metadata/${encode(ratingKey)}"))
        return container.optJSONArray("Metadata")?.optJSONObject(0)?.toItem()
    }

    override suspend fun albumTracks(albumKey: String, offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/library/metadata/${encode(albumKey)}/children?X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun artistAlbums(artistKey: String, offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/library/metadata/${encode(artistKey)}/children?X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun artistTracks(artistKey: String, offset: Int, limit: Int): PlexPage = page(
        wire.get("$base/library/metadata/${encode(artistKey)}/allLeaves?X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit")
    )

    override suspend fun search(query: String, limit: Int): PlexSearchResult {
        val container = container(wire.get("$base/hubs/search?query=${encode(query)}&limit=$limit"))
        val hubs = container.optJSONArray("Hub") ?: JSONArray()
        val result = PlexSearchResult()
        var tracks = result.tracks
        var artists = result.artists
        var albums = result.albums
        var playlists = result.playlists
        for (index in 0 until hubs.length()) {
            val hub = hubs.optJSONObject(index) ?: continue
            val metadata = hub.optJSONArray("Metadata") ?: continue
            val items = (0 until metadata.length()).mapNotNull { metadata.optJSONObject(it)?.toItem() }
            when (hub.stringOrNull("type")) {
                "track" -> tracks = items
                "artist" -> artists = items
                "album" -> albums = items
                "playlist" -> playlists = items
            }
        }
        return PlexSearchResult(tracks, artists, albums, playlists)
    }

    override suspend fun setRating(ratingKey: String, rating: Int) {
        wire.send(
            "PUT",
            "$base/:/rate?key=${encode(ratingKey)}&identifier=$PLEX_IDENTIFIER&rating=$rating"
        )
    }

    override suspend fun addToPlaylist(playlistKey: String, ratingKey: String) {
        val machine = machineIdentifier()
            ?: throw ServiceException(ServiceFailure.Unknown, "Plex did not return a server identifier")
        val uri = "$PLEX_SERVER_SCHEME://$machine/$PLEX_IDENTIFIER/library/metadata/${encode(ratingKey)}"
        wire.send("PUT", "$base/playlists/${encode(playlistKey)}/items?uri=${encode(uri)}")
    }

    override suspend fun removeFromPlaylist(playlistKey: String, playlistItemId: String) {
        wire.send("DELETE", "$base/playlists/${encode(playlistKey)}/items/${encode(playlistItemId)}")
    }

    override suspend fun machineIdentifier(): String? {
        val container = container(wire.get("$base/identity"))
        return container.stringOrNull("machineIdentifier")
    }

    override suspend fun reportTimeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long) {
        wire.send(
            "POST",
            "$base/:/timeline?ratingKey=${encode(ratingKey)}&state=${encode(state)}&time=$timeMs&duration=$durationMs"
        )
    }

    override suspend fun reportScrobble(ratingKey: String) {
        wire.send("POST", "$base/:/scrobble?key=${encode(ratingKey)}&identifier=$PLEX_IDENTIFIER")
    }

    override fun streamUrl(partKey: String): String = if (partKey.startsWith("http")) partKey else base + partKey

    override fun artUrl(thumb: String?): String? {
        val value = thumb?.takeIf { it.isNotBlank() } ?: return null
        if (value.startsWith("http")) return value
        return base + value
    }

    private fun container(body: String): JSONObject = runCatching {
        JSONObject(body).getJSONObject("MediaContainer")
    }.getOrElse {
        throw ServiceException(ServiceFailure.Unknown, "Plex returned an unreadable response", it)
    }

    private fun page(body: String): PlexPage {
        val container = container(body)
        val items = container.optJSONArray("Metadata")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toItem() } }
            .orEmpty()
        val total = container.optInt("totalSize", container.optInt("size", items.size))
        return PlexPage(items, total, container.optInt("offset", 0))
    }

    private fun JSONObject.toItem(): PlexItem? {
        val type = stringOrNull("type").orEmpty()
        val ratingKey = stringOrNull("ratingKey") ?: return null
        val kind = when (type) {
            "track" -> PlexKind.Track
            "album" -> PlexKind.Album
            "artist" -> PlexKind.Artist
            "playlist" -> PlexKind.Playlist
            else -> PlexKind.Other
        }
        val media = optJSONArray("Media")?.optJSONObject(0)
        val part = media?.optJSONArray("Part")?.optJSONObject(0)
        return PlexItem(
            ratingKey = ratingKey,
            title = stringOrNull("title").orEmpty(),
            kind = kind,
            parentRatingKey = stringOrNull("parentRatingKey"),
            grandparentRatingKey = stringOrNull("grandparentRatingKey"),
            parentTitle = stringOrNull("parentTitle"),
            grandparentTitle = stringOrNull("grandparentTitle"),
            thumb = stringOrNull("thumb"),
            parentThumb = stringOrNull("parentThumb"),
            durationMs = optLong("duration"),
            year = optInt("year").takeIf { it > 0 },
            userRating = optDouble("userRating").takeIf { !it.isNaN() },
            viewCount = optInt("viewCount").takeIf { it > 0 },
            partKey = part?.stringOrNull("key"),
            playlistItemId = stringOrNull("playlistItemID"),
            leafCount = optInt("leafCount").takeIf { it > 0 },
            smart = optBoolean("smart", false),
            playlistType = stringOrNull("playlistType"),
            composite = stringOrNull("composite")
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val PLEX_IDENTIFIER = "com.plexapp.plugins.library"
        const val PLEX_SERVER_SCHEME = "server"
        const val PLEX_PRODUCT = "Sidetrack"
        const val PLEX_PLATFORM = "Android"
        const val PLEX_DEVICE = "Sidephone"
    }
}

/** The JVM-backed transport. Every failure is mapped to a [ServiceFailure] the UI can explain. */
class HttpPlexTransport(
    private val headers: Map<String, String>,
    private val timeoutMs: Int = 15_000
) : PlexTransport {
    override suspend fun get(url: String): String = request("GET", url, null).orEmpty()

    override suspend fun send(method: String, url: String, body: String?): String? = request(method, url, body)

    private suspend fun request(method: String, url: String, body: String?): String? = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Log.w("SidetrackPlex", "HTTP $status ${url.substringBefore('?')}")
                throw ServiceException(failureFor(status), "Plex returned HTTP $status")
            }
            text.takeIf { it.isNotBlank() }
        } catch (e: ServiceException) {
            throw e
        } catch (e: IOException) {
            throw ServiceException(ServiceFailure.Network, "Couldn't reach the Plex server", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun failureFor(status: Int): ServiceFailure = when (status) {
        401, 403 -> ServiceFailure.AuthenticationExpired
        404 -> ServiceFailure.TrackUnavailable
        429 -> ServiceFailure.RateLimited
        in 500..599 -> ServiceFailure.Unavailable
        else -> ServiceFailure.Unknown
    }
}

/**
 * Trims a typed server address into the base URL Plex expects.
 *
 * A bare host or a host:port gets `http://` and Plex's default 32400. An explicit scheme and port
 * are kept: a reverse-proxied server may sit under a path or on 443.
 */
fun normalizePlexServerUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val parsed = runCatching { URL(withScheme) }.getOrNull()
    if (parsed == null || parsed.host.isNullOrEmpty() || (parsed.protocol != "http" && parsed.protocol != "https")) {
        throw ServiceException(ServiceFailure.NotConnected, "Enter the Plex server address")
    }
    // An explicit port is already right; an https URL without one is usually a reverse proxy on
    // 443, not a Plex Media Server on 32400.
    if (parsed.port != -1 || parsed.protocol == "https") return withScheme
    val authorityStart = withScheme.indexOf("://") + 3
    val authorityEnd = withScheme.indexOf('/', authorityStart).let { if (it == -1) withScheme.length else it }
    return withScheme.substring(0, authorityEnd) + ":" + DEFAULT_PLEX_PORT + withScheme.substring(authorityEnd)
}

/** The port a default Plex Media Server listens on. */
const val DEFAULT_PLEX_PORT = 32400
