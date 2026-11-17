package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState

/**
 * The full recently played list. Home previews five items; this screen shows the rest and lets
 * the user drop an entry without losing the playable queue.
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
    val baseActions = remember { listOf(spotifyBack("Home", onBack).first()) }
    val rowActions = rememberRowActionBarState()
    val actions = rowActionBarActions(
        rowActions,
        spotifyActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    SidekitScaffold(
        title = "History",
        modifier = Modifier.dismissRowActionsOnVerticalKey(rowActions),
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        rowActions.dismissOnScroll(listState)
        // The gutter follows the focused row, or a long press when the list is driven by touch.
        val gutters = rememberRowGutterState()
        SidekitList(padding, state = listState) {
            // History is local-only and has no empty state: with no entries the list is simply blank.
            // A track can be played again, so the id alone repeats; index keeps keys unique.
            itemsIndexed(
                playback.recent,
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
                    onAddToQueue = onAddToQueue,
                    onRemove = onRemove,
                    onClick = { onPlay(item) }
                )
            }
        }
    }
}
