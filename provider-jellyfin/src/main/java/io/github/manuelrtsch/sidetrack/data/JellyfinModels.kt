package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.PlaylistActions

/** The Jellyfin object kinds Sidetrack maps onto its own domain model. */
enum class JellyfinKind { Audio, Album, Artist, Playlist, Other }

/**
 * A catalog item, reduced to the fields Sidetrack reads.
 *
 * The seam below speaks these records rather than the SDK's generated DTOs, so the catalog and
 * its tests never touch an SDK type and a hand-rolled client could replace the SDK without
 * changing the provider's behaviour. The SDK stays inside [SdkJellyfinApi].
 */
data class JellyfinItem(
    val id: String,
    val name: String,
    val kind: JellyfinKind,
    val albumId: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artists: List<String> = emptyList(),
    val albumArtistId: String? = null,
    val artistIds: List<String> = emptyList(),
    val runTimeTicks: Long? = null,
    val productionYear: Int? = null,
    val childCount: Int? = null,
    val recursiveItemCount: Int? = null,
    val imageTag: String? = null,
    val imageItemId: String? = null,
    val isFavorite: Boolean? = null,
    val playbackPositionTicks: Long? = null,
    val playlistItemId: String? = null
) {
    /** The id the artwork endpoint is asked for; hints can point at a different item than the hit. */
    val artItemId: String get() = imageItemId ?: id
}

/** One page of a Jellyfin query. `total` is the server's `totalRecordCount`. */
data class JellyfinPage(
    val items: List<JellyfinItem> = emptyList(),
    val total: Int = 0,
    val startIndex: Int = 0
)

/** A playlist's detail record: the item plus the permissions/contents only `/Playlists` reports. */
data class JellyfinPlaylist(
    val item: JellyfinItem,
    val itemIds: List<String>,
    /** The server's raw `OpenAccess`/shares answer, kept for diagnostics. It is not authoritative. */
    val editable: Boolean,
    val ownerId: String? = null
) {
    /**
     * What the UI may offer. A `false` from [editable] is not a refusal: 12.4 answers a creator's
     * own fresh playlist with `OpenAccess:false, Shares:[]`, and no field names the owner. So the
     * adapter offers editing and marks it unconfirmed; the server rejects a write it won't take.
     */
    val actions: PlaylistActions get() = if (editable) PlaylistActions.All else PlaylistActions.ServerDecides
}

/** A search hint, which carries enough metadata to build a row without a per-hit lookup. */
data class JellyfinSearchHint(
    val itemId: String,
    val name: String,
    val kind: JellyfinKind,
    val albumId: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artists: List<String> = emptyList(),
    val runTimeTicks: Long? = null,
    val productionYear: Int? = null,
    val imageTag: String? = null,
    val imageItemId: String? = null,
    val songCount: Int? = null
)

data class JellyfinSearchResult(
    val hints: List<JellyfinSearchHint> = emptyList(),
    val total: Int = 0
)

/** The signed-in user's favourite/played state for one item. */
data class JellyfinUserData(
    val itemId: String,
    val isFavorite: Boolean,
    val playbackPositionTicks: Long,
    val played: Boolean
)

/**
 * What the player needs for one item: a URL that already points at either the server's transcoder
 * or the direct stream, the headers it must carry, and the ids progress reports quote back.
 */
data class JellyfinPlaybackInfo(
    val url: String,
    val headers: Map<String, String>,
    val playSessionId: String?,
    val mediaSourceId: String?,
    val directPlay: Boolean
)

/** One progress report. Position is milliseconds; the SDK conversion to ticks stays in the adapter. */
data class JellyfinPlaybackReport(
    val itemId: String,
    val positionMs: Long,
    val mediaSourceId: String? = null,
    val playSessionId: String? = null,
    val paused: Boolean = false,
    val directPlay: Boolean = true
)
