package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.PlaylistActions
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.mediaTextOrNull
import java.security.MessageDigest
import org.json.JSONObject

internal fun md5(value: String): String = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

internal fun <T> pageOf(items: List<T>, offset: Int, limit: Int, total: Int? = null): Page<T> = Page(items = items, total = total, hasMore = if (total != null) offset + items.size < total else items.size >= limit)

internal fun JSONObject?.starred(): Boolean = this?.optString("starred").orEmpty().isNotEmpty()

internal fun JSONObject?.objects(name: String): List<JSONObject> {
    val array = this?.optJSONArray(name) ?: return emptyList()
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}

// The family member is passed in rather than assumed: two entries share this mapper, and a track
// must keep the identity of the service it was fetched through, or a queue saved under one entry
// would resolve as another entry's item after a switch.

internal fun JSONObject.toTrack(service: ServiceId, cover: (String?) -> String?): Track {
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
        artUrl = cover(optString("coverArt").mediaTextOrNull()),
        service = service
    )
}

internal fun JSONObject.toAlbum(service: ServiceId, cover: (String?) -> String?): Album {
    val songs = objects("song").map { it.toTrack(service, cover) }
    return Album(
        id = optString("id"),
        title = optString("name").ifEmpty { optString("title") },
        artist = optString("artist"),
        artistId = optString("artistId"),
        year = optInt("year", 0),
        artRes = 0,
        tracks = songs,
        artUrl = cover(optString("coverArt").mediaTextOrNull()),
        trackCount = optInt("songCount", songs.size),
        service = service
    )
}

internal fun JSONObject.toArtist(service: ServiceId, cover: (String?) -> String?): Artist {
    val albums = objects("album")
    // OpenSubsonic's artistImageUrl is already absolute; coverArt is an id this client wraps.
    val image = optString("artistImageUrl").takeIf { it.startsWith("http") }
    return Artist(
        id = optString("id"),
        name = optString("name"),
        artRes = 0,
        albumIds = albums.map { it.optString("id") },
        artUrl = image ?: cover(optString("coverArt").mediaTextOrNull()),
        albumCount = optInt("albumCount", albums.size),
        service = service
    )
}

internal fun JSONObject.toPlaylist(service: ServiceId, cover: (String?) -> String?, currentUser: String): Playlist {
    val entries = objects("entry").map { it.toTrack(service, cover) }
    val owner = optString("owner").ifEmpty { optString("username") }
    // Subsonic has no per-playlist permission flag. A playlist without an owner is one the server
    // did not describe; offer editing and let the server refuse, rather than muting a playlist
    // that may well be the account's own.
    val knownOwner = owner.isNotEmpty()
    val owned = !knownOwner || currentUser.isBlank() || owner == currentUser
    val actions = when {
        !owned -> PlaylistActions.None
        !knownOwner || currentUser.isBlank() -> PlaylistActions.ServerDecides
        else -> PlaylistActions.All
    }
    return Playlist(
        id = optString("id"),
        name = optString("name"),
        owner = owner,
        ownerId = optString("owner").takeIf { it.isNotEmpty() },
        artRes = 0,
        tracks = entries,
        artUrl = cover(optString("coverArt").mediaTextOrNull()),
        trackCount = optInt("songCount", entries.size),
        actions = actions,
        service = service
    )
}
