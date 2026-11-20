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
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
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

    override suspend fun removeFromPlaylist(playlistId: String, itemId: String, index: Int?) = call {
        // Jellyfin removes by playlist entry id, which is not the media item id.
        val entryId = playlistEntryId(playlistId = playlistId, itemId = itemId, index = index) ?: return@call
        api.playlistApi.removeItemFromPlaylist(playlistId = playlistId, entryIds = listOf(entryId))
        Unit
    }

    /** The id the server removes this entry by, or null when the item is no longer there. */
    private suspend fun playlistEntryId(playlistId: String, itemId: String, index: Int?): String? {
        val playlist = uuid(playlistId)
        return resolvePlaylistEntry(itemId, index, PLAYLIST_ENTRY_PAGE) { start, limit ->
            api.playlistApi.getPlaylistItems(
                playlistId = playlist,
                userId = userId,
                startIndex = start,
                limit = limit,
                fields = listOf(ItemFields.PARENT_ID)
            ).content.items.map { PlaylistEntry(entryId = it.playlistItemId, itemId = it.id?.toString()) }
        }
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
        val url = playbackStreamUrl(
            transcodingUrl = source?.transcodingUrl,
            baseUrl = api.baseUrl?.trimEnd('/').orEmpty(),
            directUrl = directPlayStreamUrl(
                audio = api.audioApi,
                itemId = uuid(itemId),
                container = source?.container,
                mediaSourceId = source?.id,
                deviceId = api.deviceInfo.id,
                playSessionId = response.playSessionId
            )
        )
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

        /** Entries per request when resolving a playlist entry id; the scan itself has no cap. */
        const val PLAYLIST_ENTRY_PAGE = 200
    }
}

/** One playlist entry as the removal scan needs it: the id to remove by, and the item it holds. */
internal data class PlaylistEntry(val entryId: String?, val itemId: String?)

/**
 * Which playlist entry to remove, scanning every page.
 *
 * The old code read the first 500 entries and then fell back to passing the *media item id* as an
 * entry id, which removes nothing — or, if the id happens to collide, the wrong entry. A playlist
 * longer than a page therefore could not be edited from its tail at all.
 *
 * [index] is the row the caller pointed at; without it the last match wins, because an append
 * leaves its copy at the end. An index that no longer holds the item is ignored rather than
 * trusted: the playlist may have changed since the caller read it.
 */
internal suspend fun resolvePlaylistEntry(
    itemId: String,
    index: Int?,
    pageSize: Int,
    loadPage: suspend (startIndex: Int, limit: Int) -> List<PlaylistEntry>
): String? {
    var start = 0
    var lastMatch: String? = null
    while (true) {
        val page = loadPage(start, pageSize)
        for ((offset, entry) in page.withIndex()) {
            if (entry.itemId != itemId) continue
            val entryId = entry.entryId ?: continue
            if (index == start + offset) return entryId
            lastMatch = entryId
        }
        if (page.size < pageSize) return lastMatch
        start += page.size
    }
}

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
