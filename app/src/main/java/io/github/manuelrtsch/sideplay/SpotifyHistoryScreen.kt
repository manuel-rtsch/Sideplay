package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup

/**
 * The full recently played list. Home previews five items; this screen shows the rest and lets
 * the user drop an entry without losing the playable queue.
 */
@Composable
fun HistoryScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onPlay: (SpotifyMediaItem) -> Unit,
    onQueue: (SpotifyMediaItem) -> Unit,
    onRemove: (SpotifyMediaItem) -> Unit,
    onNowPlaying: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val actions = spotifyActions(
        state,
        onDismissNotice,
        onNowPlaying,
        onDismissNowPlaying,
        listOf(spotifyBack("Sideplay", onBack).first())
    )
    SidekitScaffold(
        title = "History",
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        SidekitList(padding, state = listState) {
            if (state.recent.isEmpty()) {
                item(key = "empty") {
                    EmptyMessage("Nothing played yet", hint = "Search to find your first track or episode")
                }
            }
            // A track can be played again, so the id alone repeats; index keeps keys unique.
            itemsIndexed(state.recent, key = { index, item -> "$index:${item.id}" }) { index, item ->
                SidekitSwipeRow(
                    key = "$index:${item.id}",
                    group = swipes,
                    modifier = Modifier.listFade(listState, index),
                    action = queueAction(item, onQueue)
                ) {
                    SpotifyMediaRow(
                        item,
                        Modifier,
                        secondary = item.creator,
                        trailingContent = { RemoveHistoryButton(item, onRemove) },
                        onClick = { onPlay(item) }
                    )
                }
            }
        }
    }
}

/** Removes one history entry. An X reads as dismissing a row, not deleting the track itself. */
@Composable
private fun RemoveHistoryButton(item: SpotifyMediaItem, onRemove: (SpotifyMediaItem) -> Unit) {
    val label = "Remove ${item.title} from history"
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) { onRemove(item) },
        contentAlignment = Alignment.Center
    ) {
        SidekitIconImage(
            SidekitIcon.Close,
            size = 26.dp,
            tint = SidekitTheme.colors.surfaceA6,
            contentDescription = label
        )
    }
}
