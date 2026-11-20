package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState

/**
 * The full recently played list. Home previews one page of it; this screen shows the rest in the
 * same 20-row batches and lets the user drop an entry without losing the playable queue.
 */
@Composable
fun HistoryScreen(
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onAddToQueue: (MediaItem) -> Unit,
    onRemove: (MediaItem) -> (() -> Unit)?,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val baseActions = remember { listOf(sidetrackBack("Home", onBack).first()) }
    val rowActions = rememberRowActionBarState()
    val actions = rowActionBarActions(
        rowActions,
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    SidekitScaffold(
        title = "History",
        modifier = Modifier.dismissRowActionsOnVerticalKey(rowActions),
        actions = actions,
        options = sidetrackOptions(onNowPlaying = onNowPlaying)
    ) { padding ->
        val listState = rememberLazyListState()
        rowActions.dismissOnScroll(listState)
        // The gutter follows the focused row, or a long press when the list is driven by touch.
        val gutters = rememberRowGutterState()
        val shown = remember { mutableIntStateOf(CATALOG_PAGE_SIZE) }
        val visible = playback.recent.take(shown.intValue)
        val moreFocus = rememberLoadMoreFocus()
        LoadMoreFocus(moreFocus, listState, visible.size, loading = false, firstItemListIndex = 0)
        SidekitList(padding, state = listState) {
            // History is local-only and has no empty state: with no entries the list is simply blank.
            // A track can be played again, so the id alone repeats; index keeps keys unique.
            itemsIndexed(
                visible,
                key = { index, item -> "$index:${item.id}" },
                contentType = { _, item -> if (item is Track) "track" else "episode" }
            ) { index, item ->
                val key = "$index:${item.id}"
                HistoryRow(
                    key,
                    item,
                    gutters,
                    rowActions,
                    Modifier.listFade(listState, index),
                    focusRequester = moreFocus.requesterFor(index),
                    onAddToQueue = onAddToQueue,
                    onRemove = onRemove,
                    onClick = { onPlay(item) }
                )
            }
            if (playback.recent.size > visible.size) {
                val moreIndex = visible.size
                item(key = "moreHistory") {
                    LoadMoreRow(
                        "Load more history",
                        loading = false,
                        onLoadMore = {
                            moreFocus.onLoadMore(visible.size)
                            shown.intValue += CATALOG_PAGE_SIZE
                        },
                        modifier = Modifier.listFade(listState, moreIndex),
                        focusState = moreFocus
                    )
                }
            }
        }
    }
}
