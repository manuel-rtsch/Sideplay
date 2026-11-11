package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.SwipeAction
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup

private const val SEARCH_SECTION_PAGE = 10

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
    onDismissNotice: () -> Unit
) {
    var expanded by remember(state.query) { mutableStateOf(emptyMap<SearchScope, Int>()) }
    val results = state.searchResults
    fun visible(scope: SearchScope, total: Int): Int = minOf(expanded[scope] ?: SEARCH_SECTION_PAGE, total)
    fun showMore(scope: SearchScope, total: Int) {
        val current = visible(scope, total)
        expanded = expanded + (scope to (current + SEARCH_SECTION_PAGE))
        if (current >= total && scope in state.searchHasMore) onLoadMore(scope)
    }
    val actions = withNotice(
        state.notice,
        onDismissNotice,
        buildList {
            add(spotifyBack("Back", onBack).first())
            if (state.query.isNotEmpty()) add(SideAction("clear", "Clear", SidekitIcon.Close, tone = ActionTone.Muted, onClick = onClear))
        }
    )
    val scopes = SearchScope.entries.map { scope ->
        SideAction(
            "scope.${scope.name}",
            scope.label,
            tone = if (scope in state.searchScopes) ActionTone.Primary else ActionTone.Muted,
            onClick = { onToggleScope(scope) }
        )
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
        options = scopes
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            when {
                state.query.isBlank() -> item(key = "idle") {
                    EmptyMessage("Search your library and Spotify", hint = "Tracks, artists, albums, playlists and shows")
                }

                state.query.trim().length < 2 -> item(key = "short") {
                    EmptyMessage("Keep typing", hint = "Type at least two letters to search")
                }

                state.searchPending && results.isEmpty -> item(key = "loading") {
                    EmptyMessage("Searching…", hint = "Spotify is looking for matches")
                }

                results.isEmpty -> item(key = "noResults") {
                    EmptyMessage("No results for \"${state.query.trim()}\"", hint = "Try another spelling or turn on more scopes in Options")
                }

                else -> {
                    var slot = 0
                    if (results.tracks.isNotEmpty()) {
                        item(key = "tracksLabel") { SectionLabel("Tracks") }
                        val shown = results.tracks.take(visible(SearchScope.Tracks, results.tracks.size))
                        itemsIndexed(shown, key = { _, track -> "t${track.id}" }) { index, track ->
                            SidekitSwipeRow(
                                key = track.id,
                                group = swipes,
                                modifier = Modifier.listFade(listState, slot + index),
                                action = SwipeAction(
                                    label = "Add ${track.title} to queue",
                                    icon = SidekitIcon.QueueMusic,
                                    color = SidekitTheme.colors.primaryA1,
                                    armedColor = SidekitTheme.colors.primaryA2,
                                    onClick = { onQueue(track) }
                                )
                            ) {
                                SpotifyMediaRow(track, Modifier, secondary = track.artist) { onTrack(track) }
                            }
                        }
                        slot += shown.size
                        if (shown.size < results.tracks.size || SearchScope.Tracks in state.searchHasMore) {
                            item(key = "moreTracks") {
                                SearchMoreRow(SearchScope.Tracks, "More tracks", state) { showMore(SearchScope.Tracks, results.tracks.size) }
                            }
                        }
                    }
                    if (results.artists.isNotEmpty()) {
                        item(key = "artistsLabel") { SectionLabel("Artists") }
                        val shown = results.artists.take(visible(SearchScope.Artists, results.artists.size))
                        itemsIndexed(shown, key = { _, artist -> "r${artist.id}" }) { index, artist ->
                            SpotifyLibraryRow(artist.artRes, artist.name, "Artist", Modifier.listFade(listState, slot + index), artUrl = artist.artUrl) { onArtist(artist) }
                        }
                        slot += shown.size
                        if (shown.size < results.artists.size || SearchScope.Artists in state.searchHasMore) {
                            item(key = "moreArtists") {
                                SearchMoreRow(SearchScope.Artists, "More artists", state) { showMore(SearchScope.Artists, results.artists.size) }
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        item(key = "albumsLabel") { SectionLabel("Albums") }
                        val shown = results.albums.take(visible(SearchScope.Albums, results.albums.size))
                        itemsIndexed(shown, key = { _, album -> "a${album.id}" }) { index, album ->
                            SpotifyLibraryRow(album.artRes, album.title, "${album.artist} · ${album.year}", Modifier.listFade(listState, slot + index), artUrl = album.artUrl) { onAlbum(album) }
                        }
                        slot += shown.size
                        if (shown.size < results.albums.size || SearchScope.Albums in state.searchHasMore) {
                            item(key = "moreAlbums") {
                                SearchMoreRow(SearchScope.Albums, "More albums", state) { showMore(SearchScope.Albums, results.albums.size) }
                            }
                        }
                    }
                    if (results.playlists.isNotEmpty()) {
                        item(key = "playlistsLabel") { SectionLabel("Playlists") }
                        val shown = results.playlists.take(visible(SearchScope.Playlists, results.playlists.size))
                        itemsIndexed(shown, key = { _, playlist -> "p${playlist.id}" }) { index, playlist ->
                            SpotifyLibraryRow(playlist.artRes, playlist.name, "${playlist.owner} · ${playlist.trackCount} tracks", Modifier.listFade(listState, slot + index), artUrl = playlist.artUrl) { onPlaylist(playlist) }
                        }
                        slot += shown.size
                        if (shown.size < results.playlists.size || SearchScope.Playlists in state.searchHasMore) {
                            item(key = "morePlaylists") {
                                SearchMoreRow(SearchScope.Playlists, "More playlists", state) { showMore(SearchScope.Playlists, results.playlists.size) }
                            }
                        }
                    }
                    if (results.shows.isNotEmpty()) {
                        item(key = "showsLabel") { SectionLabel("Shows") }
                        val shown = results.shows.take(visible(SearchScope.Shows, results.shows.size))
                        itemsIndexed(shown, key = { _, show -> "s${show.id}" }) { index, show ->
                            SpotifyLibraryRow(show.artRes, show.name, show.publisher, Modifier.listFade(listState, slot + index), artUrl = show.artUrl) { onShow(show) }
                        }
                        if (shown.size < results.shows.size || SearchScope.Shows in state.searchHasMore) {
                            item(key = "moreShows") {
                                SearchMoreRow(SearchScope.Shows, "More shows", state) { showMore(SearchScope.Shows, results.shows.size) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchMoreRow(
    scope: SearchScope,
    label: String,
    state: SpotifyUiState,
    onMore: () -> Unit
) {
    val loading = scope in state.searchMorePending
    TerminalRow(
        label,
        secondary = if (loading) "Loading…" else null,
        onClick = if (loading) null else onMore
    )
}
