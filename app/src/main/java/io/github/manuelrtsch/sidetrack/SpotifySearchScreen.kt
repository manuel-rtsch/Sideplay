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
import androidx.compose.ui.focus.focusProperties
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

@Composable
fun SpotifySearchScreen(
    search: SearchUiState,
    saved: SavedUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onAddToQueue: (SpotifyTrack) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onShow: (SpotifyShow) -> Unit,
    onSaveTrack: (SpotifyTrack) -> Unit,
    onEnsureSavedTrack: (SpotifyTrack) -> Unit,
    onLoadMore: (SearchScope) -> Unit,
    onRetry: () -> Unit,
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val results = search.searchResults
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddToQueue
    )
    val baseActions = remember(search.query) {
        buildList {
            add(spotifyBack("Back", onBack).first())
            if (search.query.isNotEmpty()) add(SideAction("clear", "Clear", SidekitIcon.Close, tone = ActionTone.Muted, onClick = onClear))
        }
    }
    val actions = rowActionBarActions(
        rows.bar,
        spotifyActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    // The right side of the "Search in" row must stay on one line. The only selection that
    // overflows is four of five scopes, so name the one that is left out instead of listing four.
    val scopeSummary = when (search.searchScopes.size) {
        SearchScope.entries.size -> "Everything"

        0 -> "Nothing"

        1 -> SearchScope.entries.first { it in search.searchScopes }.label

        SearchScope.entries.size - 1 ->
            "All but " + SearchScope.entries.first { it !in search.searchScopes }.label

        else -> SearchScope.entries.filter { it in search.searchScopes }.joinToString(", ") { it.label }
    }
    val searchFocus = remember { FocusRequester() }
    val scopesFocus = remember { FocusRequester() }
    val filterFocus = remember { SearchScope.entries.associateWith { FocusRequester() } }
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
    val populated = SearchScope.entries.firstOrNull { it in search.searchScopes && results.sizeFor(it) > 0 }
    // A query may return nothing in the selected category, and toggling scopes can remove the
    // selected category entirely. Move to an included category with results first, then to any
    // included category, so the buttons and the list never disagree.
    LaunchedEffect(search.query, search.searchScopes, populated) {
        if (scope !in search.searchScopes) {
            SearchScope.entries.firstOrNull { it in search.searchScopes }?.let { scope = it }
        } else if (populated != null && results.sizeFor(scope) == 0) {
            scope = populated
        }
    }
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
    val firstResultModifier = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
            filterFocus.getValue(scope).requestFocus()
            true
        } else {
            false
        }
    }
    // The category row sits above the list, so a Down from the last row must not wrap back into
    // it. Cancel Down there; the row is only reachable by walking Up out of the list.
    val lastRowModifier = Modifier.focusProperties { down = FocusRequester.Cancel }
    SidekitScaffold(
        title = null,
        modifier = Modifier.dismissRowActionsOnVerticalKey(rows.bar),
        header = {
            Box(
                Modifier.fillMaxWidth().height(SidekitTheme.spacing.headerHeight)
                    .padding(horizontal = SidekitTheme.spacing.itemPadding).padding(top = SidekitTheme.spacing.headerPaddingTop)
            ) {
                SidekitTextField(
                    search.query,
                    onQuery,
                    Modifier.fillMaxWidth().semantics { contentDescription = "Search Spotify" },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Search Spotify",
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
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        // A new scope or query shows a different page from the top; keeping the old scroll offset
        // would open it in the middle.
        LaunchedEffect(search.query, scope) { listState.scrollToItem(0) }
        if (failed) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                SpotifyStatusScreen(
                    "Couldn't search Spotify",
                    hint = search.searchError,
                    actionLabel = "Try again",
                    onAction = onRetry
                )
            }
            return@SidekitScaffold
        }
        SidekitList(padding, state = listState) {
            if (!showFilters) {
                // Idle and short-query states keep the "Search in" row: this is where the user
                // decides what the next search will look at.
                item(key = "scopes") {
                    TerminalRow(
                        "Search in",
                        modifier = Modifier.listFade(listState, 0)
                            .then(scopesModifier)
                            .then(lastRowModifier),
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
                                hint = "Looking on Spotify"
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
                        onSelect = { scope = it },
                        focusRequesters = filterFocus,
                        enabled = { it in search.searchScopes },
                        modifier = Modifier.listFade(listState, 0)
                            .padding(bottom = SidekitTheme.spacing.pageGap)
                            .then(filterRowModifier)
                    )
                }
                val items = results.itemsFor(scope)
                if (items.isEmpty()) {
                    item(key = "empty") {
                        EmptyMessage(
                            "No ${scope.label.lowercase()} for \"${search.query.trim()}\"",
                            Modifier.listFade(listState, 1),
                            hint = "Try another spelling or keyword"
                        )
                    }
                } else {
                    val hasMore = scope in search.searchHasMore
                    itemsIndexed(
                        items,
                        key = { _, item -> searchItemKey(item) },
                        contentType = { _, item -> searchItemKey(item).take(1) }
                    ) { index, item ->
                        val modifier = Modifier.listFade(listState, index + 1)
                            .then(if (index == 0) firstResultModifier else Modifier)
                            .then(if (index == items.lastIndex && !hasMore) lastRowModifier else Modifier)
                        when (item) {
                            is SpotifyTrack -> TrackRow(
                                searchItemKey(item),
                                item,
                                rows,
                                modifier
                            ) { onTrack(item) }

                            is SpotifyArtist -> SpotifyLibraryRow(
                                item.artRes,
                                item.name,
                                "Artist",
                                modifier,
                                artUrl = item.artUrl
                            ) { onArtist(item) }

                            is SpotifyAlbum -> SpotifyLibraryRow(
                                item.artRes,
                                item.title,
                                if (item.year > 0) "${item.artist} · ${item.year}" else item.artist,
                                modifier,
                                artUrl = item.artUrl
                            ) { onAlbum(item) }

                            is SpotifyPlaylist -> SpotifyLibraryRow(
                                item.artRes,
                                item.name,
                                playlistSubtitle(item),
                                modifier,
                                artUrl = item.artUrl
                            ) { onPlaylist(item) }

                            is SpotifyShow -> SpotifyLibraryRow(
                                item.artRes,
                                item.name,
                                item.publisher,
                                modifier,
                                artUrl = item.artUrl
                            ) { onShow(item) }
                        }
                    }
                    if (hasMore) {
                        item(key = "more") {
                            LoadMoreRow(
                                "Load more ${scope.label.lowercase()}",
                                scope in search.searchMorePending,
                                onLoadMore = { onLoadMore(scope) },
                                modifier = Modifier.listFade(listState, items.size + 1)
                                    .then(lastRowModifier)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SpotifySearchResults.itemsFor(scope: SearchScope): List<Any> = when (scope) {
    SearchScope.Tracks -> tracks
    SearchScope.Artists -> artists
    SearchScope.Albums -> albums
    SearchScope.Playlists -> playlists
    SearchScope.Shows -> shows
}

private fun SpotifySearchResults.sizeFor(scope: SearchScope): Int = itemsFor(scope).size

private fun searchItemKey(item: Any): String = when (item) {
    is SpotifyTrack -> "t${item.id}"
    is SpotifyArtist -> "r${item.id}"
    is SpotifyAlbum -> "a${item.id}"
    is SpotifyPlaylist -> "p${item.id}"
    is SpotifyShow -> "s${item.id}"
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
    onBack: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val baseActions = remember { listOf(spotifyBack("Search", onBack).first()) }
    val actions = spotifyActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    SidekitScaffold(
        title = "Search in",
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "label") {
                SectionLabel("Categories")
            }
            SearchScope.entries.forEachIndexed { index, scope ->
                item(key = scope.name) {
                    val included = scope in search.searchScopes
                    // Never let the last category be turned off: the search page has no list to
                    // show without one, and an empty scope set used to mean "everything". The row
                    // stays focusable so its checked, disabled state is still announced.
                    val locked = included && search.searchScopes.size == 1
                    SidetrackItem(
                        scope.label,
                        modifier = Modifier.listFade(listState, index + 1)
                            .semantics { if (locked) disabled() },
                        secondary = when {
                            locked -> "On, required"
                            included -> "On"
                            else -> "Off"
                        },
                        icon = if (included) SidekitIcon.Check else null,
                        toggleable = included,
                        toggleRole = Role.Checkbox,
                        onClick = { if (!locked) onToggleScope(scope) }
                    )
                }
            }
        }
    }
}
