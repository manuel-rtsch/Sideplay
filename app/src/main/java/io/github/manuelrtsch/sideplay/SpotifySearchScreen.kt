package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
    onScopes: () -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val results = state.searchResults
    val actions = withNotice(
        state.notice,
        onDismissNotice,
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
    val focus = remember { FocusRequester() }
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
                    focusRequester = focus,
                    focusOnKeypad = true,
                    singleLine = true,
                    autoFocus = true
                )
            }
        },
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            val scopeIndex = rowIndex
            item(key = "scopes") {
                TerminalRow(
                    "Search in",
                    modifier = Modifier.listFade(listState, scopeIndex),
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
                            hint = "Tracks, artists, albums, playlists and shows"
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
                            hint = "Try another spelling or change Search in"
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
                                SpotifyMediaRow(track, Modifier, secondary = track.artist) { onTrack(track) }
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
                                Modifier.listFade(listState, base + index),
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
                                Modifier.listFade(listState, base + index),
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
                                Modifier.listFade(listState, base + index),
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
                                Modifier.listFade(listState, base + index),
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
    onDismissNotice: () -> Unit
) {
    val actions = withNotice(
        state.notice,
        onDismissNotice,
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
