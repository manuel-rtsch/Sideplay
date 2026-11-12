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
import io.github.manuelrtsch.sideplay.ui.SwipeRevealGroup
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup

@Composable
fun SideplayHomeScreen(
    state: SpotifyUiState,
    onNowPlaying: () -> Unit,
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (SpotifyMediaItem) -> Unit,
    onQueue: () -> Unit,
    onQueueTrack: (SpotifyMediaItem) -> Unit,
    onHistory: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val recent = state.recent.take(RECENT_PREVIEW_SIZE)
    val hasMoreHistory = state.recent.size > RECENT_PREVIEW_SIZE
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
                itemsIndexed(recent, key = { index, item -> "$index:${item.id}" }) { index, item ->
                    SidekitSwipeRow(
                        key = item.id,
                        group = swipes,
                        modifier = Modifier.listFade(listState, index + 2),
                        action = queueAction(item, onQueueTrack)
                    ) {
                        SpotifyMediaRow(
                            item,
                            Modifier,
                            secondary = item.creator,
                            onClick = { onPlay(item) }
                        )
                    }
                }
            }
            if (hasMoreHistory) {
                val moreIndex = recent.size + 2
                item(key = "moreHistory") {
                    SeeMoreRow(
                        "See more history",
                        loading = false,
                        onClick = onHistory,
                        modifier = Modifier.listFade(listState, moreIndex)
                    )
                }
            }
        }
    }
}

@Composable
internal fun <T : SpotifyMediaItem> queueAction(item: T, onQueue: (T) -> Unit) = SwipeAction(
    label = "Add ${item.title} to queue",
    icon = SidekitIcon.QueueMusic,
    color = SidekitTheme.colors.successA1,
    armedColor = SidekitTheme.colors.successA2,
    onClick = { onQueue(item) }
)

/**
 * The Library overview: a short preview of every category with a "See more" row, instead of
 * hiding the categories behind five Options buttons.
 */
@Composable
fun LibraryScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSeeMore: (LibraryFilter) -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onShow: (SpotifyShow) -> Unit,
    onDismissNotice: () -> Unit
) {
    val actions = withNotice(
        state.notice,
        onDismissNotice,
        listOf(spotifyBack("Sideplay", onBack).first(), SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch))
    )
    SidekitScaffold(title = "Library", actions = actions) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val sections = state.librarySections
        val isEmpty = sections.values.all { it.isEmpty() }
        val firstFilter = LibraryFilter.entries.firstOrNull { sections[it].orEmpty().isNotEmpty() }
        val first = remember { FocusRequester() }
        LaunchedEffect(firstFilter) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            if (isEmpty) {
                val index = rowIndex
                item(key = "empty") {
                    if (state.librarySectionsLoading) {
                        EmptyMessage(
                            "Loading your library…",
                            Modifier.listFade(listState, index),
                            hint = "Liked songs, playlists, albums, artists and shows"
                        )
                    } else {
                        EmptyMessage(
                            "Your library is empty",
                            Modifier.listFade(listState, index),
                            hint = "Search to find something to save"
                        )
                    }
                }
                rowIndex += 1
            }
            LibraryFilter.entries.forEach { filter ->
                val items = sections[filter].orEmpty()
                if (items.isEmpty()) return@forEach
                val labelIndex = rowIndex
                item(key = "label.${filter.name}") {
                    SectionLabel(filter.label, Modifier.listFade(listState, labelIndex))
                }
                rowIndex += 1
                val base = rowIndex
                itemsIndexed(items, key = { index, item -> "${filter.name}:$index:${libraryKey(item)}" }) { index, item ->
                    val modifier = Modifier.listFade(listState, base + index)
                        .then(
                            if (filter == firstFilter && index == 0) Modifier.focusRequester(first) else Modifier
                        )
                    LibraryRow(
                        item,
                        modifier,
                        swipes,
                        onTrack,
                        onQueue,
                        onPlaylist,
                        onAlbum,
                        onArtist,
                        onShow
                    )
                }
                rowIndex += items.size
                if (filter in state.librarySectionHasMore) {
                    val moreIndex = rowIndex
                    item(key = "more.${filter.name}") {
                        SeeMoreRow(
                            "See more ${filter.label.lowercase()}",
                            loading = false,
                            onClick = { onSeeMore(filter) },
                            modifier = Modifier.listFade(listState, moreIndex)
                        )
                    }
                    rowIndex += 1
                }
            }
        }
    }
}

/** One category of the library, opened by a "See more" row on the overview. */
@Composable
fun LibraryCategoryScreen(
    filter: LibraryFilter,
    state: SpotifyUiState,
    onBack: () -> Unit,
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
        listOf(spotifyBack("Library", onBack).first())
    )
    SidekitScaffold(title = filter.label, actions = actions) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val first = remember { FocusRequester() }
        LaunchedEffect(filter) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            val items = state.libraryItems
            var rowIndex = 0
            if (items.isEmpty()) {
                val index = rowIndex
                item(key = "empty") {
                    if (state.libraryLoading) {
                        EmptyMessage(
                            "Loading ${filter.label.lowercase()}…",
                            Modifier.listFade(listState, index),
                            hint = null
                        )
                    } else {
                        EmptyMessage(
                            filter.empty,
                            Modifier.listFade(listState, index),
                            hint = "Search to find something to save"
                        )
                    }
                }
                rowIndex += 1
            }
            val base = rowIndex
            itemsIndexed(items, key = { index, item -> "$index:${libraryKey(item)}" }) { index, item ->
                val modifier = Modifier.listFade(listState, base + index)
                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                LibraryRow(item, modifier, swipes, onTrack, onQueue, onPlaylist, onAlbum, onArtist, onShow)
            }
            rowIndex += items.size
            if (state.libraryHasMore) {
                val moreIndex = rowIndex
                item(key = "more") {
                    PagingFooter(state.libraryMorePending, onLoadMore, Modifier.listFade(listState, moreIndex))
                }
            }
        }
    }
}

@Composable
private fun LibraryRow(
    item: Any,
    modifier: Modifier,
    swipes: SwipeRevealGroup,
    onTrack: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onShow: (SpotifyShow) -> Unit
) {
    when (item) {
        is SpotifyTrack -> SidekitSwipeRow(
            key = item.id,
            group = swipes,
            modifier = modifier,
            action = queueAction(item, onQueue)
        ) {
            SpotifyMediaRow(item, Modifier, secondary = item.artist, onClick = { onTrack(item) })
        }

        is SpotifyPlaylist -> SpotifyLibraryRow(item.artRes, item.name, playlistSubtitle(item), modifier, artUrl = item.artUrl) { onPlaylist(item) }

        is SpotifyAlbum -> SpotifyLibraryRow(
            item.artRes,
            item.title,
            if (item.year > 0) "${item.artist} · ${item.year}" else item.artist,
            modifier,
            artUrl = item.artUrl
        ) { onAlbum(item) }

        is SpotifyArtist -> SpotifyLibraryRow(item.artRes, item.name, "Artist", modifier, artUrl = item.artUrl) { onArtist(item) }

        is SpotifyShow -> SpotifyLibraryRow(item.artRes, item.name, item.publisher, modifier, artUrl = item.artUrl) { onShow(item) }

        else -> Unit
    }
}

@Composable
fun CollectionScreen(
    collection: SpotifyCollection,
    state: SpotifyUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
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
    // Save and Artist already have tiles in the header. The options bar is capped at two actions,
    // so it only carries the less common ones.
    val options = buildList {
        collection.items.firstOrNull()?.let { add(SideAction("queue", "Add to queue", SidekitIcon.QueueMusic, onClick = onQueue)) }
        add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    }
    SidekitScaffold(title = collection.title, actions = actions, options = options) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val playFocus = remember { FocusRequester() }
        val playIds = remember(collection.items) { collection.items.mapTo(mutableSetOf()) { it.id } }
        LaunchedEffect(collection.type, collection.id) { runCatching { playFocus.requestFocus() } }
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, rowIndex)) {
                    ArtworkTile(collection.artRes, "${collection.title} artwork", Modifier.weight(1f), collection.artUrl)
                    ContextPlayTile(
                        playIds,
                        state.current?.id,
                        state.isPlaying,
                        onPlay,
                        onPlayPause,
                        Modifier.weight(1f).focusRequester(playFocus)
                    )
                    SpotifyIconTile(
                        if (saved) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                        if (saved) "Saved" else "Save",
                        onSave,
                        tone = if (saved) SpotifyTileTone.Library else SpotifyTileTone.Neutral
                    )
                    if (collection.artistId.spotifyIdOrNull() != null) {
                        SpotifyIconTile(SidekitIcon.Artist, "Artist", onArtist)
                    }
                }
            }
            rowIndex += 1
            val metaIndex = rowIndex
            item(key = "meta") {
                SidekitText(
                    collection.subtitle,
                    Modifier.padding(start = 18.dp, end = 18.dp).listFade(listState, metaIndex),
                    style = SidekitTheme.typography.caption,
                    color = colors.surfaceA6,
                    maxLines = 1
                )
            }
            rowIndex += 1
            val base = rowIndex
            itemsIndexed(collection.items, key = { index, item -> "$index:${item.id}" }) { index, item ->
                SidekitSwipeRow(
                    key = item.id,
                    group = swipes,
                    modifier = Modifier.listFade(listState, base + index),
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
            rowIndex += collection.items.size
            if (collection.hasMore) {
                val moreIndex = rowIndex
                item(key = "more") {
                    PagingFooter(loadingMore, onLoadMore, Modifier.listFade(listState, moreIndex))
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
    onPlayPause: () -> Unit,
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
            SideAction("follow", if (followed) "Following" else "Follow", tone = ActionTone.Secondary, onClick = onFollow),
            SideAction("nowPlaying", "Go to now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying)
        )
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val playIds = remember(tracks) { tracks.mapTo(mutableSetOf()) { it.id } }
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, rowIndex)) {
                    ContextPlayTile(
                        playIds,
                        state.current?.id,
                        state.isPlaying,
                        onPlay,
                        onPlayPause,
                        Modifier.weight(1f)
                    )
                    ArtworkTile(artist.artRes, "${artist.name} artwork", Modifier.weight(1f), artist.artUrl)
                }
            }
            rowIndex += 1
            val topLabelIndex = rowIndex
            item(key = "topLabel") {
                SectionLabel("Top tracks", Modifier.listFade(listState, topLabelIndex))
            }
            rowIndex += 1
            val tracksBase = rowIndex
            itemsIndexed(tracks, key = { index, track -> "$index:${track.id}" }) { index, track ->
                SidekitSwipeRow(
                    key = track.id,
                    group = swipes,
                    modifier = Modifier.listFade(listState, tracksBase + index),
                    action = queueAction(track, onQueue)
                ) {
                    SpotifyMediaRow(track, Modifier, secondary = track.artist, onClick = { onTrack(track) })
                }
            }
            rowIndex += tracks.size
            if (trackHasMore) {
                val moreIndex = rowIndex
                item(key = "tracksMore") {
                    SeeMoreRow(
                        "See more tracks",
                        loadingMore,
                        onLoadMoreTracks,
                        Modifier.listFade(listState, moreIndex)
                    )
                }
                rowIndex += 1
            }
            val albumsLabelIndex = rowIndex
            item(key = "albumsLabel") {
                SectionLabel("Albums", Modifier.listFade(listState, albumsLabelIndex))
            }
            rowIndex += 1
            val albumsBase = rowIndex
            itemsIndexed(albums, key = { index, album -> "$index:${album.id}" }) { index, album ->
                SpotifyLibraryRow(
                    album.artRes,
                    album.title,
                    "${album.year}" + if (album.trackCount > 0) " · ${album.trackCount} tracks" else "",
                    Modifier.listFade(listState, albumsBase + index),
                    artUrl = album.artUrl
                ) { onAlbum(album) }
            }
            rowIndex += albums.size
            if (albumHasMore) {
                val moreIndex = rowIndex
                item(key = "albumsMore") {
                    SeeMoreRow(
                        "See more albums",
                        loadingMore,
                        onLoadMoreAlbums,
                        Modifier.listFade(listState, moreIndex)
                    )
                }
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
