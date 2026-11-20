package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState

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
    onTrack: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    onShow: (Show) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onLoadMore: () -> Unit,
    onNowPlaying: () -> Unit,
    onRetry: () -> Unit,
    onCreatePlaylist: () -> Unit,
    canCreatePlaylist: Boolean,
    supportedFilters: Set<LibraryFilter> = LibraryFilter.entries.toSet(),
    onArtistRadio: (Artist) -> Unit = {},
    canRadio: Boolean = true,
    onQueueAlbum: (Album) -> Unit = {},
    onQueuePlaylist: (Playlist) -> Unit = {},
    onAddLatestShowToQueue: (Show) -> Unit = {},
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onAddToPlaylist: ((Track) -> Unit)? = null
) {
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue,
        onAddToPlaylist = onAddToPlaylist
    )
    // Artist and album rows own a gutter of their own: follow/radio and save/queue are list
    // verbs here, not destinations. Track rows keep their contextual bar, so the states never mix.
    val libraryGutters = rememberRowGutterState()
    val baseActions = remember {
        listOf(sidetrackBack("Home", onBack).first(), SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch))
    }
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    // The gate outlives any one row: after a hold switches category, the list may rebuild and move
    // focus before the physical key comes up. The screen root uses the same gate to swallow the
    // repeats and the release so the category row cannot walk sideways through every category.
    val holdGate = remember { CategoryHoldGate() }
    SidekitScaffold(
        title = library.filter.label,
        modifier = Modifier
            .dismissRowActionsOnVerticalKey(rows.bar)
            .consumeCategoryHoldRepeats(holdGate),
        actions = actions,
        options = buildList {
            // Creating a playlist is a per-service capability, and it only belongs on the
            // playlists category where the result would appear.
            if (canCreatePlaylist && library.filter == LibraryFilter.Playlists) {
                add(SideAction("createPlaylist", "New playlist", SidekitIcon.PlaylistAdd, onClick = onCreatePlaylist))
            }
            add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
        }
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val filter = library.filter
        val items = library.libraryItems
        val filterFocus = remember {
            LibraryFilter.entries.associateWith { FocusRequester() }
        }
        val moreFocus = rememberLoadMoreFocus()
        val loading = items.isEmpty() && library.libraryLoading
        val viewport = rememberLibraryViewport(
            library = library,
            listState = listState,
            rows = rows,
            libraryGutters = libraryGutters,
            filterFocus = filterFocus,
            moreFocus = moreFocus,
            supportedFilters = supportedFilters,
            gate = holdGate,
            onFilter = onFilter
        )
        val switchFilter = viewport.switchFilter
        val holdSwitch = viewport.holdSwitch
        val restoreFocus = viewport.restoreFocus
        val restoreFocusIndex = viewport.restoreFocusIndex
        val firstRowModifier = viewport.firstRowModifier
        if (items.isEmpty()) {
            // The filter row stays above the status block, and the block centres in what is left
            // of the page. It shrinks its record to fit; a full-size one would push the text off
            // the bottom under the five square filters.
            Column(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                LibraryFilterRow(
                    filter,
                    switchFilter,
                    filterFocus,
                    supportedFilters = supportedFilters,
                    acceptsFocus = rows.bar.selection == null
                )
                Spacer(Modifier.height(SidekitTheme.spacing.pageGap))
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        loading -> LoadingScreen(
                            title = "Loading ${filter.label.lowercase()}…",
                            hint = null
                        )

                        library.libraryError != null -> StatusScreen(
                            "Couldn't load ${filter.label.lowercase()}",
                            hint = library.libraryError,
                            actionLabel = "Try again",
                            onAction = onRetry
                        )

                        else -> StatusScreen(
                            filter.empty,
                            hint = "Search to find something to save"
                        )
                    }
                }
            }
        } else {
            // The filter row is the first list item, not a header above the list: it scrolls away
            // with the content and shares the list fade with the rows below it.
            LoadMoreFocus(
                moreFocus,
                listState,
                items.size,
                library.libraryMorePending,
                firstItemListIndex = 1
            )
            SidekitList(padding, state = listState) {
                item(key = "filters") {
                    LibraryFilterRow(
                        filter,
                        switchFilter,
                        filterFocus,
                        Modifier.listFade(listState, 0)
                            .padding(bottom = SidekitTheme.spacing.pageGap),
                        supportedFilters,
                        acceptsFocus = rows.bar.selection == null
                    )
                }
                var rowIndex = 1
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
                        holdSwitch,
                        rows,
                        libraryGutters,
                        focusRequester = if (index == restoreFocusIndex) {
                            restoreFocus
                        } else {
                            moreFocus.requesterFor(index)
                        },
                        canRadio = canRadio,
                        onArtistRadio = onArtistRadio,
                        onQueueAlbum = onQueueAlbum,
                        onQueuePlaylist = onQueuePlaylist,
                        onAddLatestShowToQueue = onAddLatestShowToQueue,
                        onTrack = onTrack,
                        onPlaylist = onPlaylist,
                        onAlbum = onAlbum,
                        onArtist = onArtist,
                        onShow = onShow
                    )
                }
                rowIndex += items.size
                if (library.libraryHasMore) {
                    val moreIndex = rowIndex
                    item(key = "more") {
                        LoadMoreRow(
                            "Load more ${filter.label.lowercase()}",
                            library.libraryMorePending,
                            onLoadMore = {
                                moreFocus.onLoadMore(items.size)
                                onLoadMore()
                            },
                            Modifier.listFade(listState, moreIndex),
                            focusState = moreFocus
                        )
                    }
                }
            }
        }
    }
}
