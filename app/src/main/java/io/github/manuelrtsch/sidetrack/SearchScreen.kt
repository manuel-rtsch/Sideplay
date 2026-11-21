package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCompactItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow
import io.github.manuelrtsch.sidetrack.ui.SidekitSwipeRow
import io.github.manuelrtsch.sidetrack.ui.SwipeRevealGroup
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberSwipeRevealGroup

/**
 * The search landing screen: type a term, choose where to look, or pick a recent search.
 *
 * This screen is deliberately about making a search, not about browsing one. Submitting hands the
 * query to [SearchResultsScreen]; Back from there returns here with the term still in the field,
 * so the term and the categories can be changed without losing the results page to a half-typed
 * query. The history lives below "Search in" and scrolls with the page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    search: SearchUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onRemoveHistory: (String) -> Unit,
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onDraftQuery: (String) -> Unit = {},
    onClearHistory: () -> Unit = {},
    onRestoreHistory: (List<String>) -> Unit = {}
) {
    val field = rememberTextFieldState(search.query)
    val typed = field.text.toString()
    val keypad = LocalKeypad.current
    val term = typed.trim()
    val canSearch = term.length >= 2
    // One way to submit, shared by the Search action, the IME's Done and the keypads that have no
    // letters of their own (A on the game controller, centre on the Sundial).
    val submit = {
        if (canSearch) {
            field.setTextAndPlaceCursorAtEnd(term)
            onSearch(term)
        }
    }
    // The soft keyboard is an overlay, and this screen is small: with it up, the only things worth
    // the remaining height are the field and the category row. The bar returns when it is down.
    val keyboardVisible = WindowInsets.isImeVisible
    // Keep an abandoned draft in the shared state: leaving to change categories (or going back to
    // Home and returning) must show the text that was typed, not the last submitted term.
    LaunchedEffect(typed) { onDraftQuery(typed) }
    val searchFocus = remember { FocusRequester() }
    // Decided once on entry. Clearing the history must not turn this true: focusing the field would
    // open the keyboard over the Undo that was just offered.
    val focusFieldOnEntry = remember { search.searchHistory.isEmpty() || search.query.isNotEmpty() }
    // Clearing every term is one press and one Undo, like the other list-wide deletions in the app.
    var clearedHistory by remember { mutableStateOf<List<String>?>(null) }
    val undoWindow = rememberUndoWindow { clearedHistory = null }
    UndoWindowLifecycle(undoWindow) { clearedHistory = null }
    // The summary describes what the next search will actually look at: a category the service
    // cannot answer is never part of the choice, so all of the answerable ones in means
    // "Everything". The right side must stay on one line, so when one category is left out it is
    // named instead of listing the rest.
    val pickedScopes = SearchScope.entries.filter { it in supportedScopes && it in search.searchScopes }
    val leftOutScopes = SearchScope.entries.filter { it in supportedScopes && it !in search.searchScopes }
    val scopeSummary = when {
        supportedScopes.isEmpty() -> "Nothing"
        pickedScopes.size == supportedScopes.size -> "Everything"
        pickedScopes.isEmpty() -> "Nothing"
        pickedScopes.size == 1 -> pickedScopes.single().label
        leftOutScopes.size == 1 -> "All but " + leftOutScopes.single().label
        else -> pickedScopes.joinToString(", ") { it.label }
    }
    // A notice owns the bar; Clear is part of the screen's own bar and steps aside with it.
    val notice = chrome.notice != null || chrome.nowPlayingNotice != null
    val clearHistory = if (typed.isEmpty() && !keyboardVisible && !notice && search.searchHistory.isNotEmpty()) {
        SideAction(
            "clearHistory",
            "Clear",
            SidekitIcon.DeleteForever,
            tone = ActionTone.Destructive,
            contentDescription = "Clear search history",
            onClick = {
                clearedHistory = search.searchHistory
                undoWindow.start()
                onClearHistory()
            }
        )
    } else {
        null
    }
    val undo = clearedHistory?.let { previous ->
        SideAction.undo("undoClearHistory", "Undo", "Undo clear search history") {
            undoWindow.cancel()
            clearedHistory = null
            onRestoreHistory(previous)
        }
    }
    val clear = if (typed.isNotEmpty() && !keyboardVisible && !notice) {
        SideAction.square(
            "clear",
            SidekitIcon.Close,
            "Clear search",
            tone = ActionTone.Muted,
            onClick = {
                field.setTextAndPlaceCursorAtEnd("")
                onClear()
            }
        )
    } else {
        null
    }
    val back = sidetrackBack("Back", onBack).first()
    val actions = when {
        keyboardVisible -> emptyList()

        undo != null -> undoBar(back, undo)

        else -> sidetrackActions(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            buildList {
                add(back)
                when {
                    canSearch -> add(
                        SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward) {
                            submit()
                        }
                    )

                    clearHistory != null -> add(clearHistory)
                }
            }
        )
    }
    // One row may be swiped open at a time, like the queue: opening a history row's Remove closes
    // the one before it.
    val swipes = rememberSwipeRevealGroup()
    SidekitScaffold(
        title = null,
        actions = actions,
        trailingActions = listOfNotNull(clear),
        options = if (keyboardVisible) emptyList() else sidetrackOptions(onNowPlaying = onNowPlaying),
        header = {
            Box(
                Modifier.fillMaxWidth().height(SidekitTheme.spacing.headerHeight)
                    .padding(horizontal = SidekitTheme.spacing.itemPadding).padding(top = SidekitTheme.spacing.headerPaddingTop)
            ) {
                SidekitTextField(
                    field,
                    Modifier.fillMaxWidth().semantics { contentDescription = "Search" }
                        .onPreviewKeyEvent { event ->
                            // Let the typing keypads keep Enter for their own completion engine: their
                            // OK both takes a suggestion and submits the field.
                            if (event.type != KeyEventType.KeyDown || event.nativeKeyEvent.repeatCount != 0) {
                                false
                            } else if (isSearchSubmitKey(event.key, keypad?.module)) {
                                submit()
                                true
                            } else {
                                false
                            }
                        },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Search",
                    focusRequester = searchFocus,
                    focusOnKeypad = true,
                    singleLine = true,
                    // Open on the history when there is one to read: the keyboard would hide it.
                    // Coming back from results (a query is already set) focuses the field again
                    // so the term can be refined without another tap.
                    autoFocus = focusFieldOnEntry,
                    onDone = submit
                )
            }
        }
    ) { padding ->
        val listState = rememberLazyListState()
        val history = search.searchHistory
        SidekitList(padding, state = listState) {
            item(key = "scopes") {
                TerminalRow(
                    "Search in",
                    modifier = Modifier.listFade(listState, 0),
                    secondary = scopeSummary,
                    // The badge is a green success surface. The muted surface tone disappears on
                    // it; the value has to read as bright as the label beside it.
                    secondaryColor = SidekitTheme.colors.light,
                    onClick = onScopes
                )
            }
            if (history.isNotEmpty()) {
                if (!keyboardVisible) {
                    item(key = "historyLabel") {
                        SectionLabel("Recent searches", Modifier.listFade(listState, 1))
                    }
                    itemsIndexed(history, key = { _, term -> "history:$term" }) { index, term ->
                        SearchHistoryRow(
                            term,
                            swipes,
                            Modifier.listFade(listState, index + 2),
                            onOpen = {
                                field.setTextAndPlaceCursorAtEnd(term)
                                onSearch(term)
                            },
                            onRemove = {
                                swipes.closeAll()
                                onRemoveHistory(term)
                            }
                        )
                    }
                }
            } else {
                item(key = "hint") {
                    EmptyMessage(
                        when {
                            typed.isBlank() -> "Type at least two letters"
                            typed.trim().length < 2 -> "Keep typing"
                            else -> "Press Done to search"
                        },
                        Modifier.listFade(listState, 1)
                    )
                }
            }
        }
    }
}

/**
 * Keys that submit the field on the attached keypad. T9 and QWERTY own Enter while they type
 * (Sidekit's completion bar takes it first), so only the letterless keypads are handled here.
 */
internal fun isSearchSubmitKey(key: Key, module: KeypadModule?): Boolean {
    if (module?.typesOnKeypad == true) return false
    return when (key) {
        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> true
        Key.ButtonA -> module == KeypadModule.GamePad
        Key.MediaPlayPause -> module == KeypadModule.Sundial
        else -> false
    }
}

/**
 * One remembered term. Swipe left (or focus it with the keypad) to reveal Remove; tapping the row
 * searches for it again. This is the SDK's compact item on the SDK's swipe row, so it behaves like
 * every other removable row in the app.
 */
@Composable
private fun SearchHistoryRow(
    term: String,
    group: SwipeRevealGroup,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onRemove: () -> Unit
) {
    SidekitSwipeRow(
        key = "history:$term",
        group = group,
        modifier = modifier,
        onDelete = onRemove,
        deleteLabel = "Remove",
        rowHeight = SidekitTheme.spacing.compactRowHeight
    ) {
        SidekitCompactItem(term, icon = SidekitIcon.Search, onClick = onOpen)
    }
}

/**
 * The results page for the submitted query. The term is the page's title and cannot be edited
 * here: changing it means going back one step, where the field still holds it. Categories can be
 * changed from Options without losing the term, and toggling one searches again in place.
 */
@Composable
fun SearchResultsScreen(
    search: SearchUiState,
    saved: SavedUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onTrack: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onArtist: (Artist) -> Unit,
    onAlbum: (Album) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onShow: (Show) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onLoadMore: (SearchScope) -> Unit,
    onRetry: () -> Unit,
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    /** Offered where the service can build a station from an artist; null hides the row gutter. */
    canRadio: Boolean = true,
    onArtistRadio: (Artist) -> Unit = {},
    onQueueAlbum: (Album) -> Unit = {},
    onQueuePlaylist: (Playlist) -> Unit = {},
    onAddLatestShowToQueue: (Show) -> Unit = {}
) {
    val results = search.searchResults
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue,
        onAddToPlaylist = onAddToPlaylist
    )
    val baseActions = listOf(sidetrackBack("Back", onBack).first())
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    val filterFocus = remember { SearchScope.entries.associateWith { FocusRequester() } }
    val moreFocus = rememberLoadMoreFocus()
    // Artist/album/playlist/show rows share one gutter state so their action squares behave like
    // the library's, and the list settles under the cursor into a reading position.
    val searchGutters = rememberRowGutterState()
    // The gate outlives any one row: after a held arrow switches scope, the list may rebuild and
    // move focus before the physical key comes up. The screen root uses the same gate to swallow
    // the repeats and the release so the switch cannot walk sideways through every scope.
    val holdGate = remember { CategoryHoldGate() }
    var scope by rememberSaveable { mutableStateOf(SearchScope.Tracks) }
    val query = search.query.trim()
    val hasQuery = query.length >= 2
    val loading = hasQuery && search.searchPending && results.isEmpty
    val failed = hasQuery && !search.searchPending && search.searchError != null && results.isEmpty
    val showFilters = hasQuery && !loading && !failed
    // The categories that are both in the queried set and answerable by the connected service.
    // Moving the row (and the selection) only ever walks these.
    val includedScopes = SearchScope.entries.filter { it in supportedScopes && it in search.searchScopes }.toSet()
    // A failed search replaces the list with the retry body. Drop a page that was in flight so a
    // later retry cannot mistake its larger result set for a page that just arrived.
    LaunchedEffect(failed) { if (failed) moreFocus.reset() }
    SidekitScaffold(
        title = query.ifBlank { "Search" },
        modifier = Modifier
            .dismissRowActionsOnVerticalKey(rows.bar)
            .consumeCategoryHoldRepeats(holdGate),
        actions = actions,
        options = sidetrackOptions(searchInOption(onScopes), onNowPlaying = onNowPlaying)
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val viewport = rememberSearchViewport(
            query = search.query,
            scope = scope,
            setScope = { scope = it },
            supportedScopes = supportedScopes,
            includedScopes = includedScopes,
            showFilters = showFilters,
            failed = failed,
            results = results,
            listState = listState,
            rows = rows,
            searchGutters = searchGutters,
            moreFocus = moreFocus,
            scopeFocus = filterFocus,
            gate = holdGate
        )
        val holdSwitch = viewport.holdSwitch
        val restoreFocus = viewport.restoreFocus
        val restoreFocusIndex = viewport.restoreFocusIndex
        // A new query shows a different page from the top; keeping the old scroll offset would open
        // it in the middle. A scope change is handled by the viewport, which also keeps the cursor
        // on the category row.
        LaunchedEffect(search.query) {
            moreFocus.reset()
            listState.scrollToItem(0)
        }
        if (!hasQuery) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyMessage(
                    "Type at least two letters",
                    hint = "Go back to the search field"
                )
            }
            return@SidekitScaffold
        }
        if (failed) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                StatusScreen(
                    "Couldn't search",
                    hint = search.searchError,
                    actionLabel = "Try again",
                    onAction = onRetry
                )
            }
            return@SidekitScaffold
        }
        if (loading) {
            SidekitList(padding, state = listState) {
                item(key = "loading") {
                    EmptyMessage(
                        "Searching for \"$query\"",
                        Modifier.listFade(listState, 0),
                        hint = "Searching"
                    )
                }
            }
            return@SidekitScaffold
        }
        val items = results.itemsFor(scope)
        val hasMore = scope in search.searchHasMore
        val morePending = scope in search.searchMorePending
        LoadMoreFocus(moreFocus, listState, items.size, morePending, firstItemListIndex = 1)
        SidekitList(padding, state = listState) {
            // Like the library, the category row is the first list item: it scrolls away with
            // the content and one category's list is shown at a time.
            item(key = "filters") {
                ContentFilterRow(
                    filters = SearchScope.entries,
                    selected = scope,
                    icon = { it.icon },
                    label = { it.label },
                    onSelect = viewport.switchScope,
                    focusRequesters = filterFocus,
                    // A row action bar owns the focus while it is open. Leaving the category
                    // buttons focusable let Android's exit from touch mode land on the first
                    // one, select it, and dispose the very row whose bar had just opened.
                    acceptsFocus = rows.bar.selection == null,
                    // A category the service cannot answer stays in the row but takes no
                    // click and no focus: it would only ever show an empty list.
                    enabled = { it in supportedScopes && it in search.searchScopes },
                    disabledLabel = { if (it in supportedScopes) "off" else "not available" },
                    modifier = Modifier.listFade(listState, 0)
                        .padding(bottom = SidekitTheme.spacing.pageGap)
                )
            }
            if (items.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage(
                        "No ${scope.label.lowercase()} for \"$query\"",
                        Modifier.listFade(listState, 1),
                        hint = "Try another spelling or keyword"
                    )
                }
            } else {
                itemsIndexed(
                    items,
                    key = { _, item -> searchItemKey(item) },
                    contentType = { _, item -> searchItemKey(item).take(1) }
                ) { index, item ->
                    // Every result row carries its own focus target, so the load-more requester
                    // travels with a row requester instead of riding a wrapper that has no
                    // focusable. A held-arrow switch may restore exactly one row, which takes
                    // the shared restore requester for its one index.
                    val focusRequester = if (index == restoreFocusIndex) {
                        restoreFocus
                    } else {
                        moreFocus.requesterFor(index)
                    }
                    val modifier = Modifier.listFade(listState, index + 1)
                        .then(if (index == 0) viewport.firstRowModifier else Modifier)
                    when (item) {
                        is Track -> TrackRow(
                            searchItemKey(item),
                            item,
                            rows,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester
                        ) { onTrack(item) }

                        is Artist -> ArtistGutterRow(
                            searchItemKey(item),
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            canRadio = canRadio,
                            onRadio = { onArtistRadio(item) },
                            onClick = { onArtist(item) }
                        )

                        is Album -> AlbumGutterRow(
                            searchItemKey(item),
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            onAddToQueue = { onQueueAlbum(item) },
                            onClick = { onAlbum(item) }
                        )

                        is Playlist -> PlaylistGutterRow(
                            searchItemKey(item),
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            onAddToQueue = { onQueuePlaylist(item) },
                            onClick = { onPlaylist(item) }
                        )

                        is Show -> ShowGutterRow(
                            searchItemKey(item),
                            item,
                            searchGutters,
                            rows.bar,
                            modifier,
                            bodyModifier = holdSwitch,
                            focusRequester = focusRequester,
                            onAddLatestToQueue = { onAddLatestShowToQueue(item) },
                            onClick = { onShow(item) }
                        )
                    }
                }
                if (hasMore) {
                    item(key = "more") {
                        LoadMoreRow(
                            "Load more ${scope.label.lowercase()}",
                            morePending,
                            onLoadMore = {
                                moreFocus.onLoadMore(items.size)
                                onLoadMore(scope)
                            },
                            modifier = Modifier.listFade(listState, items.size + 1),
                            focusState = moreFocus
                        )
                    }
                }
            }
        }
    }
}

internal fun SearchResults.itemsFor(scope: SearchScope): List<Any> = when (scope) {
    SearchScope.Tracks -> tracks
    SearchScope.Artists -> artists
    SearchScope.Albums -> albums
    SearchScope.Playlists -> playlists
    SearchScope.Shows -> shows
}

internal fun SearchResults.sizeFor(scope: SearchScope): Int = itemsFor(scope).size

private fun searchItemKey(item: Any): String = when (item) {
    is Track -> "t${item.id}"
    is Artist -> "r${item.id}"
    is Album -> "a${item.id}"
    is Playlist -> "p${item.id}"
    is Show -> "s${item.id}"
    else -> item.toString()
}

private val SearchScope.icon: SidekitIcon
    get() = when (this) {
        SearchScope.Tracks -> SidekitIcon.MusicNote2
        SearchScope.Artists -> SidekitIcon.Artist
        SearchScope.Albums -> SidekitIcon.Album
        SearchScope.Playlists -> SidekitIcon.QueueMusic
        SearchScope.Shows -> SidekitIcon.SmartDisplay
    }

/** Opens the category chooser from the results page without giving up the term being read. */
private fun searchInOption(onClick: () -> Unit) = SideAction(
    key = "searchIn",
    label = "Search in",
    icon = SidekitIcon.Search,
    onClick = onClick
)

/** Lets the user choose which search categories are queried without hiding them in Options. */
@Composable
fun SearchScopesScreen(
    search: SearchUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val baseActions = remember { listOf(sidetrackBack("Search", onBack).first()) }
    val actions = sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    SidekitScaffold(
        title = "Search in",
        actions = actions,
        options = sidetrackOptions(onNowPlaying = onNowPlaying)
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "label") {
                SectionLabel("Categories")
            }
            SearchScope.entries.forEachIndexed { index, scope ->
                item(key = scope.name) {
                    // A category the service cannot answer is shown, not hidden: the list keeps
                    // its shape on every service, and "Not available" says why it cannot be
                    // turned on instead of leaving the user to guess.
                    val available = scope in supportedScopes
                    val included = available && scope in search.searchScopes
                    // Never let the last category be turned off: the search page has no list to
                    // show without one, and an empty scope set used to mean "everything". The row
                    // stays focusable so its checked, disabled state is still announced.
                    val locked = included &&
                        search.searchScopes.count { it in supportedScopes } == 1
                    SidetrackItem(
                        scope.label,
                        modifier = Modifier.listFade(listState, index + 1)
                            .semantics { if (locked || !available) disabled() },
                        secondary = when {
                            !available -> "Not available"
                            locked -> "On, required"
                            included -> "On"
                            else -> "Off"
                        },
                        secondaryToEnd = true,
                        icon = if (included) SidekitIcon.Check else null,
                        toggleable = included,
                        toggleRole = Role.Checkbox,
                        onClick = { if (available && !locked) onToggleScope(scope) }
                    )
                }
            }
        }
    }
}
