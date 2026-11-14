package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.SwipeAction
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup

@Composable
fun SpotifySearchScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onQuery: (String) -> Unit,
    onClear: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
    onTrack: (SpotifyTrack) -> Unit,
    onQueue: (SpotifyTrack) -> Unit,
    onArtist: (SpotifyArtist) -> Unit,
    onAlbum: (SpotifyAlbum) -> Unit,
    onPlaylist: (SpotifyPlaylist) -> Unit,
    onShow: (SpotifyShow) -> Unit,
    onLoadMore: (SearchScope) -> Unit,
    onRetry: () -> Unit,
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val results = state.searchResults
    val actions = spotifyActions(
        state,
        onDismissNotice,
        onNowPlaying,
        onDismissNowPlaying,
        buildList {
            add(spotifyBack("Back", onBack).first())
            if (state.query.isNotEmpty()) add(SideAction("clear", "Clear", SidekitIcon.Close, tone = ActionTone.Muted, onClick = onClear))
        }
    )
    val scopeSummary = when (state.searchScopes.size) {
        SearchScope.entries.size -> "Everything"
        0 -> "Nothing"
        else -> SearchScope.entries.filter { it in state.searchScopes }.joinToString(", ") { it.label }
    }
    val searchFocus = remember { FocusRequester() }
    val scopesFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val firstResultScope = if (state.query.trim().length < 2 || results.isEmpty) {
        null
    } else {
        when {
            results.tracks.isNotEmpty() -> SearchScope.Tracks
            results.artists.isNotEmpty() -> SearchScope.Artists
            results.albums.isNotEmpty() -> SearchScope.Albums
            results.playlists.isNotEmpty() -> SearchScope.Playlists
            results.shows.isNotEmpty() -> SearchScope.Shows
            else -> null
        }
    }
    val lastResultScope = when {
        results.shows.isNotEmpty() -> SearchScope.Shows
        results.playlists.isNotEmpty() -> SearchScope.Playlists
        results.albums.isNotEmpty() -> SearchScope.Albums
        results.artists.isNotEmpty() -> SearchScope.Artists
        results.tracks.isNotEmpty() -> SearchScope.Tracks
        else -> null
    }
    val lastResultIsSeeMore = lastResultScope != null && lastResultScope in state.searchHasMore
    // Enter in the search field hands the keypad to the results. Up walks back out: first result
    // to "Search in", then "Search in" to the field, so typing and browsing never fight over keys.
    // Preview handlers run before the focused row's own D-pad handling, which would otherwise
    // consume Up and let focus search fall back to the search field.
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
    val firstResultModifier = Modifier
        .focusRequester(firstResultFocus)
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                scopesFocus.requestFocus()
                true
            } else {
                false
            }
        }
    // The search field sits above the list, so a Down from the last row must not wrap back into
    // it. Cancel Down there; the field is only reachable by walking Up out of the list.
    val lastRowModifier = Modifier.focusProperties { down = FocusRequester.Cancel }
    fun resultModifier(scope: SearchScope, index: Int, lastIndex: Int): Modifier {
        var modifier: Modifier = Modifier
        if (firstResultScope == scope && index == 0) modifier = modifier.then(firstResultModifier)
        if (lastResultScope == scope && !lastResultIsSeeMore && index == lastIndex) modifier = modifier.then(lastRowModifier)
        return modifier
    }
    SidekitScaffold(
        title = null,
        header = {
            Box(
                Modifier.fillMaxWidth().height(SidekitTheme.spacing.headerHeight)
                    .padding(horizontal = SidekitTheme.spacing.itemPadding).padding(top = SidekitTheme.spacing.headerPaddingTop)
            ) {
                SidekitTextField(
                    state.query,
                    onQuery,
                    Modifier.fillMaxWidth().semantics { contentDescription = "Search Spotify" },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Search Spotify",
                    focusRequester = searchFocus,
                    focusOnKeypad = true,
                    singleLine = true,
                    autoFocus = true,
                    onDone = { if (firstResultScope == null) scopesFocus.requestFocus() else firstResultFocus.requestFocus() }
                )
            }
        },
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        if (state.query.trim().length >= 2 && !state.searchPending && state.searchError != null && results.isEmpty) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                SpotifyStatusScreen(
                    "Couldn't search Spotify",
                    hint = state.searchError,
                    actionLabel = "Try again",
                    onAction = onRetry
                )
            }
            return@SidekitScaffold
        }
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            val scopeIndex = rowIndex
            item(key = "scopes") {
                TerminalRow(
                    "Search in",
                    modifier = Modifier.listFade(listState, scopeIndex)
                        .then(scopesModifier)
                        .then(if (lastResultScope == null) lastRowModifier else Modifier),
                    secondary = scopeSummary,
                    onClick = onScopes
                )
            }
            rowIndex += 1
            when {
                state.query.isBlank() -> {
                    val index = rowIndex
                    item(key = "idle") {
                        EmptyMessage(
                            "Search your library and Spotify",
                            Modifier.listFade(listState, index),
                            hint = "Tracks, artists, albums and more"
                        )
                    }
                }

                state.query.trim().length < 2 -> {
                    val index = rowIndex
                    item(key = "short") {
                        EmptyMessage(
                            "Keep typing",
                            Modifier.listFade(listState, index),
                            hint = "Type at least two letters to search"
                        )
                    }
                }

                state.searchPending && results.isEmpty -> {
                    val index = rowIndex
                    item(key = "loading") {
                        EmptyMessage(
                            "Searching…",
                            Modifier.listFade(listState, index),
                            hint = "Spotify is looking for matches"
                        )
                    }
                }

                results.isEmpty -> {
                    val index = rowIndex
                    item(key = "noResults") {
                        EmptyMessage(
                            "No results for \"${state.query.trim()}\"",
                            Modifier.listFade(listState, index),
                            hint = "Try another spelling or keyword"
                        )
                    }
                }

                else -> {
                    if (results.tracks.isNotEmpty()) {
                        val labelIndex = rowIndex
                        item(key = "tracksLabel") {
                            SectionLabel("Tracks", Modifier.listFade(listState, labelIndex))
                        }
                        rowIndex += 1
                        val base = rowIndex
                        itemsIndexed(results.tracks, key = { _, track -> "t${track.id}" }) { index, track ->
                            SidekitSwipeRow(
                                key = track.id,
                                group = swipes,
                                modifier = Modifier.listFade(listState, base + index),
                                action = SwipeAction(
                                    label = "Add ${track.title} to queue",
                                    icon = SidekitIcon.QueueMusic,
                                    color = SidekitTheme.colors.successA1,
                                    armedColor = SidekitTheme.colors.successA2,
                                    onClick = { onQueue(track) }
                                )
                            ) {
                                SpotifyMediaRow(
                                    track,
                                    resultModifier(SearchScope.Tracks, index, results.tracks.lastIndex),
                                    secondary = track.artist
                                ) { onTrack(track) }
                            }
                        }
                        rowIndex += results.tracks.size
                        if (SearchScope.Tracks in state.searchHasMore) {
                            val moreIndex = rowIndex
                            item(key = "moreTracks") {
                                SeeMoreRow(
                                    "See more tracks",
                                    SearchScope.Tracks in state.searchMorePending,
                                    onClick = { onLoadMore(SearchScope.Tracks) },
                                    modifier = Modifier.listFade(listState, moreIndex)
                                        .then(if (lastResultScope == SearchScope.Tracks && lastResultIsSeeMore) lastRowModifier else Modifier)
                                )
                            }
                            rowIndex += 1
                        }
                    }
                    if (results.artists.isNotEmpty()) {
                        val labelIndex = rowIndex
                        item(key = "artistsLabel") {
                            SectionLabel("Artists", Modifier.listFade(listState, labelIndex))
                        }
                        rowIndex += 1
                        val base = rowIndex
                        itemsIndexed(results.artists, key = { _, artist -> "r${artist.id}" }) { index, artist ->
                            SpotifyLibraryRow(
                                artist.artRes,
                                artist.name,
                                "Artist",
                                Modifier.listFade(listState, base + index)
                                    .then(resultModifier(SearchScope.Artists, index, results.artists.lastIndex)),
                                artUrl = artist.artUrl
                            ) { onArtist(artist) }
                        }
                        rowIndex += results.artists.size
                        if (SearchScope.Artists in state.searchHasMore) {
                            val moreIndex = rowIndex
                            item(key = "moreArtists") {
                                SeeMoreRow(
                                    "See more artists",
                                    SearchScope.Artists in state.searchMorePending,
                                    onClick = { onLoadMore(SearchScope.Artists) },
                                    modifier = Modifier.listFade(listState, moreIndex)
                                        .then(if (lastResultScope == SearchScope.Artists && lastResultIsSeeMore) lastRowModifier else Modifier)
                                )
                            }
                            rowIndex += 1
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        val labelIndex = rowIndex
                        item(key = "albumsLabel") {
                            SectionLabel("Albums", Modifier.listFade(listState, labelIndex))
                        }
                        rowIndex += 1
                        val base = rowIndex
                        itemsIndexed(results.albums, key = { _, album -> "a${album.id}" }) { index, album ->
                            SpotifyLibraryRow(
                                album.artRes,
                                album.title,
                                if (album.year > 0) "${album.artist} · ${album.year}" else album.artist,
                                Modifier.listFade(listState, base + index)
                                    .then(resultModifier(SearchScope.Albums, index, results.albums.lastIndex)),
                                artUrl = album.artUrl
                            ) { onAlbum(album) }
                        }
                        rowIndex += results.albums.size
                        if (SearchScope.Albums in state.searchHasMore) {
                            val moreIndex = rowIndex
                            item(key = "moreAlbums") {
                                SeeMoreRow(
                                    "See more albums",
                                    SearchScope.Albums in state.searchMorePending,
                                    onClick = { onLoadMore(SearchScope.Albums) },
                                    modifier = Modifier.listFade(listState, moreIndex)
                                        .then(if (lastResultScope == SearchScope.Albums && lastResultIsSeeMore) lastRowModifier else Modifier)
                                )
                            }
                            rowIndex += 1
                        }
                    }
                    if (results.playlists.isNotEmpty()) {
                        val labelIndex = rowIndex
                        item(key = "playlistsLabel") {
                            SectionLabel("Playlists", Modifier.listFade(listState, labelIndex))
                        }
                        rowIndex += 1
                        val base = rowIndex
                        itemsIndexed(results.playlists, key = { _, playlist -> "p${playlist.id}" }) { index, playlist ->
                            SpotifyLibraryRow(
                                playlist.artRes,
                                playlist.name,
                                playlistSubtitle(playlist),
                                Modifier.listFade(listState, base + index)
                                    .then(resultModifier(SearchScope.Playlists, index, results.playlists.lastIndex)),
                                artUrl = playlist.artUrl
                            ) { onPlaylist(playlist) }
                        }
                        rowIndex += results.playlists.size
                        if (SearchScope.Playlists in state.searchHasMore) {
                            val moreIndex = rowIndex
                            item(key = "morePlaylists") {
                                SeeMoreRow(
                                    "See more playlists",
                                    SearchScope.Playlists in state.searchMorePending,
                                    onClick = { onLoadMore(SearchScope.Playlists) },
                                    modifier = Modifier.listFade(listState, moreIndex)
                                        .then(if (lastResultScope == SearchScope.Playlists && lastResultIsSeeMore) lastRowModifier else Modifier)
                                )
                            }
                            rowIndex += 1
                        }
                    }
                    if (results.shows.isNotEmpty()) {
                        val labelIndex = rowIndex
                        item(key = "showsLabel") {
                            SectionLabel("Shows", Modifier.listFade(listState, labelIndex))
                        }
                        rowIndex += 1
                        val base = rowIndex
                        itemsIndexed(results.shows, key = { _, show -> "s${show.id}" }) { index, show ->
                            SpotifyLibraryRow(
                                show.artRes,
                                show.name,
                                show.publisher,
                                Modifier.listFade(listState, base + index)
                                    .then(resultModifier(SearchScope.Shows, index, results.shows.lastIndex)),
                                artUrl = show.artUrl
                            ) { onShow(show) }
                        }
                        rowIndex += results.shows.size
                        if (SearchScope.Shows in state.searchHasMore) {
                            val moreIndex = rowIndex
                            item(key = "moreShows") {
                                SeeMoreRow(
                                    "See more shows",
                                    SearchScope.Shows in state.searchMorePending,
                                    onClick = { onLoadMore(SearchScope.Shows) },
                                    modifier = Modifier.listFade(listState, moreIndex)
                                        .then(if (lastResultScope == SearchScope.Shows && lastResultIsSeeMore) lastRowModifier else Modifier)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Lets the user choose which search categories are queried without hiding them in Options. */
@Composable
fun SearchScopesScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onToggleScope: (SearchScope) -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val actions = spotifyActions(
        state,
        onDismissNotice,
        onNowPlaying,
        onDismissNowPlaying,
        listOf(spotifyBack("Search", onBack).first())
    )
    SidekitScaffold(
        title = "Search in",
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "label") {
                SectionLabel("Categories", Modifier.listFade(listState, 0))
            }
            SearchScope.entries.forEachIndexed { index, scope ->
                item(key = scope.name) {
                    val included = scope in state.searchScopes
                    SidekitItem(
                        scope.label,
                        modifier = Modifier.listFade(listState, index + 1),
                        secondary = if (included) "On" else "Off",
                        icon = if (included) SidekitIcon.Check else null,
                        onClick = { onToggleScope(scope) }
                    )
                }
            }
        }
    }
}
