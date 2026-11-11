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
                        itemsIndexed(results.tracks, key = { _, track -> "t${track.id}" }) { index, track ->
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
                        slot += results.tracks.size
                        if (SearchScope.Tracks in state.searchHasMore) {
                            item(key = "moreTracks") {
                                SeeMoreRow("See more tracks", SearchScope.Tracks in state.searchMorePending) {
                                    onLoadMore(SearchScope.Tracks)
                                }
                            }
                        }
                    }
                    if (results.artists.isNotEmpty()) {
                        item(key = "artistsLabel") { SectionLabel("Artists") }
                        itemsIndexed(results.artists, key = { _, artist -> "r${artist.id}" }) { index, artist ->
                            SpotifyLibraryRow(artist.artRes, artist.name, "Artist", Modifier.listFade(listState, slot + index), artUrl = artist.artUrl) { onArtist(artist) }
                        }
                        slot += results.artists.size
                        if (SearchScope.Artists in state.searchHasMore) {
                            item(key = "moreArtists") {
                                SeeMoreRow("See more artists", SearchScope.Artists in state.searchMorePending) {
                                    onLoadMore(SearchScope.Artists)
                                }
                            }
                        }
                    }
                    if (results.albums.isNotEmpty()) {
                        item(key = "albumsLabel") { SectionLabel("Albums") }
                        itemsIndexed(results.albums, key = { _, album -> "a${album.id}" }) { index, album ->
                            SpotifyLibraryRow(album.artRes, album.title, "${album.artist} · ${album.year}", Modifier.listFade(listState, slot + index), artUrl = album.artUrl) { onAlbum(album) }
                        }
                        slot += results.albums.size
                        if (SearchScope.Albums in state.searchHasMore) {
                            item(key = "moreAlbums") {
                                SeeMoreRow("See more albums", SearchScope.Albums in state.searchMorePending) {
                                    onLoadMore(SearchScope.Albums)
                                }
                            }
                        }
                    }
                    if (results.playlists.isNotEmpty()) {
                        item(key = "playlistsLabel") { SectionLabel("Playlists") }
                        itemsIndexed(results.playlists, key = { _, playlist -> "p${playlist.id}" }) { index, playlist ->
                            SpotifyLibraryRow(playlist.artRes, playlist.name, "${playlist.owner} · ${playlist.trackCount} tracks", Modifier.listFade(listState, slot + index), artUrl = playlist.artUrl) { onPlaylist(playlist) }
                        }
                        slot += results.playlists.size
                        if (SearchScope.Playlists in state.searchHasMore) {
                            item(key = "morePlaylists") {
                                SeeMoreRow("See more playlists", SearchScope.Playlists in state.searchMorePending) {
                                    onLoadMore(SearchScope.Playlists)
                                }
                            }
                        }
                    }
                    if (results.shows.isNotEmpty()) {
                        item(key = "showsLabel") { SectionLabel("Shows") }
                        itemsIndexed(results.shows, key = { _, show -> "s${show.id}" }) { index, show ->
                            SpotifyLibraryRow(show.artRes, show.name, show.publisher, Modifier.listFade(listState, slot + index), artUrl = show.artUrl) { onShow(show) }
                        }
                        if (SearchScope.Shows in state.searchHasMore) {
                            item(key = "moreShows") {
                                SeeMoreRow("See more shows", SearchScope.Shows in state.searchMorePending) {
                                    onLoadMore(SearchScope.Shows)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
