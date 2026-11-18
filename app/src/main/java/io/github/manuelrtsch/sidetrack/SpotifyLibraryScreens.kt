package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SidetrackHomeScreen(
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    sleepTimer: StateFlow<SleepTimerState>,
    onNowPlaying: () -> Unit,
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onPlay: (SpotifyMediaItem) -> Unit,
    onOpenQueue: () -> Unit,
    onAddToQueue: (SpotifyMediaItem) -> Unit,
    onRemoveHistory: (SpotifyMediaItem) -> (() -> Unit)?,
    onHistory: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    // The slice is remembered so an unrelated state change (notice, save, etc.) does not give the
    // recent rows a new list instance and force them to rebuild.
    val recent = remember(playback.recent) { playback.recent.take(RECENT_PREVIEW_SIZE) }
    val hasMoreHistory = playback.recent.size > RECENT_PREVIEW_SIZE
    val recentGutters = rememberRowGutterState()
    val rowActions = rememberRowActionBarState()
    val baseActions = remember {
        listOf(
            SideAction("queue", "Queue", SidekitIcon.QueueMusic, contentDescription = "Open queue", onClick = onOpenQueue),
            SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch)
        )
    }
    val bar = rowActionBarActions(
        rowActions,
        spotifyActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    SidekitScaffold(
        title = "Sidetrack",
        modifier = Modifier.dismissRowActionsOnVerticalKey(rowActions),
        actions = bar,
        options = listOf(
            SideAction("settings", "Settings", SidekitIcon.Settings, onClick = onSettings)
        )
    ) { padding ->
        val listState = rememberLazyListState()
        rowActions.dismissOnScroll(listState)
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    NowPlayingTile(playback.current, sleepTimer, onNowPlaying, Modifier.weight(1f))
                    LibraryTile(onLibrary, Modifier.width(SidekitTheme.spacing.itemHeight))
                }
            }
            item(key = "recentLabel") { SectionLabel("Recently played") }
            if (recent.isEmpty()) {
                item(key = "empty") {
                    SpotifyStatusScreen(
                        "Nothing played yet",
                        Modifier.listFade(listState, 1),
                        hint = "Search to find your first track"
                    )
                }
            } else {
                itemsIndexed(
                    recent,
                    key = { index, item -> "$index:${item.id}" },
                    contentType = { _, item -> mediaContentType(item) }
                ) { index, item ->
                    val key = "$index:${item.id}"
                    HistoryRow(
                        key,
                        item,
                        recentGutters,
                        rowActions,
                        Modifier.listFade(listState, index + 2),
                        onAddToQueue = onAddToQueue,
                        onRemove = onRemoveHistory,
                        onClick = { onPlay(item) }
                    )
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

/**
 * The Library: one category at a time, chosen from a row of five square filter buttons under
 * the title. Moving focus across the row attaches that category's list, so the keypad can
 * browse the library without opening a second screen per category.
 */
@Composable
fun LibraryScreen(
    library: LibraryUiState,
    saved: SavedUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onFilter: (LibraryFilter) -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onAddToQueue: (SpotifyTrack) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onShow: (SpotifyShow) -> Unit,
    onSaveTrack: (SpotifyTrack) -> Unit,
    onEnsureSavedTrack: (SpotifyTrack) -> Unit,
    onLoadMore: () -> Unit,
    onNowPlaying: () -> Unit,
    onRetry: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue
    )
    val baseActions = remember {
        listOf(spotifyBack("Home", onBack).first(), SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch))
    }
    val actions = rowActionBarActions(
        rows.bar,
        spotifyActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    SidekitScaffold(
        title = "Library",
        modifier = Modifier.dismissRowActionsOnVerticalKey(rows.bar),
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val filter = library.filter
        val items = library.libraryItems
        val filterFocus = remember {
            LibraryFilter.entries.associateWith { FocusRequester() }
        }
        // Focus the current category so the row and the list below are one connected target
        // when the screen opens; its onFocusChanged does not reload the same category.
        LaunchedEffect(Unit) {
            runCatching { filterFocus.getValue(library.filter).requestFocus() }
        }
        LaunchedEffect(filter) { listState.scrollToItem(0) }
        // Up from the first row returns to the keyboard row rather than letting focus search pick
        // the geometrically nearest button, which is the middle one.
        val firstRowModifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                filterFocus.getValue(filter).requestFocus()
                true
            } else {
                false
            }
        }
        val loading = items.isEmpty() && library.libraryLoading
        if (loading || (items.isEmpty() && library.libraryError != null)) {
            Column(Modifier.fillMaxSize()) {
                LibraryFilterRow(filter, onFilter, filterFocus)
                Spacer(Modifier.height(SidekitTheme.spacing.pageGap))
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    if (loading) {
                        SpotifyLoadingScreen(title = "Loading ${filter.label.lowercase()}…", hint = null)
                    } else {
                        SpotifyStatusScreen(
                            "Couldn't load ${filter.label.lowercase()}",
                            hint = library.libraryError,
                            actionLabel = "Try again",
                            onAction = onRetry
                        )
                    }
                }
            }
        } else {
            // The filter row is the first list item, not a header above the list: it scrolls away
            // with the content and shares the list fade with the rows below it.
            SidekitList(padding, state = listState) {
                item(key = "filters") {
                    LibraryFilterRow(
                        filter,
                        onFilter,
                        filterFocus,
                        Modifier.listFade(listState, 0)
                            .padding(bottom = SidekitTheme.spacing.pageGap)
                    )
                }
                var rowIndex = 1
                if (items.isEmpty()) {
                    val index = rowIndex
                    item(key = "empty") {
                        SpotifyStatusScreen(
                            filter.empty,
                            Modifier.listFade(listState, index).then(firstRowModifier),
                            hint = "Search to find something to save"
                        )
                    }
                    rowIndex += 1
                }
                // Snapshot the offset before registering the items: the lazy DSL runs each item's
                // content later, after rowIndex has moved past the page, so reading it inside the
                // lambda would fade every row by the wrong index.
                val base = rowIndex
                itemsIndexed(
                    items,
                    key = { index, item -> "$index:${libraryKey(item)}" },
                    contentType = { _, item -> libraryKey(item).take(1) }
                ) { index, item ->
                    var modifier: Modifier = Modifier.listFade(listState, base + index)
                    if (index == 0) modifier = modifier.then(firstRowModifier)
                    LibraryRow(
                        item,
                        modifier,
                        rows,
                        onTrack,
                        onPlaylist,
                        onAlbum,
                        onArtist,
                        onShow
                    )
                }
                rowIndex += items.size
                if (library.libraryHasMore) {
                    val moreIndex = rowIndex
                    item(key = "more") {
                        LoadMoreRow(
                            "Load more ${filter.label.lowercase()}",
                            library.libraryMorePending,
                            onLoadMore,
                            Modifier.listFade(listState, moreIndex)
                        )
                    }
                }
            }
        }
    }
}

/** Five square category buttons. Focus selects a category; clicking does too. */
@Composable
private fun LibraryFilterRow(
    selected: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    focusRequesters: Map<LibraryFilter, FocusRequester>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = LibraryFilter.entries.size
        val cell = (maxWidth - SidekitTheme.spacing.itemOuterGap * (columns - 1)) / columns.toFloat()
        SidekitItemRow {
            LibraryFilter.entries.forEach { filter ->
                val isSelected = filter == selected
                SidekitIconItem(
                    icon = filter.icon,
                    contentDescription = filter.label,
                    modifier = Modifier
                        .weight(1f)
                        .height(cell)
                        .focusRequester(focusRequesters.getValue(filter))
                        .onFocusChanged { state ->
                            if (state.isFocused && !isSelected) onFilter(filter)
                        }
                        .semantics { this.selected = isSelected },
                    color = (if (isSelected) SpotifyTileTone.Library else SpotifyTileTone.Neutral)
                        .colour(SidekitTheme.colors),
                    iconSize = SidekitTheme.spacing.tileIconSize,
                    onClick = { onFilter(filter) }
                )
            }
        }
    }
}

private val LibraryFilter.icon: SidekitIcon
    get() = when (this) {
        LibraryFilter.LikedSongs -> SidekitIcon.Favorite
        LibraryFilter.Playlists -> SidekitIcon.QueueMusic
        LibraryFilter.Albums -> SidekitIcon.Album
        LibraryFilter.Artists -> SidekitIcon.Artist
        LibraryFilter.Shows -> SidekitIcon.SmartDisplay
    }

@Composable
private fun LibraryRow(
    item: Any,
    modifier: Modifier,
    rows: TrackRows,
    onTrack: (SpotifyTrack) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onShow: (SpotifyShow) -> Unit
) {
    when (item) {
        // Library rows carry no list index: a track can appear once per category, so its id is
        // already unique here.
        is SpotifyTrack -> TrackRow(item.id, item, rows, modifier) { onTrack(item) }

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
    saved: SavedUiState,
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onItem: (Int) -> Unit,
    onSave: () -> Unit,
    onArtist: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddItemToQueue: (SpotifyMediaItem) -> Unit,
    onSaveTrack: (SpotifyTrack) -> Unit,
    onEnsureSavedTrack: (SpotifyTrack) -> Unit,
    onNowPlaying: () -> Unit,
    onArtwork: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    /** Generated pages such as Song radio re-use the collection body but cannot be saved. */
    showSave: Boolean = true
) {
    val colors = SidekitTheme.colors
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddItemToQueue
    )
    val savedCollection = "${collection.type.route}:${collection.id}" in saved.savedCollectionKeys
    val back = remember { spotifyBack("Back", onBack).first() }
    val actions = rowActionBarActions(
        rows.bar,
        spotifyBar(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            back
        )
    )
    // The header keeps the three primary actions: play, artwork and save. The artist lives in the
    // metadata line below it, so collections keep the same play / image / save-or-follow layout
    // without a third Options entry.
    val options = buildList {
        collection.items.firstOrNull()?.let { add(SideAction("addToQueue", "Add to queue", SidekitIcon.PlaylistAdd, onClick = onAddToQueue)) }
        add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    }
    val artist = if (collection.type == SpotifyCollectionType.Album) {
        collection.artistId.spotifyIdOrNull()?.let { collection.subtitle.substringBefore(" · ").takeIf(String::isNotBlank) }
    } else {
        null
    }
    val details = artist?.let { collection.subtitle.removePrefix(it).trimStart(' ', '·', ' ') }.orEmpty()
    SidekitScaffold(
        title = collection.title,
        modifier = Modifier.dismissRowActionsOnVerticalKey(rows.bar),
        actions = actions,
        options = options
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val playFocus = remember { FocusRequester() }
        val playIds = remember(collection.items) { collection.items.mapTo(mutableSetOf()) { it.id } }
        LaunchedEffect(collection.type, collection.id) { runCatching { playFocus.requestFocus() } }
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
                        Modifier.weight(1f).focusRequester(playFocus)
                    )
                    ArtworkTile(
                        collection.artRes,
                        "${collection.title} artwork",
                        Modifier.weight(1f),
                        collection.artUrl,
                        onClick = onArtwork
                    )
                    if (showSave) {
                        SpotifyIconTile(
                            if (savedCollection) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                            if (savedCollection) "Saved" else "Save",
                            onSave,
                            tone = if (savedCollection) SpotifyTileTone.Library else SpotifyTileTone.Neutral
                        )
                    }
                }
            }
            rowIndex += 1
            val metaIndex = rowIndex
            item(key = "meta") {
                if (artist != null) {
                    CollectionArtistLine(
                        artist,
                        details,
                        Modifier.padding(start = 18.dp, end = 18.dp).listFade(listState, metaIndex),
                        onArtist
                    )
                } else {
                    SidekitText(
                        collection.subtitle,
                        Modifier.padding(start = 18.dp, end = 18.dp).listFade(listState, metaIndex),
                        style = SidekitTheme.typography.bodySmall,
                        color = colors.surfaceA6,
                        maxLines = 1
                    )
                }
            }
            rowIndex += 1
            val base = rowIndex
            // Albums and shows share one cover, so a position number is the useful left column.
            // Playlist tracks have their own artwork, which is more informative than a list index.
            val showTrackArt = collection.type == SpotifyCollectionType.Playlist
            itemsIndexed(
                collection.items,
                key = { index, item -> "$index:${item.id}" },
                contentType = { _, item -> mediaContentType(item) }
            ) { index, item ->
                val key = "$index:${item.id}"
                val modifier = Modifier.listFade(listState, base + index)
                if (item is SpotifyTrack) {
                    TrackRow(
                        key,
                        item,
                        rows,
                        modifier,
                        index = if (showTrackArt) null else index,
                        showArt = showTrackArt
                    ) { onItem(index) }
                } else {
                    GutterMediaRow(
                        key,
                        item,
                        rows.focus,
                        modifier,
                        index = if (showTrackArt) null else index,
                        showArt = showTrackArt,
                        secondary = if (item is SpotifyEpisode) formatPublishedDate(item.published) else item.creator,
                        onAddToQueue = onAddItemToQueue,
                        onClick = { onItem(index) }
                    )
                }
            }
            rowIndex += collection.items.size
            if (collection.hasMore) {
                val moreIndex = rowIndex
                item(key = "more") {
                    LoadMoreRow(
                        if (collection.type == SpotifyCollectionType.Show) "Load more episodes" else "Load more tracks",
                        loadingMore,
                        onLoadMore,
                        Modifier.listFade(listState, moreIndex)
                    )
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
    saved: SavedUiState,
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onAddToQueue: (SpotifyTrack) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onSaveTrack: (SpotifyTrack) -> Unit,
    onEnsureSavedTrack: (SpotifyTrack) -> Unit,
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
    onDismissNowPlaying: () -> Unit
) {
    val followed = artist.id in saved.followedArtistIds
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue
    )
    val back = remember { spotifyBack("Back", onBack).first() }
    val actions = rowActionBarActions(
        rows.bar,
        spotifyBar(
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
        options = listOf(
            SideAction("artistRadio", "Artist radio", SidekitIcon.Explore, onClick = onRadio),
            SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying)
        )
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val playIds = remember(tracks) { tracks.mapTo(mutableSetOf()) { it.id } }
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
                    SpotifyIconTile(
                        if (followed) SidekitIcon.Artist else SidekitIcon.ArtistBorder,
                        if (followed) "Following" else "Follow",
                        onFollow,
                        tone = if (followed) SpotifyTileTone.Library else SpotifyTileTone.Neutral
                    )
                }
            }
            rowIndex += 1
            item(key = "topLabel") {
                SectionLabel("Top tracks")
            }
            rowIndex += 1
            val tracksBase = rowIndex
            itemsIndexed(
                tracks,
                key = { index, track -> "$index:${track.id}" },
                contentType = { _, _ -> "track" }
            ) { index, track ->
                TrackRow(
                    "$index:${track.id}",
                    track,
                    rows,
                    Modifier.listFade(listState, tracksBase + index)
                ) { onTrack(track) }
            }
            rowIndex += tracks.size
            if (trackHasMore) {
                val moreIndex = rowIndex
                item(key = "tracksMore") {
                    LoadMoreRow(
                        "Load more tracks",
                        loadingMore,
                        onLoadMoreTracks,
                        Modifier.listFade(listState, moreIndex)
                    )
                }
                rowIndex += 1
            }
            item(key = "albumsLabel") {
                SectionLabel("Albums")
            }
            rowIndex += 1
            val albumsBase = rowIndex
            itemsIndexed(
                albums,
                key = { index, album -> "$index:${album.id}" },
                contentType = { _, _ -> "album" }
            ) { index, album ->
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
                    LoadMoreRow(
                        "Load more albums",
                        loadingMore,
                        onLoadMoreAlbums,
                        Modifier.listFade(listState, moreIndex)
                    )
                }
            }
        }
    }
}

private fun mediaContentType(item: Any): String = when (item) {
    is SpotifyTrack -> "track"
    is SpotifyEpisode -> "episode"
    else -> "media"
}

private fun libraryKey(item: Any): String = when (item) {
    is SpotifyTrack -> "t${item.id}"
    is SpotifyPlaylist -> "p${item.id}"
    is SpotifyAlbum -> "a${item.id}"
    is SpotifyArtist -> "r${item.id}"
    is SpotifyShow -> "s${item.id}"
    else -> item.toString()
}

/**
 * The album's artist and the rest of its metadata on one line. The whole line opens the artist,
 * so the link is a full-width D-pad target instead of a tiny text span.
 */
@Composable
private fun CollectionArtistLine(
    artist: String,
    details: String,
    modifier: Modifier,
    onArtist: () -> Unit
) {
    val colors = SidekitTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(SidekitTheme.shapes.control)
            .clickable(onClickLabel = "Open artist", role = Role.Button, onClick = onArtist)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SidekitText(
            artist,
            Modifier.weight(if (details.isBlank()) 1f else 0.42f),
            style = SidekitTheme.typography.bodySmall,
            maxLines = 1
        )
        if (details.isNotBlank()) {
            SidekitText(
                details,
                Modifier.weight(0.58f),
                style = SidekitTheme.typography.label,
                color = colors.surfaceA6,
                maxLines = 1,
                textAlign = TextAlign.End
            )
        }
        SidekitIconImage(
            SidekitIcon.ArrowRightAlt,
            Modifier.padding(start = 8.dp),
            size = 18.dp
        )
    }
}
