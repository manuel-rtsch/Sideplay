package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.launch

/**
 * Picks the playlist a track should be appended to.
 *
 * Only playlists that report [PlaylistActions.canAddItems] are listed. A target that the account
 * may not edit would otherwise be a button whose only outcome is a server refusal. The add itself
 * returns the index the service placed the row at, so the bar can offer an Undo that removes
 * exactly that copy.
 */
@Composable
fun AddToPlaylistScreen(
    track: Track?,
    playlists: PlaylistUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onAdd: suspend (Playlist) -> Int?,
    onUndoAdd: suspend (Playlist, Int) -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val bar = rememberRowActionBarState()
    val baseActions = remember { sidetrackBack("Back", onBack) }
    val actions = rowActionBarActions(
        bar,
        sidetrackActions(
            chrome,
            onDismissNotice,
            onOpenNowPlaying = {},
            onDismissNowPlaying = onDismissNowPlaying,
            actions = baseActions
        )
    )
    SidekitScaffold(title = "Add to playlist", actions = actions) { padding ->
        val listState = rememberLazyListState()
        bar.dismissOnScroll(listState)
        val choices = playlists.choices
        val loading = playlists.loading && choices.isEmpty()
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when {
                track == null -> StatusScreen(
                    "This track is no longer available",
                    hint = "Go back and open it again"
                )

                loading -> LoadingScreen(title = "Loading playlists…", hint = null)

                playlists.error != null && choices.isEmpty() -> StatusScreen(
                    "Couldn't load playlists",
                    hint = playlists.error,
                    actionLabel = "Try again",
                    onAction = onRetry
                )

                choices.isEmpty() && playlists.loaded && !playlists.hasMore -> StatusScreen(
                    "No playlists you can add to",
                    hint = "Create one from Library → Playlists"
                )

                choices.isNotEmpty() || playlists.hasMore -> Unit
            }
        }
        // A page can hold only read-only playlists; if the service says there is another page,
        // show the Load more row instead of declaring the account has no targets.
        val canShowList = track != null && !loading &&
            !(playlists.error != null && choices.isEmpty()) &&
            (choices.isNotEmpty() || playlists.hasMore)
        if (!canShowList) return@SidekitScaffold
        val moreFocus = rememberLoadMoreFocus()
        LoadMoreFocus(moreFocus, listState, choices.size, playlists.loading, firstItemListIndex = 1)
        SidekitList(padding, state = listState) {
            item(key = "track") {
                SidekitText(
                    track.title,
                    Modifier.fillMaxWidth()
                        .padding(horizontal = SidekitTheme.spacing.itemPadding)
                        .listFade(listState, 0),
                    style = SidekitTheme.typography.bodySmall,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 1
                )
            }
            itemsIndexed(
                choices,
                key = { _, playlist -> playlist.key.encode() },
                contentType = { _, _ -> "playlist" }
            ) { index, playlist ->
                LibraryRow(
                    playlist.artRes,
                    playlist.name,
                    playlistSubtitle(playlist),
                    Modifier.listFade(listState, index + 1).then(moreFocus.rowModifier(index)),
                    artUrl = playlist.artUrl
                ) {
                    scope.launch {
                        val addedAt = onAdd(playlist) ?: return@launch
                        bar.showUndo(
                            RowActionUndo(
                                contentDescription = "Undo adding ${track.title} to ${playlist.name}",
                                onClick = {
                                    scope.launch { onUndoAdd(playlist, addedAt) }
                                }
                            )
                        )
                    }
                }
            }
            if (playlists.hasMore) {
                val moreIndex = choices.size + 1
                item(key = "more") {
                    LoadMoreRow(
                        "Load more playlists",
                        playlists.loading,
                        onLoadMore = {
                            moreFocus.onLoadMore(choices.size)
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
