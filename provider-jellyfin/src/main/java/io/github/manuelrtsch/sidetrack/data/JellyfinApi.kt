package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import io.github.manuelrtsch.sidetrack.MediaType as SidetrackMediaType
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.auth.JellyfinCredentials
import io.github.manuelrtsch.sidetrack.auth.jellyfinFailure
import io.github.manuelrtsch.sidetrack.jellyfin.BuildConfig
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.searchApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.operations.AudioApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaType as JellyfinMediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PlaylistDto
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SearchHint
import org.jellyfin.sdk.model.api.SearchHintResult
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.TranscodingProfile

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
    val editable: Boolean,
    val ownerId: String? = null
)

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

/**
 * The Jellyfin operations Sidetrack needs, in Sidetrack's vocabulary.
 *
 * A fake implements this for catalog tests; [SdkJellyfinApi] is the only class that knows the
 * official Kotlin SDK. Failures are expected to surface as [ServiceException].
 */
interface JellyfinApi {
    suspend fun likedSongs(offset: Int, limit: Int): JellyfinPage
    suspend fun playlists(offset: Int, limit: Int): JellyfinPage
    suspend fun albums(offset: Int, limit: Int): JellyfinPage
    suspend fun favoriteArtists(offset: Int, limit: Int): JellyfinPage

    suspend fun item(id: String): JellyfinItem?
    suspend fun albumTracks(albumId: String, offset: Int, limit: Int): JellyfinPage
    suspend fun playlist(id: String): JellyfinPlaylist?
    suspend fun playlistItems(id: String, offset: Int, limit: Int): JellyfinPage
    suspend fun artistAlbums(artistId: String, offset: Int, limit: Int): JellyfinPage
    suspend fun artistTopTracks(artistId: String, offset: Int, limit: Int): JellyfinPage

    suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): JellyfinSearchResult
    suspend fun instantMix(seed: String, type: SidetrackMediaType, limit: Int): JellyfinPage

    suspend fun userData(id: String): JellyfinUserData?
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun markPlayed(id: String)

    suspend fun createPlaylist(name: String): String?
    suspend fun addToPlaylist(playlistId: String, itemId: String): Int
    suspend fun removeFromPlaylist(playlistId: String, itemId: String)

    suspend fun playback(itemId: String, maxStreamingBitrate: Int?): JellyfinPlaybackInfo
    fun imageUrl(id: String, tag: String?, maxHeight: Int = ARTWORK_MAX_HEIGHT): String

    suspend fun announceCapabilities()
    suspend fun reportStart(report: JellyfinPlaybackReport)
    suspend fun reportProgress(report: JellyfinPlaybackReport)
    suspend fun reportStopped(report: JellyfinPlaybackReport, failed: Boolean = false)

    /** The account's resume list, used once at sign-in to seed the local resume point (D13). */
    suspend fun resumeItems(limit: Int): JellyfinPage
}

/**
 * The SDK-backed seam.
 *
 * One `createJellyfin` per instance and one `ApiClient` per signed-in account; the runtime keeps
 * this instance for the session. Every call maps an SDK failure through [jellyfinFailure] so the
 * UI only ever sees a [ServiceFailure].
 */
class SdkJellyfinApi(
    context: Context,
    private val credentials: JellyfinCredentials
) : JellyfinApi {
    private val jellyfin = createJellyfin {
        this.context = context.applicationContext
        clientInfo = ClientInfo(name = CLIENT_NAME, version = BuildConfig.VERSION_NAME)
    }

    private val api = jellyfin.createApi(
        baseUrl = credentials.serverUrl,
        accessToken = credentials.accessToken,
        // 30 s is long for a search on a self-hosted box; the SDK defaults are otherwise kept.
        httpClientOptions = HttpClientOptions(
            requestTimeout = 30.seconds,
            connectTimeout = 6.seconds,
            socketTimeout = 30.seconds
        )
    )

    private val userId: UUID? = credentials.userId.takeIf { it.isNotBlank() }
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun uuid(id: String): UUID = runCatching { UUID.fromString(id) }
        .getOrElse { throw ServiceException(ServiceFailure.TrackUnavailable, "Jellyfin returned an id Sidetrack cannot read") }

    override suspend fun likedSongs(offset: Int, limit: Int): JellyfinPage = call {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            filters = listOf(ItemFilter.IS_FAVORITE),
            recursive = true,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun playlists(offset: Int, limit: Int): JellyfinPage = call {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            recursive = true,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun albums(offset: Int, limit: Int): JellyfinPage = call {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun favoriteArtists(offset: Int, limit: Int): JellyfinPage = call {
        api.artistApi.getArtists(
            userId = userId,
            isFavorite = true,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun item(id: String): JellyfinItem? = call {
        runCatching { api.libraryApi.getItem(uuid(id), userId).content }
            .getOrElse { error ->
                // A deleted album or a stale queue entry is "not there", not "the server is down".
                if ((jellyfinFailure(error).failure == ServiceFailure.NotConnected)) null else throw error
            }
            ?.toItem()
    }

    override suspend fun albumTracks(albumId: String, offset: Int, limit: Int): JellyfinPage = call {
        api.libraryApi.getItems(
            userId = userId,
            parentId = uuid(albumId),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = false,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun playlist(id: String): JellyfinPlaylist? = call {
        val detail = runCatching { api.playlistApi.getPlaylist(uuid(id)).content }
            .getOrElse { error ->
                if (jellyfinFailure(error).failure == ServiceFailure.NotConnected) return@call null
                throw error
            }
        val item = runCatching { api.libraryApi.getItem(uuid(id), userId).content.toItem() }
            .getOrNull() ?: return@call null
        detail.toPlaylist(item, userId)
    }

    override suspend fun playlistItems(id: String, offset: Int, limit: Int): JellyfinPage = call {
        api.playlistApi.getPlaylistItems(
            playlistId = uuid(id),
            userId = userId,
            startIndex = offset,
            limit = limit,
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun artistAlbums(artistId: String, offset: Int, limit: Int): JellyfinPage = call {
        api.libraryApi.getItems(
            userId = userId,
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            albumArtistIds = listOf(uuid(artistId)),
            recursive = true,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun artistTopTracks(artistId: String, offset: Int, limit: Int): JellyfinPage = call {
        // Jellyfin has no top-songs endpoint; play count is the closest honest ordering.
        api.libraryApi.getItems(
            userId = userId,
            artistIds = listOf(uuid(artistId)),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            startIndex = offset,
            limit = limit,
            sortBy = listOf(ItemSortBy.PLAY_COUNT),
            sortOrder = listOf(SortOrder.DESCENDING),
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): JellyfinSearchResult = call {
        val kinds = buildList {
            if (SearchScope.Tracks in scopes) add(BaseItemKind.AUDIO)
            if (SearchScope.Artists in scopes) add(BaseItemKind.MUSIC_ARTIST)
            if (SearchScope.Albums in scopes) add(BaseItemKind.MUSIC_ALBUM)
            if (SearchScope.Playlists in scopes) add(BaseItemKind.PLAYLIST)
        }
        if (kinds.isEmpty()) return@call JellyfinSearchResult()
        val result = api.searchApi.getSearchHints(
            startIndex = offset,
            limit = limit,
            userId = userId,
            searchTerm = query,
            includeItemTypes = kinds,
            includePeople = false,
            includeGenres = false,
            includeStudios = false,
            // Artists come from a separate bucket in the server's search, not from
            // `includeItemTypes` alone; only ask for them when the scope is selected.
            includeArtists = SearchScope.Artists in scopes
        ).content
        result.toSearchResult()
    }

    override suspend fun instantMix(seed: String, type: SidetrackMediaType, limit: Int): JellyfinPage = call {
        api.instantMixApi.getInstantMixFromItem(
            itemId = uuid(seed),
            userId = userId,
            limit = limit,
            fields = ITEM_FIELDS,
            enableUserData = true
        ).content.toPage()
    }

    override suspend fun userData(id: String): JellyfinUserData? = call {
        runCatching { api.userDataApi.getItemUserData(uuid(id), userId).content }
            .getOrElse { error ->
                if (jellyfinFailure(error).failure == ServiceFailure.NotConnected) null else throw error
            }
            ?.let {
                JellyfinUserData(
                    itemId = it.itemId.toString(),
                    isFavorite = it.isFavorite,
                    playbackPositionTicks = it.playbackPositionTicks,
                    played = it.played
                )
            }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) = call {
        if (favorite) {
            api.userDataApi.markFavoriteItem(uuid(id), userId)
        } else {
            api.userDataApi.unmarkFavoriteItem(uuid(id), userId)
        }
        Unit
    }

    override suspend fun markPlayed(id: String) = call {
        api.userDataApi.markPlayedItem(uuid(id), userId)
        Unit
    }

    override suspend fun createPlaylist(name: String): String? = call {
        // The server owns the id; the caller then resolves the playlist like any other.
        val created = api.playlistApi.createPlaylist(
            CreatePlaylistDto(
                name = name,
                ids = emptyList(),
                userId = userId,
                mediaType = JellyfinMediaType.AUDIO,
                users = emptyList(),
                isPublic = false
            )
        ).content
        created.id
    }

    override suspend fun addToPlaylist(playlistId: String, itemId: String): Int = call {
        // Jellyfin appends when no position is given; report the size before for Undo.
        val before = runCatching {
            api.playlistApi.getPlaylist(uuid(playlistId)).content.itemIds.size
        }.getOrDefault(0)
        api.playlistApi.addItemToPlaylist(
            playlistId = uuid(playlistId),
            ids = listOf(uuid(itemId)),
            userId = userId
        )
        before
    }

    override suspend fun removeFromPlaylist(playlistId: String, itemId: String) = call {
        // Jellyfin removes by playlist entry id, which is not the media item id.
        val entry = runCatching {
            api.playlistApi.getPlaylistItems(
                playlistId = uuid(playlistId),
                userId = userId,
                limit = PLAYLIST_ENTRY_SCAN,
                fields = listOf(ItemFields.PARENT_ID)
            ).content.items.firstOrNull { it.id.toString() == itemId }
        }.getOrNull()
        api.playlistApi.removeItemFromPlaylist(
            playlistId = playlistId,
            entryIds = listOf(entry?.playlistItemId ?: itemId)
        )
        Unit
    }

    override suspend fun playback(itemId: String, maxStreamingBitrate: Int?): JellyfinPlaybackInfo = call {
        val response = api.mediaInfoApi.getPostedPlaybackInfo(
            uuid(itemId),
            PlaybackInfoDto(
                userId = userId,
                maxStreamingBitrate = maxStreamingBitrate,
                deviceProfile = jellyfinMusicProfile(maxStreamingBitrate),
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
                allowAudioStreamCopy = true
            )
        ).content
        val source = response.mediaSources.firstOrNull()
        val directPlay = source?.transcodingUrl == null && source?.supportsDirectPlay == true
        val baseUrl = api.baseUrl?.trimEnd('/').orEmpty()
        val transcodingUrl = source?.transcodingUrl
        val url = if (!transcodingUrl.isNullOrBlank()) {
            if (transcodingUrl.startsWith("http")) transcodingUrl else baseUrl + transcodingUrl
        } else {
            directPlayStreamUrl(
                audio = api.audioApi,
                itemId = uuid(itemId),
                container = source?.container,
                mediaSourceId = source?.id,
                deviceId = api.deviceInfo.id,
                playSessionId = response.playSessionId
            )
        }
        val headers = buildMap {
            source?.requiredHttpHeaders?.forEach { (key, value) -> if (value != null) put(key, value) }
            putAll(credentials.headers())
        }
        JellyfinPlaybackInfo(
            url = url,
            headers = headers,
            playSessionId = response.playSessionId,
            mediaSourceId = source?.id,
            directPlay = directPlay
        )
    }

    override fun imageUrl(id: String, tag: String?, maxHeight: Int): String = api.imageApi.getItemImageUrl(
        itemId = uuid(id),
        imageType = ImageType.PRIMARY,
        maxHeight = maxHeight,
        tag = tag
    )

    override suspend fun announceCapabilities() = call {
        api.sessionApi.postFullCapabilities(
            data = ClientCapabilitiesDto(
                playableMediaTypes = listOf(JellyfinMediaType.AUDIO),
                supportedCommands = emptyList(),
                supportsMediaControl = true,
                supportsPersistentIdentifier = true,
                deviceProfile = jellyfinMusicProfile(null)
            )
        )
        Unit
    }

    override suspend fun reportStart(report: JellyfinPlaybackReport) = call {
        api.sessionApi.reportPlaybackStart(report.toStartInfo())
        Unit
    }

    override suspend fun reportProgress(report: JellyfinPlaybackReport) = call {
        api.sessionApi.reportPlaybackProgress(report.toProgressInfo())
        Unit
    }

    override suspend fun reportStopped(report: JellyfinPlaybackReport, failed: Boolean) = call {
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = uuid(report.itemId),
                mediaSourceId = report.mediaSourceId,
                playSessionId = report.playSessionId,
                positionTicks = report.positionMs.toTicks(),
                failed = failed
            )
        )
        Unit
    }

    override suspend fun resumeItems(limit: Int): JellyfinPage = call {
        api.libraryApi.getResumeItems(
            userId = userId,
            limit = limit,
            fields = ITEM_FIELDS,
            mediaTypes = listOf(JellyfinMediaType.AUDIO),
            enableUserData = true
        ).content.toPage()
    }

    /** Runs one SDK call off the main thread and maps its failure onto [ServiceException]. */
    private suspend fun <T> call(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (error: Throwable) {
            throw jellyfinFailure(error)
        }
    }

    private fun JellyfinPlaybackReport.toStartInfo() = PlaybackStartInfo(
        canSeek = true,
        itemId = uuid(itemId),
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        positionTicks = positionMs.toTicks(),
        isPaused = paused,
        isMuted = false,
        playMethod = playMethod(),
        repeatMode = RepeatMode.REPEAT_NONE,
        playbackOrder = PlaybackOrder.DEFAULT
    )

    private fun JellyfinPlaybackReport.toProgressInfo() = PlaybackProgressInfo(
        canSeek = true,
        itemId = uuid(itemId),
        mediaSourceId = mediaSourceId,
        playSessionId = playSessionId,
        positionTicks = positionMs.toTicks(),
        isPaused = paused,
        isMuted = false,
        playMethod = playMethod(),
        repeatMode = RepeatMode.REPEAT_NONE,
        playbackOrder = PlaybackOrder.DEFAULT
    )

    private fun JellyfinPlaybackReport.playMethod(): PlayMethod = if (directPlay) PlayMethod.DIRECT_PLAY else PlayMethod.TRANSCODE

    private companion object {
        const val CLIENT_NAME = "Sidetrack"
        const val PLAYLIST_ENTRY_SCAN = 500
    }
}

/**
 * The URL for a source the server let us play directly.
 *
 * `/Audio/{id}/universal` is the endpoint a `MediaSourceInfo` points at only implicitly, and on
 * Jellyfin 12 it answers **500** with an HTML error page unless it is also told the container to
 * produce — so the player is handed a web page where it expects audio and fails with "none of the
 * available extractors could read the stream". `/Audio/{id}/stream?static=true` is what Jellyfin's
 * own clients use for a direct play: it serves the original file with byte ranges intact, which
 * Media3 needs to seek. A bitrate cap never reaches this path; the server answers such a request
 * with a `transcodingUrl` instead.
 */
internal fun directPlayStreamUrl(
    audio: AudioApi,
    itemId: UUID,
    container: String?,
    mediaSourceId: String?,
    deviceId: String?,
    playSessionId: String?
): String = audio.getAudioStreamUrl(
    itemId = itemId,
    container = container,
    static = true,
    mediaSourceId = mediaSourceId,
    deviceId = deviceId,
    playSessionId = playSessionId
)

/** The direct-play containers Media3/ExoPlayer handles, plus one audio-only transcode fallback. */
internal fun jellyfinMusicProfile(maxStreamingBitrate: Int?): DeviceProfile {
    val ceiling = maxStreamingBitrate ?: UNCAPPED_BITRATE
    return DeviceProfile(
        name = "Sidetrack",
        maxStreamingBitrate = ceiling,
        maxStaticBitrate = ceiling,
        maxStaticMusicBitrate = ceiling,
        musicStreamingTranscodingBitrate = maxStreamingBitrate,
        directPlayProfiles = listOf(
            DirectPlayProfile(container = "flac", audioCodec = "flac", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "mp3", audioCodec = "mp3", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "m4a", audioCodec = "aac", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "aac", audioCodec = "aac", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "ogg", audioCodec = "vorbis", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "opus", audioCodec = "opus", type = DlnaProfileType.AUDIO),
            DirectPlayProfile(container = "wav", audioCodec = "pcm_s16le", type = DlnaProfileType.AUDIO)
        ),
        transcodingProfiles = listOf(
            TranscodingProfile(
                container = "mp3",
                type = DlnaProfileType.AUDIO,
                videoCodec = "",
                audioCodec = "mp3",
                protocol = MediaStreamProtocol.HTTP,
                conditions = emptyList()
            )
        ),
        containerProfiles = emptyList(),
        codecProfiles = emptyList(),
        subtitleProfiles = emptyList()
    )
}

/** 1411 kbit/s is CD-quality FLAC: the "no cap" choice still states a ceiling (D4). */
private const val UNCAPPED_BITRATE = 1_411_200

internal const val ARTWORK_MAX_HEIGHT = 600

/** The fields every list query asks for; without them artwork, counts and resume data are absent. */
private val ITEM_FIELDS = listOf(ItemFields.CHILD_COUNT, ItemFields.RECURSIVE_ITEM_COUNT)

private fun Long.toTicks(): Long = this * 10_000L

internal fun Long.ticksToMs(): Long = this / 10_000L

private fun BaseItemDtoQueryResult.toPage(): JellyfinPage = JellyfinPage(
    items = items.map { it.toItem() },
    total = totalRecordCount,
    startIndex = startIndex
)

private fun PlaylistDto.toPlaylist(item: JellyfinItem, userId: UUID?): JellyfinPlaylist {
    val ownShare = userId?.let { id -> shares.firstOrNull { it.userId == id } }
    return JellyfinPlaylist(
        item = item,
        itemIds = itemIds.map { it.toString() },
        editable = openAccess || ownShare?.canEdit == true,
        ownerId = shares.firstOrNull()?.userId?.toString()
    )
}

private fun SearchHintResult.toSearchResult(): JellyfinSearchResult = JellyfinSearchResult(
    hints = searchHints.map { it.toHint() },
    total = totalRecordCount
)

internal fun BaseItemDto.toItem(): JellyfinItem = JellyfinItem(
    id = id.toString(),
    name = name.orEmpty(),
    kind = type.toKind(),
    albumId = albumId?.toString(),
    album = album,
    albumArtist = albumArtist,
    artists = artists.orEmpty(),
    albumArtistId = albumArtists?.firstOrNull()?.id?.toString(),
    artistIds = artistItems.orEmpty().map { it.id.toString() },
    runTimeTicks = runTimeTicks,
    productionYear = productionYear,
    childCount = childCount,
    recursiveItemCount = recursiveItemCount,
    imageTag = imageTags?.get(ImageType.PRIMARY),
    imageItemId = null,
    isFavorite = userData?.isFavorite,
    playbackPositionTicks = userData?.playbackPositionTicks,
    playlistItemId = playlistItemId
)

internal fun SearchHint.toHint(): JellyfinSearchHint = JellyfinSearchHint(
    itemId = itemId.toString(),
    name = name,
    kind = type.toKind(),
    albumId = albumId?.toString(),
    album = album,
    albumArtist = albumArtist,
    artists = artists,
    runTimeTicks = runTimeTicks,
    productionYear = productionYear,
    imageTag = primaryImageTag,
    imageItemId = thumbImageItemId?.toString()
)

internal fun BaseItemKind.toKind(): JellyfinKind = when (this) {
    BaseItemKind.AUDIO -> JellyfinKind.Audio
    BaseItemKind.MUSIC_ALBUM -> JellyfinKind.Album
    BaseItemKind.MUSIC_ARTIST -> JellyfinKind.Artist
    BaseItemKind.PLAYLIST -> JellyfinKind.Playlist
    else -> JellyfinKind.Other
}
