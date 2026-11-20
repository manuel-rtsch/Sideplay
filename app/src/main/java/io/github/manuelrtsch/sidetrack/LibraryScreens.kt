package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconItem
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.HoldToAct
import hrln.interactive.sidekit.design.input.KeypadTiming
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.ui.InlineLinkArrowSize
import io.github.manuelrtsch.sidetrack.ui.LinkArrow
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
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
    onPlay: (MediaItem) -> Unit,
    onOpenQueue: () -> Unit,
    onAddToQueue: (MediaItem) -> Unit,
    onRemoveHistory: (MediaItem) -> (() -> Unit)?,
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
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
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
                    NowPlayingTile(playback.current, playback.isPlaying, sleepTimer, onNowPlaying, Modifier.weight(1f))
                    LibraryTile(onLibrary, Modifier.width(SidekitTheme.spacing.itemHeight))
                }
            }
            item(key = "recentLabel") { SectionLabel("Recently played") }
            if (recent.isEmpty()) {
                item(key = "empty") {
                    StatusScreen(
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
 * Where a category was left: its first visible row and the row that owned the focus. The focused
 * row is stored by its row key rather than its index, so an item inserted above it does not move
 * the cursor to a stranger.
 */
private data class LibraryViewport(
    val index: Int,
    val offset: Int,
    val focusedRowKey: String?
)

private fun LazyListState.captureViewport(focusedRowKey: String?): LibraryViewport = LibraryViewport(
    index = firstVisibleItemIndex,
    offset = firstVisibleItemScrollOffset,
    focusedRowKey = focusedRowKey
)

private fun libraryRowFocusKey(item: Any): String = when (item) {
    is Track -> item.id
    is Album -> item.key.encode()
    is Playlist -> item.key.encode()
    is Artist -> item.key.encode()
    is Show -> item.key.encode()
    else -> item.toString()
}

/** Saves the five viewports as a flat list of strings, so a process restart keeps the cache. */
private val LibraryViewportsSaver: Saver<MutableMap<LibraryFilter, LibraryViewport>, List<String>> = Saver(
    save = { viewports ->
        viewports.entries.flatMap { (filter, viewport) ->
            listOf(
                filter.name,
                viewport.index.toString(),
                viewport.offset.toString(),
                viewport.focusedRowKey.orEmpty()
            )
        }
    },
    restore = { saved ->
        mutableStateMapOf<LibraryFilter, LibraryViewport>().apply {
            saved.chunked(4).forEach { parts ->
                if (parts.size != 4) return@forEach
                runCatching {
                    put(
                        LibraryFilter.valueOf(parts[0]),
                        LibraryViewport(parts[1].toInt(), parts[2].toInt(), parts[3].ifEmpty { null })
                    )
                }
            }
        }
    }
)

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
    onDismissNowPlaying: () -> Unit
) {
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue
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
        // One viewport per category, remembered across a switch and across process death. The
        // focused row is keyed by the row, not its old index: saving or removing a row above the
        // cursor must not move the cursor to a different item when the category comes back.
        val viewports = rememberSaveable(saver = LibraryViewportsSaver) {
            mutableStateMapOf<LibraryFilter, LibraryViewport>()
        }
        var lastFocusedRowKey by remember { mutableStateOf<String?>(null) }
        var pendingViewport by remember { mutableStateOf<LibraryViewport?>(null) }
        var restoreFocusIndex by remember { mutableStateOf<Int?>(null) }
        var focusFilterRow by remember { mutableStateOf(false) }
        val restoreFocus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            snapshotFlow { rows.focus.focusedKey ?: libraryGutters.focusedKey }.collect { key ->
                if (key != null) lastFocusedRowKey = key.toString()
            }
        }
        // Switching category swaps this screen between the status body and the list, which
        // re-creates the filter row and drops focus. Re-attach focus to the selected category
        // whenever the category or the body branch changes, so keyboard navigation across the row
        // never falls through to the action bar.
        val branch = when {
            loading -> "loading"
            items.isEmpty() && library.libraryError != null -> "error"
            items.isEmpty() -> "empty"
            else -> "list"
        }
        // A row that has settled under the focus is a reading position: the user stopped moving
        // through the list. Recording it here keeps the last real position even when the next
        // thing they do is climb all the way up to the category row.
        val settledRowKey = rows.focus.openKey ?: libraryGutters.openKey
        LaunchedEffect(settledRowKey, filter, branch) {
            val settled = settledRowKey ?: return@LaunchedEffect
            if (branch != "list") return@LaunchedEffect
            val itemIndex = items.indexOfFirst { libraryRowFocusKey(it) == settled.toString() }
            if (itemIndex < 0) return@LaunchedEffect
            viewports[filter] = listState.captureViewport(settled.toString())
        }
        val switchFilter: (LibraryFilter) -> Unit = { next ->
            if (next != filter) {
                val focusedKey = rows.focus.focusedKey ?: libraryGutters.focusedKey
                val focusedIndex = focusedKey?.let { key ->
                    items.indexOfFirst { libraryRowFocusKey(it) == key.toString() }
                } ?: -1
                val focusedVisible = focusedIndex >= 0 &&
                    listState.layoutInfo.visibleItemsInfo.any { it.index == focusedIndex + 1 }
                // A quick switch from the list records the exact position. A switch from the
                // category row keeps the last settled viewport instead of the temporary top of
                // the list the user climbed through to reach the row.
                if (focusedVisible || viewports[filter] == null) {
                    viewports[filter] = listState.captureViewport(
                        focusedKey?.toString() ?: lastFocusedRowKey
                    )
                }
                pendingViewport = viewports[next]
                restoreFocusIndex = null
                lastFocusedRowKey = null
                onFilter(next)
            }
        }
        val holdSwitch = Modifier.libraryCategoryHoldSwitch(
            filter = filter,
            supportedFilters = supportedFilters,
            enabled = rows.bar.selection == null,
            gate = holdGate,
            onSwitch = switchFilter
        )
        LaunchedEffect(filter, branch) {
            if (branch != "list") {
                moreFocus.reset()
                runCatching { filterFocus.getValue(filter).requestFocus() }
                return@LaunchedEffect
            }
            val viewport = pendingViewport
            if (viewport == null) {
                listState.scrollToItem(0)
                runCatching { filterFocus.getValue(filter).requestFocus() }
                return@LaunchedEffect
            }
            pendingViewport = null
            listState.scrollToItem(
                viewport.index.coerceIn(0, items.size),
                viewport.offset.coerceAtLeast(0)
            )
            val focusedIndex = viewport.focusedRowKey?.let { key ->
                items.indexOfFirst { libraryRowFocusKey(it) == key }.takeIf { it >= 0 }
            }
            restoreFocusIndex = focusedIndex
                ?: (viewport.index - 1).coerceIn(0, items.lastIndex)
        }
        LaunchedEffect(filter) {
            moreFocus.reset()
        }
        LaunchedEffect(restoreFocusIndex) {
            if (restoreFocusIndex == null) return@LaunchedEffect
            // The scroll above composes the restored row. Give that frame a chance to attach the
            // requester before asking it to take the cursor.
            repeat(3) {
                withFrameNanos { }
                if (runCatching { restoreFocus.requestFocus() }.isSuccess) {
                    restoreFocusIndex = null
                    return@LaunchedEffect
                }
            }
            restoreFocusIndex = null
        }
        // Up from the first row returns to the keyboard row rather than letting focus search pick
        // the geometrically nearest button, which is the middle one. The category row can be
        // outside the composed window when the first item sits at the top, so scroll it back in
        // and attach its focus requester before asking it to take the cursor.
        LaunchedEffect(focusFilterRow) {
            if (!focusFilterRow) return@LaunchedEffect
            listState.scrollToItem(0)
            repeat(3) {
                withFrameNanos { }
                if (runCatching { filterFocus.getValue(filter).requestFocus() }.isSuccess) {
                    focusFilterRow = false
                    return@LaunchedEffect
                }
            }
            focusFilterRow = false
        }
        val firstRowModifier = Modifier.onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                focusFilterRow = true
                true
            } else {
                false
            }
        }
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

/** One category step per physical hold, even if the key repeats faster than the composable. */
private class CategoryHoldGate {
    var keyCode: Int? = null
    var switched: Boolean = false

    fun reset() {
        keyCode = null
        switched = false
    }
}

/** Swallows the rest of a held arrow at the screen root, before focus can land on the category row. */
private fun Modifier.consumeCategoryHoldRepeats(gate: CategoryHoldGate): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (gate.switched && native.keyCode == gate.keyCode &&
        (native.action == KeyEvent.ACTION_DOWN || native.action == KeyEvent.ACTION_UP)
    ) {
        if (native.action == KeyEvent.ACTION_UP) gate.reset()
        true
    } else {
        false
    }
}

/**
 * A held Left/Right on a library row body steps to the adjacent category. A tap keeps its old
 * meaning (into the gutter on Right, back out on Left), so the press is held back until release
 * or the shared keypad long-press threshold fires.
 */
@Composable
private fun Modifier.libraryCategoryHoldSwitch(
    filter: LibraryFilter,
    supportedFilters: Set<LibraryFilter>,
    enabled: Boolean,
    gate: CategoryHoldGate,
    onSwitch: (LibraryFilter) -> Unit
): Modifier {
    val currentFilter by rememberUpdatedState(filter)
    val currentSupported by rememberUpdatedState(supportedFilters)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnSwitch by rememberUpdatedState(onSwitch)
    val focusManager = LocalFocusManager.current
    val hold = remember {
        HoldToAct(holdMs = KeypadTiming.LONG_PRESS_MS, onHold = { action, _ ->
            if (!currentEnabled || gate.switched) return@HoldToAct
            val step = when (action) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@HoldToAct
            }
            val next = adjacentLibraryFilter(currentFilter, step, currentSupported) ?: return@HoldToAct
            gate.switched = true
            currentOnSwitch(next)
        })
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            hold.reset()
            gate.reset()
        }
    }
    DisposableEffect(hold) {
        onDispose {
            hold.reset()
            gate.reset()
        }
    }
    return onPreviewKeyEvent { event ->
        if (!currentEnabled) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        when {
            // A hold already stepped. Swallow every repeat until the physical key comes up, even
            // if the input source reports each repeat as a fresh press with a zero repeat count.
            native.action == KeyEvent.ACTION_DOWN &&
                gate.switched &&
                native.keyCode == gate.keyCode -> return@onPreviewKeyEvent true

            native.action == KeyEvent.ACTION_UP && native.keyCode == gate.keyCode -> gate.reset()

            native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> gate.keyCode = native.keyCode
        }
        when (
            val result = hold.handle(native) { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
                    else -> null
                }
            }
        ) {
            HoldToAct.Result.Pass -> false

            HoldToAct.Result.Consume -> true

            is HoldToAct.Result.Deliver -> {
                // A tap: replay the direction the swallowed press would have performed.
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> focusManager.moveFocus(FocusDirection.Left)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> focusManager.moveFocus(FocusDirection.Right)
                }
                true
            }
        }
    }
}

/** The next category in [step] order that the service can fill, skipping empty slots. */
private fun adjacentLibraryFilter(
    from: LibraryFilter,
    step: Int,
    supported: Set<LibraryFilter>
): LibraryFilter? {
    val filters = LibraryFilter.entries
    var index = from.ordinal + step
    while (index in filters.indices) {
        val candidate = filters[index]
        if (candidate in supported) return candidate
        index += step
    }
    return null
}

/**
 * Five square category buttons. Focus selects a category; clicking does too. A category the
 * connected service cannot fill keeps its slot but is muted and skipped, so the row never reflows
 * and no button leads to an empty page.
 */
@Composable
private fun LibraryFilterRow(
    selected: LibraryFilter,
    onFilter: (LibraryFilter) -> Unit,
    focusRequesters: Map<LibraryFilter, FocusRequester>,
    modifier: Modifier = Modifier,
    supportedFilters: Set<LibraryFilter> = LibraryFilter.entries.toSet(),
    acceptsFocus: Boolean = true
) {
    ContentFilterRow(
        filters = LibraryFilter.entries,
        selected = selected,
        icon = { it.icon },
        label = { it.label },
        onSelect = onFilter,
        focusRequesters = focusRequesters,
        modifier = modifier,
        enabled = { it in supportedFilters },
        // A row action bar owns the focus while it is open. Leaving the category buttons
        // focusable let Android's exit from touch mode land on the first one, select it, and
        // dispose the very row whose contextual bar had just opened.
        acceptsFocus = acceptsFocus,
        // Unlike a search category that is merely switched off, this one cannot be switched on
        // anywhere: it is a fact about the service, not a setting.
        disabledLabel = { "not available" }
    )
}

/**
 * A row of equally wide square category buttons, one per entry of [filters]. Moving focus with
 * Left/Right (or clicking) selects a category; the screen then shows that category's content.
 * Library and Search share the row so both pages behave the same under the pointer and keypad.
 */
@Composable
internal fun <T> ContentFilterRow(
    filters: List<T>,
    selected: T,
    icon: (T) -> SidekitIcon,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    focusRequesters: Map<T, FocusRequester>,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    /** False while another surface (a row action bar) owns the focus. */
    acceptsFocus: Boolean = true,
    /** How a disabled button is announced: "off" when it could be switched on, otherwise why not. */
    disabledLabel: (T) -> String = { "off" }
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = filters.size
        val cell = (maxWidth - SidekitTheme.spacing.itemOuterGap * (columns - 1)) / columns.toFloat()
        SidekitItemRow {
            filters.forEachIndexed { index, filter ->
                val isSelected = filter == selected
                val isEnabled = enabled(filter)
                SidekitIconItem(
                    icon = icon(filter),
                    // An excluded category keeps its slot so the row never reflows; it only loses
                    // its click, its muted colour says why.
                    contentDescription = if (isEnabled) label(filter) else "${label(filter)}, ${disabledLabel(filter)}",
                    modifier = Modifier
                        .weight(1f)
                        .height(cell)
                        .focusRequester(focusRequesters.getValue(filter))
                        .then(
                            if (acceptsFocus) {
                                Modifier
                            } else {
                                Modifier.focusProperties { canFocus = false }
                            }
                        )
                        .onFocusChanged { state ->
                            if (isEnabled && acceptsFocus && state.isFocused && !isSelected) onSelect(filter)
                        }
                        // Left/Right move to the adjacent enabled category by request. Geometric
                        // focus search picked the action bar (below and to the right) instead of
                        // the neighbouring button, and the loading swap re-created the row, so the
                        // buttons to the right were unreachable. Down/Up keep their default path
                        // into and out of the content below. Disabled buttons are skipped.
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) {
                                false
                            } else {
                                when (event.key) {
                                    Key.DirectionRight -> {
                                        focusAdjacent(filters, index, 1, enabled, focusRequesters)
                                        true
                                    }

                                    Key.DirectionLeft -> {
                                        focusAdjacent(filters, index, -1, enabled, focusRequesters)
                                        true
                                    }

                                    else -> false
                                }
                            }
                        }
                        .semantics {
                            this.selected = isSelected
                            if (!isEnabled) disabled()
                        },
                    color = when {
                        !isEnabled -> TileTone.Muted
                        isSelected -> TileTone.Library
                        else -> TileTone.Neutral
                    }.colour(SidekitTheme.colors),
                    iconSize = SidekitTheme.spacing.tileIconSize,
                    onClick = if (isEnabled) ({ onSelect(filter) }) else null
                )
            }
        }
    }
}

/** Moves focus past disabled entries in the row; stays put when there is no enabled one left. */
private fun <T> focusAdjacent(
    filters: List<T>,
    from: Int,
    step: Int,
    enabled: (T) -> Boolean,
    focusRequesters: Map<T, FocusRequester>
) {
    var index = from + step
    while (index in filters.indices) {
        val candidate = filters[index]
        if (enabled(candidate)) {
            focusRequesters[candidate]?.requestFocus()
            return
        }
        index += step
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
    bodyModifier: Modifier,
    rows: TrackRows,
    libraryGutters: RowGutterState,
    focusRequester: FocusRequester? = null,
    canRadio: Boolean,
    onArtistRadio: (Artist) -> Unit,
    onQueueAlbum: (Album) -> Unit,
    onQueuePlaylist: (Playlist) -> Unit,
    onAddLatestShowToQueue: (Show) -> Unit,
    onTrack: (Track) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    onShow: (Show) -> Unit
) {
    // Every library row now wraps its focusable tile in a gutter, so each takes the load-more
    // focus requester directly instead of receiving it through an already-modified tile.
    when (item) {
        // Library rows carry no list index: a track can appear once per category, so its id is
        // already unique here.
        is Track -> TrackRow(
            item.id,
            item,
            rows,
            modifier,
            bodyModifier,
            focusRequester = focusRequester
        ) { onTrack(item) }

        is Playlist -> PlaylistGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            onAddToQueue = { onQueuePlaylist(item) },
            onClick = { onPlaylist(item) }
        )

        is Album -> AlbumGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            onAddToQueue = { onQueueAlbum(item) },
            onClick = { onAlbum(item) }
        )

        is Artist -> ArtistGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            canRadio = canRadio,
            onRadio = { onArtistRadio(item) },
            onClick = { onArtist(item) }
        )

        is Show -> ShowGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            onAddLatestToQueue = { onAddLatestShowToQueue(item) },
            onClick = { onShow(item) }
        )

        else -> Unit
    }
}

@Composable
fun CollectionScreen(
    collection: Collection,
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
    onAddItemToQueue: (MediaItem) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
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
    val savedCollection = collection.key.encode() in saved.savedCollectionKeys
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
    // The header keeps the three primary actions: play, artwork and save. The artist lives in the
    // metadata line below it, so collections keep the same play / image / save-or-follow layout
    // without a third Options entry.
    val options = buildList {
        collection.items.firstOrNull()?.let { add(SideAction("addToQueue", "Add to queue", SidekitIcon.PlaylistAdd, onClick = onAddToQueue)) }
        add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    }
    val artist = if (collection.type == CollectionType.Album) {
        collection.artistId.mediaIdOrNull()?.let { collection.subtitle.substringBefore(" · ").takeIf(String::isNotBlank) }
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
        val moreFocus = rememberLoadMoreFocus()
        val playIds = remember(collection.items) { collection.items.mapTo(mutableSetOf()) { it.id } }
        LaunchedEffect(collection.type, collection.id) {
            moreFocus.reset()
            runCatching { playFocus.requestFocus() }
        }
        LoadMoreFocus(
            moreFocus,
            listState,
            collection.items.size,
            loadingMore,
            firstItemListIndex = 2
        )
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
                        IconTile(
                            if (savedCollection) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                            if (savedCollection) "Saved" else "Save",
                            onSave,
                            tone = if (savedCollection) TileTone.Library else TileTone.Neutral
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
            val showTrackArt = collection.type == CollectionType.Playlist
            itemsIndexed(
                collection.items,
                key = { index, item -> "$index:${item.id}" },
                contentType = { _, item -> mediaContentType(item) }
            ) { index, item ->
                val key = "$index:${item.id}"
                val modifier = Modifier.listFade(listState, base + index)
                if (item is Track) {
                    TrackRow(
                        key,
                        item,
                        rows,
                        modifier,
                        index = if (showTrackArt) null else index,
                        showArt = showTrackArt,
                        focusRequester = moreFocus.requesterFor(index)
                    ) { onItem(index) }
                } else {
                    GutterMediaRow(
                        key,
                        item,
                        rows.focus,
                        modifier,
                        index = if (showTrackArt) null else index,
                        showArt = showTrackArt,
                        secondary = if (item is Episode) formatPublishedDate(item.published) else item.creator,
                        focusRequester = moreFocus.requesterFor(index),
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
                        if (collection.type == CollectionType.Show) "Load more episodes" else "Load more tracks",
                        loadingMore,
                        onLoadMore = {
                            moreFocus.onLoadMore(collection.items.size)
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

private fun mediaContentType(item: Any): String = when (item) {
    is Track -> "track"
    is Episode -> "episode"
    else -> "media"
}

private fun libraryKey(item: Any): String = when (item) {
    is Track -> "t${item.id}"
    is Playlist -> "p${item.id}"
    is Album -> "a${item.id}"
    is Artist -> "r${item.id}"
    is Show -> "s${item.id}"
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
        LinkArrow(Modifier.padding(start = 8.dp), size = InlineLinkArrowSize)
    }
}

/**
 * Names a new playlist for the active service. [onSubmit] receives the trimmed name; the caller
 * creates it on the service and opens the result. The create row is only reachable once a name is
 * typed, so an empty or whitespace-only name cannot create an untitled playlist.
 */
@Composable
fun CreatePlaylistScreen(
    onBack: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val createFocus = remember { FocusRequester() }
    val canCreate = name.trim().isNotEmpty()
    val actions = remember { listOf(SideAction.back("back", "Library", onBack)) }
    SidekitScaffold(
        title = "New playlist",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "name") {
                SidekitTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth().padding(horizontal = SidekitTheme.spacing.itemPadding)
                        .semantics { contentDescription = "Playlist name" },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Playlist name",
                    singleLine = true,
                    autoFocus = true,
                    focusOnKeypad = true,
                    onDone = { if (canCreate) createFocus.requestFocus() }
                )
            }
            item(key = "create") {
                SidetrackItem(
                    "Create playlist",
                    Modifier.listFade(listState, 1)
                        .then(if (canCreate) Modifier.focusRequester(createFocus) else Modifier)
                        .semantics { if (!canCreate) disabled() },
                    icon = SidekitIcon.PlaylistAdd,
                    showLinkArrow = true,
                    onClick = { if (canCreate) onSubmit(name.trim()) }
                )
            }
        }
    }
}
