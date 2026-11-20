package io.github.manuelrtsch.sidetrack.data

import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.PlaylistDto
import org.jellyfin.sdk.model.api.SearchHint
import org.jellyfin.sdk.model.api.SearchHintResult

internal fun Long.toTicks(): Long = this * 10_000L

internal fun Long.ticksToMs(): Long = this / 10_000L

internal fun BaseItemDtoQueryResult.toPage(): JellyfinPage = JellyfinPage(
    items = items.map { it.toItem() },
    total = totalRecordCount,
    startIndex = startIndex
)

internal fun PlaylistDto.toPlaylist(item: JellyfinItem, userId: UUID?): JellyfinPlaylist {
    val ownShare = userId?.let { id -> shares.firstOrNull { it.userId == id } }
    return JellyfinPlaylist(
        item = item,
        itemIds = itemIds.map { it.toString() },
        editable = openAccess || ownShare?.canEdit == true,
        ownerId = shares.firstOrNull()?.userId?.toString()
    )
}

internal fun SearchHintResult.toSearchResult(): JellyfinSearchResult = JellyfinSearchResult(
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
