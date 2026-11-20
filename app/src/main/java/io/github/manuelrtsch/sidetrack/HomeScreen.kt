package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SidetrackHomeScreen(
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    sleepTimer: StateFlow<SleepTimerState>,
    onNowPlaying: () -> Unit,
    onLibrary: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    onOpenQueue: () -> Unit,
    onAddToQueue: (MediaItem) -> Unit,
    onRemoveHistory: (MediaItem) -> (() -> Unit)?,
    onHistory: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    // The slice is remembered so an unrelated state change (notice, save, etc.) does not give the
    // recent rows a new list instance and force them to rebuild.
    val recent = remember(playback.recent) { playback.recent.take(RECENT_PREVIEW_SIZE) }
    val hasMoreHistory = playback.recent.size > RECENT_PREVIEW_SIZE
    val recentGutters = rememberRowGutterState()
    val rowActions = rememberRowActionBarState()
    val baseActions = remember {
        listOf(
            SideAction("queue", "Queue", SidekitIcon.QueueMusic, contentDescription = "Open queue", onClick = onOpenQueue),
            SideAction("search", "Search", SidekitIcon.Search, motion = ActionMotion.Forward, onClick = onSearch)
        )
    }
    val bar = rowActionBarActions(
        rowActions,
        sidetrackActions(chrome, onDismissNotice, onNowPlaying, onDismissNowPlaying, baseActions)
    )
    SidekitScaffold(
        title = "Sidetrack",
        modifier = Modifier.dismissRowActionsOnVerticalKey(rowActions),
        actions = bar,
        options = sidetrackOptions(settingsOption(onSettings))
    ) { padding ->
        val listState = rememberLazyListState()
        rowActions.dismissOnScroll(listState)
        SidekitList(padding, state = listState) {
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, 0)) {
                    NowPlayingTile(playback.current, playback.isPlaying, sleepTimer, onNowPlaying, Modifier.weight(1f))
                    LibraryTile(onLibrary, Modifier.width(SidekitTheme.spacing.itemHeight))
                }
            }
            item(key = "recentLabel") { SectionLabel("Recently played") }
            if (recent.isEmpty()) {
                item(key = "empty") {
                    StatusScreen(
                        "Nothing played yet",
                        Modifier.listFade(listState, 1),
                        hint = "Search to find your first track"
                    )
                }
            } else {
                itemsIndexed(
                    recent,
                    key = { index, item -> "$index:${item.id}" },
                    contentType = { _, item -> mediaContentType(item) }
                ) { index, item ->
                    val key = "$index:${item.id}"
                    HistoryRow(
                        key,
                        item,
                        recentGutters,
                        rowActions,
                        Modifier.listFade(listState, index + 2),
                        onAddToQueue = onAddToQueue,
                        onRemove = onRemoveHistory,
                        onClick = { onPlay(item) }
                    )
                }
            }
            if (hasMoreHistory) {
                val moreIndex = recent.size + 2
                item(key = "moreHistory") {
                    SeeMoreRow(
                        "See more history",
                        loading = false,
                        onClick = onHistory,
                        modifier = Modifier.listFade(listState, moreIndex)
                    )
                }
            }
        }
    }
}
