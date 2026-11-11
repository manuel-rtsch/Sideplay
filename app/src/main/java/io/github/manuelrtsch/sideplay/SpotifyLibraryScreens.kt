package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.SwipeAction
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup

@Composable
fun SideplayHomeScreen(
    state: SpotifyUiState,
    onNowPlaying: () -> Unit,
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (SpotifyTrack) -> Unit,
    onQueue: () -> Unit,
    onQueueTrack: (SpotifyTrack) -> Unit,
    onDismissNotice: () -> Unit
) {
    val recent = state.recent.take(10)
    val bar = withNotice(
        state.notice,
        onDismissNotice,
        listOf(
            SideAction("queue", "Queue", SidekitIcon.QueueMusic, onClick = onQueue),
            SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch)
        )
    )
    SidekitScaffold(
        title = "Sideplay",
        actions = bar,
        options = listOf(
            SideAction("refresh", "Refresh", SidekitIcon.Undo, onClick = onRefresh),
            SideAction("settings", "Settings", SidekitIcon.Settings, onClick = onSettings)
        )
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    NowPlayingTile(state.current, onNowPlaying, Modifier.weight(1f))
                    LibraryTile(onLibrary, Modifier.width(SidekitTheme.spacing.itemHeight))
                }
            }
            item(key = "recentLabel") { SectionLabel("Recently played") }
            if (recent.isEmpty()) {
                item(key = "empty") { EmptyMessage("Nothing played yet", hint = "Search to find your first track") }
            } else {
                itemsIndexed(recent, key = { index, track -> "$index:${track.id}" }) { index, track ->
                    SidekitSwipeRow(
                        key = track.id,
                        group = swipes,
                        modifier = Modifier.listFade(listState, index + 2),
                        action = queueAction(track, onQueueTrack)
                    ) {
                        SpotifyMediaRow(
                            track,
                            Modifier,
                            secondary = track.artist,
                            onClick = { onPlay(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T : SpotifyMediaItem> queueAction(item: T, onQueue: (T) -> Unit) = SwipeAction(
    label = "Add ${item.title} to queue",
    icon = SidekitIcon.QueueMusic,
    color = SidekitTheme.colors.primaryA1,
    armedColor = SidekitTheme.colors.primaryA2,
    onClick = { onQueue(item) }
)

@Composable
fun LibraryScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onShow: (SpotifyShow) -> Unit,
    onLoadMore: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val actions = withNotice(
        state.notice,
        onDismissNotice,
        listOf(spotifyBack("Sideplay", onBack).first(), SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch))
    )
    val filters = LibraryFilter.entries.map { filter ->
        SideAction(
            "filter.${filter.name}",
            filter.label,
            tone = if (filter == state.filter) ActionTone.Primary else ActionTone.Muted,
            onClick = { onFilter(filter) }
        )
    }
    SidekitScaffold(
        title = "Library",
        actions = actions,
        options = filters
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val first = remember { FocusRequester() }
        LaunchedEffect(state.filter) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            val items = state.libraryItems
            if (items.isEmpty()) {
                item(key = "empty") { EmptyMessage(state.filter.empty, hint = "Search to find something to save") }
            }
            itemsIndexed(items, key = { index, item -> "$index:${libraryKey(item)}" }) { index, item ->
                val modifier = Modifier.listFade(listState, index).then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                when (item) {
                    is SpotifyTrack -> SidekitSwipeRow(
                        key = item.id,
                        group = swipes,
                        modifier = modifier,
                        action = queueAction(item, onQueue)
                    ) {
                        SpotifyMediaRow(item, Modifier, secondary = item.artist, onClick = { onTrack(item) })
                    }

                    is SpotifyPlaylist -> SpotifyLibraryRow(item.artRes, item.name, "${item.owner} · ${item.trackCount} tracks", modifier, artUrl = item.artUrl) { onPlaylist(item) }

                    is SpotifyAlbum -> SpotifyLibraryRow(item.artRes, item.title, "${item.artist} · ${item.year}", modifier, artUrl = item.artUrl) { onAlbum(item) }

                    is SpotifyArtist -> SpotifyLibraryRow(item.artRes, item.name, "Artist", modifier, artUrl = item.artUrl) { onArtist(item) }

                    is SpotifyShow -> SpotifyLibraryRow(item.artRes, item.name, item.publisher, modifier, artUrl = item.artUrl) { onShow(item) }

                    else -> Unit
                }
            }
            if (state.libraryHasMore) {
                item(key = "more") {
                    PagingFooter(state.libraryMorePending, onLoadMore)
                }
            }
        }
    }
}

@Composable
fun CollectionScreen(
    collection: SpotifyCollection,
    state: SpotifyUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onItem: (Int) -> Unit,
    onSave: () -> Unit,
    onArtist: () -> Unit,
    onQueue: () -> Unit,
    onQueueItem: (SpotifyMediaItem) -> Unit,
    onNowPlaying: () -> Unit,
    onUndo: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    onDismissNotice: () -> Unit
) {
    val colors = SidekitTheme.colors
    val saved = "${collection.type.route}:${collection.id}" in state.savedCollectionKeys
    val actions = spotifyBar(state, onDismissNotice, onUndo, spotifyBack("Back", onBack).first())
    val options = buildList {
        add(
            SideAction(
                "save",
                if (saved) "Saved" else "Save",
                if (saved) SidekitIcon.Check else SidekitIcon.Bookmark,
                tone = if (saved) ActionTone.Secondary else ActionTone.Primary,
                onClick = onSave
            )
        )
        if (collection.artistId != null) add(SideAction("artist", "Artist", SidekitIcon.Artist, onClick = onArtist))
        collection.items.firstOrNull()?.let { add(SideAction("queue", "Add to queue", SidekitIcon.QueueMusic, onClick = onQueue)) }
        add(SideAction("nowPlaying", "Go to now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    }
    SidekitScaffold(title = collection.title, actions = actions, options = options) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val playFocus = remember { FocusRequester() }
        LaunchedEffect(collection.type, collection.id) { runCatching { playFocus.requestFocus() } }
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    ArtworkTile(collection.artRes, "${collection.title} artwork", Modifier.weight(1f), collection.artUrl)
                    PlayTile(onPlay, Modifier.weight(1f).focusRequester(playFocus))
                    SpotifyIconTile(
                        if (saved) SidekitIcon.Check else SidekitIcon.Bookmark,
                        if (saved) "Saved" else "Save",
                        onSave,
                        tone = if (saved) SpotifyTileTone.On else SpotifyTileTone.Neutral
                    )
                    if (collection.artistId != null) {
                        SpotifyIconTile(SidekitIcon.Artist, "Artist", onArtist)
                    }
                }
            }
            item(key = "meta") {
                SidekitText(
                    collection.subtitle,
                    Modifier.padding(start = 18.dp, end = 18.dp),
                    style = SidekitTheme.typography.caption,
                    color = colors.surfaceA6,
                    maxLines = 1
                )
            }
            itemsIndexed(collection.items, key = { index, item -> "$index:${item.id}" }) { index, item ->
                SidekitSwipeRow(
                    key = item.id,
                    group = swipes,
                    modifier = Modifier.listFade(listState, index + 2),
                    action = queueAction(item, onQueueItem)
                ) {
                    SpotifyMediaRow(
                        item,
                        Modifier,
                        index = index,
                        showArt = false,
                        secondary = if (item is SpotifyEpisode) formatPublishedDate(item.published) else item.creator,
                        onClick = { onItem(index) }
                    )
                }
            }
            if (collection.hasMore) {
                item(key = "more") {
                    PagingFooter(loadingMore, onLoadMore)
                }
            }
        }
    }
}

@Composable
fun ArtistScreen(
    artist: SpotifyArtist,
    albums: List<SpotifyAlbum>,
    tracks: List<SpotifyTrack>,
    state: SpotifyUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onFollow: () -> Unit,
    onNowPlaying: () -> Unit,
    onUndo: () -> Unit,
    onLoadMoreAlbums: () -> Unit,
    onLoadMoreTracks: () -> Unit,
    albumHasMore: Boolean,
    trackHasMore: Boolean,
    loadingMore: Boolean,
    onDismissNotice: () -> Unit
) {
    val followed = artist.id in state.followedArtistIds
    val actions = spotifyBar(state, onDismissNotice, onUndo, spotifyBack("Back", onBack).first())
    SidekitScaffold(
        title = artist.name,
        actions = actions,
        options = listOf(
            SideAction("follow", if (followed) "Following" else "Follow", tone = if (followed) ActionTone.Secondary else ActionTone.Primary, onClick = onFollow),
            SideAction("nowPlaying", "Go to now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying)
        )
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    PlayTile(onPlay, Modifier.weight(1f))
                    ArtworkTile(artist.artRes, "${artist.name} artwork", Modifier.weight(1f), artist.artUrl)
                }
            }
            item(key = "topLabel") { SectionLabel("Top tracks") }
            itemsIndexed(tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                SidekitSwipeRow(
                    key = track.id,
                    group = swipes,
                    modifier = Modifier.listFade(listState, index + 2),
                    action = queueAction(track, onQueue)
                ) {
                    SpotifyMediaRow(track, Modifier, secondary = track.artist, onClick = { onTrack(track) })
                }
            }
            if (trackHasMore) {
                item(key = "tracksMore") { PagingFooter(loadingMore, onLoadMoreTracks) }
            }
            item(key = "albumsLabel") { SectionLabel("Albums") }
            itemsIndexed(albums, key = { index, album -> "$index:${album.id}" }) { index, album ->
                SpotifyLibraryRow(
                    album.artRes,
                    album.title,
                    "${album.year}" + if (album.trackCount > 0) " · ${album.trackCount} tracks" else "",
                    Modifier.listFade(listState, index + tracks.size + 3),
                    artUrl = album.artUrl
                ) { onAlbum(album) }
            }
            if (albumHasMore) {
                item(key = "albumsMore") { PagingFooter(loadingMore, onLoadMoreAlbums) }
            }
        }
    }
}

private fun libraryKey(item: Any): String = when (item) {
    is SpotifyTrack -> "t${item.id}"
    is SpotifyPlaylist -> "p${item.id}"
    is SpotifyAlbum -> "a${item.id}"
    is SpotifyArtist -> "r${item.id}"
    is SpotifyShow -> "s${item.id}"
    else -> item.toString()
}
