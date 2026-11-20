package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon

@Composable
fun ArtistScreen(
    artist: Artist,
    albums: List<Album>,
    tracks: List<Track>,
    saved: SavedUiState,
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onTrack: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAlbum: (Album) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onFollow: () -> Unit,
    onRadio: () -> Unit,
    onNowPlaying: () -> Unit,
    onArtwork: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onLoadMoreTracks: () -> Unit,
    albumHasMore: Boolean,
    trackHasMore: Boolean,
    loadingMore: Boolean,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    canFollow: Boolean = true,
    canRadio: Boolean = true
) {
    val followed = artist.key.encode() in saved.followedArtistIds
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue
    )
    val back = remember { sidetrackBack("Back", onBack).first() }
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackBar(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            back
        )
    )
    SidekitScaffold(
        title = artist.name,
        modifier = Modifier.dismissRowActionsOnVerticalKey(rows.bar),
        actions = actions,
        options = buildList {
            if (canRadio) add(SideAction("artistRadio", "Artist radio", SidekitIcon.Explore, onClick = onRadio))
            add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
        }
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val playIds = remember(tracks) { tracks.mapTo(mutableSetOf()) { it.id } }
        val tracksFocus = rememberLoadMoreFocus()
        val albumsFocus = rememberLoadMoreFocus()
        // Tiles, then the "Top tracks" label, then the tracks. The album section follows the
        // tracks and their Load more row, so its base moves with the track page.
        val tracksBase = 2
        val albumsBase = tracksBase + tracks.size + (if (trackHasMore) 1 else 0) + 1
        LaunchedEffect(artist.id) {
            tracksFocus.reset()
            albumsFocus.reset()
        }
        LoadMoreFocus(tracksFocus, listState, tracks.size, loadingMore, firstItemListIndex = tracksBase)
        LoadMoreFocus(albumsFocus, listState, albums.size, loadingMore, firstItemListIndex = albumsBase)
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, rowIndex)) {
                    ContextPlayTile(
                        playIds,
                        playback.current?.id,
                        playback.isPlaying,
                        onPlay,
                        onPlayPause,
                        Modifier.weight(1f)
                    )
                    ArtworkTile(
                        artist.artRes,
                        "${artist.name} artwork",
                        Modifier.weight(1f),
                        artist.artUrl,
                        onClick = onArtwork
                    )
                    if (canFollow) {
                        IconTile(
                            if (followed) SidekitIcon.Artist else SidekitIcon.ArtistBorder,
                            if (followed) "Following" else "Follow",
                            onFollow,
                            tone = if (followed) TileTone.Library else TileTone.Neutral
                        )
                    }
                }
            }
            rowIndex += 1
            item(key = "topLabel") {
                SectionLabel("Top tracks")
            }
            rowIndex += 1
            itemsIndexed(
                tracks,
                key = { index, track -> "$index:${track.id}" },
                contentType = { _, _ -> "track" }
            ) { index, track ->
                TrackRow(
                    "$index:${track.id}",
                    track,
                    rows,
                    Modifier.listFade(listState, tracksBase + index),
                    focusRequester = tracksFocus.requesterFor(index)
                ) { onTrack(track) }
            }
            rowIndex += tracks.size
            if (trackHasMore) {
                val moreIndex = rowIndex
                item(key = "tracksMore") {
                    LoadMoreRow(
                        "Load more tracks",
                        loadingMore,
                        onLoadMore = {
                            tracksFocus.onLoadMore(tracks.size)
                            onLoadMoreTracks()
                        },
                        Modifier.listFade(listState, moreIndex),
                        focusState = tracksFocus
                    )
                }
                rowIndex += 1
            }
            item(key = "albumsLabel") {
                SectionLabel("Albums")
            }
            rowIndex += 1
            itemsIndexed(
                albums,
                key = { index, album -> "$index:${album.id}" },
                contentType = { _, _ -> "album" }
            ) { index, album ->
                LibraryRow(
                    album.artRes,
                    album.title,
                    "${album.year}" + if (album.trackCount > 0) " · ${album.trackCount} tracks" else "",
                    Modifier.listFade(listState, albumsBase + index)
                        .then(albumsFocus.rowModifier(index)),
                    artUrl = album.artUrl
                ) { onAlbum(album) }
            }
            rowIndex += albums.size
            if (albumHasMore) {
                val moreIndex = rowIndex
                item(key = "albumsMore") {
                    LoadMoreRow(
                        "Load more albums",
                        loadingMore,
                        onLoadMore = {
                            albumsFocus.onLoadMore(albums.size)
                            onLoadMoreAlbums()
                        },
                        Modifier.listFade(listState, moreIndex),
                        focusState = albumsFocus
                    )
                }
            }
        }
    }
}
