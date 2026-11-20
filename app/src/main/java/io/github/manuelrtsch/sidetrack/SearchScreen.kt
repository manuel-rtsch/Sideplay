package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState

@Composable
fun SearchScreen(
    search: SearchUiState,
    saved: SavedUiState,
    chrome: ChromeUiState,
    supportedScopes: Set<SearchScope> = SearchScope.entries.toSet(),
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
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
    val baseActions = remember(search.query) {
        buildList {
            add(sidetrackBack("Back", onBack).first())
            if (search.query.isNotEmpty()) add(SideAction("clear", "Clear", SidekitIcon.Close, tone = ActionTone.Muted, onClick = onClear))
        }
    }
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
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
    val searchFocus = remember { FocusRequester() }
    val scopesFocus = remember { FocusRequester() }
    val filterFocus = remember { SearchScope.entries.associateWith { FocusRequester() } }
    val moreFocus = rememberLoadMoreFocus()
    // Artist/album/playlist/show rows share one gutter state so their action squares behave like
    // the library's, and the list settles under the cursor into a reading position.
    val searchGutters = rememberRowGutterState()
    // The gate outlives any one row: after a held arrow switches scope, the list may rebuild and
    // move focus before the physical key comes up. The screen root uses the same gate to swallow
    // the repeats and the release so the switch cannot walk sideways through every scope.
    val holdGate = remember { CategoryHoldGate() }
    // The category buttons only choose which result list is shown. Which categories get queried is
    // still decided on the "Search in" page, and that page is reached from the idle/typing state.
    // The selection survives a trip into a result and back.
    var scope by rememberSaveable { mutableStateOf(SearchScope.Tracks) }
    val hasQuery = search.query.trim().length >= 2
    val loading = hasQuery && search.searchPending && results.isEmpty
    val failed = hasQuery && !search.searchPending && search.searchError != null && results.isEmpty
    // Once there is a query the page is about browsing results: either the list or the reason there
    // is none. The "Search in" row belongs to the state where the query is still being typed.
    val showFilters = hasQuery && !loading && !failed
    // The categories that are both in the queried set and answerable by the connected service.
    // Moving the row (and the selection) only ever walks these.
    val includedScopes = SearchScope.entries.filter { it in supportedScopes && it in search.searchScopes }.toSet()
    // A failed search replaces the list with the retry body. Drop a page that was in flight so a
    // later retry cannot mistake its larger result set for a page that just arrived.
    LaunchedEffect(failed) { if (failed) moreFocus.reset() }
    // Enter in the search field hands the keypad to the result categories. Up walks back out: first
    // result to the category buttons, category buttons to the field, so typing and browsing never
    // fight over keys. Preview handlers run before the focused row's own D-pad handling, which
    // would otherwise consume Up and let focus search fall back to the search field.
    val scopesModifier = Modifier
        .focusRequester(scopesFocus)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                searchFocus.requestFocus()
                true
            } else {
                false
            }
        }
    val filterRowModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            searchFocus.requestFocus()
            true
        } else {
            false
        }
    }
    // The category row sits above the list, so Up from the first result returns to it. Down from
    // the last row is left to focus search, which finds the action bar below the list.
    SidekitScaffold(
        title = null,
        modifier = Modifier
            .dismissRowActionsOnVerticalKey(rows.bar)
            .consumeCategoryHoldRepeats(holdGate),
        header = {
            Box(
                Modifier.fillMaxWidth().height(SidekitTheme.spacing.headerHeight)
                    .padding(horizontal = SidekitTheme.spacing.itemPadding).padding(top = SidekitTheme.spacing.headerPaddingTop)
            ) {
                SidekitTextField(
                    search.query,
                    onQuery,
                    Modifier.fillMaxWidth().semantics { contentDescription = "Search" },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Search",
                    focusRequester = searchFocus,
                    focusOnKeypad = true,
                    singleLine = true,
                    autoFocus = true,
                    onDone = {
                        when {
                            showFilters -> filterFocus.getValue(scope).requestFocus()
                            !hasQuery -> scopesFocus.requestFocus()
                            else -> Unit
                        }
                    }
                )
            }
        },
        actions = actions,
        options = sidetrackOptions(onNowPlaying = onNowPlaying)
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
        val items = results.itemsFor(scope)
        val hasMore = scope in search.searchHasMore
        val morePending = scope in search.searchMorePending
        LoadMoreFocus(moreFocus, listState, items.size, morePending, firstItemListIndex = 1)
        SidekitList(padding, state = listState) {
            if (!showFilters) {
                // Idle and short-query states keep the "Search in" row: this is where the user
                // decides what the next search will look at.
                item(key = "scopes") {
                    TerminalRow(
                        "Search in",
                        modifier = Modifier.listFade(listState, 0)
                            .then(scopesModifier),
                        secondary = scopeSummary,
                        onClick = onScopes
                    )
                }
                when {
                    search.query.isBlank() -> {
                        item(key = "idle") {
                            EmptyMessage(
                                "Type at least two letters",
                                Modifier.listFade(listState, 1)
                            )
                        }
                    }

                    search.query.trim().length < 2 -> {
                        item(key = "short") {
                            EmptyMessage(
                                "Keep typing",
                                Modifier.listFade(listState, 1)
                            )
                        }
                    }

                    else -> {
                        item(key = "loading") {
                            EmptyMessage(
                                "Searching…",
                                Modifier.listFade(listState, 1),
                                hint = "Searching"
                            )
                        }
                    }
                }
            } else {
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
                            .then(filterRowModifier)
                    )
                }
                if (items.isEmpty()) {
                    item(key = "empty") {
                        EmptyMessage(
                            "No ${scope.label.lowercase()} for \"${search.query.trim()}\"",
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
