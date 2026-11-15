package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.SidekitSwipeRow
import io.github.manuelrtsch.sidetrack.ui.rememberSwipeRevealGroup
import kotlinx.coroutines.delay

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
        listOf(spotifyBack("Sidetrack", onBack).first())
    )
    SidekitScaffold(
        title = "History",
        actions = actions,
        options = listOf(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    ) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        // The gutter follows the focused row, but only once the focus has settled: holding
        // Up/Down through the list must not flash a pair of buttons on every row it passes.
        var focusedKey by remember { mutableStateOf<Any?>(null) }
        var gutterKey by remember { mutableStateOf<Any?>(null) }
        LaunchedEffect(focusedKey) {
            if (focusedKey == null) {
                gutterKey = null
            } else {
                // Close the previous row's control as soon as the focus border moves.
                gutterKey = null
                delay(GUTTER_DELAY_MS)
                gutterKey = focusedKey
            }
        }
        SidekitList(padding, state = listState) {
            // History is local-only and has no empty state: with no entries the list is simply blank.
            // A track can be played again, so the id alone repeats; index keeps keys unique.
            itemsIndexed(state.recent, key = { index, item -> "$index:${item.id}" }) { index, item ->
                val key = "$index:${item.id}"
                val gutterOpen = gutterKey == key
                val square = SidekitTheme.spacing.itemHeight
                val targetGutter = if (gutterOpen) square * 2 + GutterGap else 0.dp
                // The gutter opens and closes rather than snapping: the row narrows as the
                // controls slide in, and widens again as they leave.
                val gutter by animateDpAsState(targetGutter, tween(GUTTER_ANIM_MS), label = "historyGutter")
                val gutterAlpha by animateFloatAsState(
                    if (targetGutter > 0.dp) 1f else 0f,
                    tween(GUTTER_ANIM_MS),
                    label = "historyGutterAlpha"
                )
                val gutterPx = with(LocalDensity.current) { gutter.toPx() }
                Box(Modifier.listFade(listState, index)) {
                    SidekitSwipeRow(
                        key = key,
                        group = swipes,
                        // A focused row slides left to leave the gutter free on its right; its
                        // width stays the same, as with a touch swipe.
                        modifier = Modifier.graphicsLayer { translationX = -gutterPx },
                        action = queueAction(item, onQueue),
                        // The gutter already gives a focused row its actions; a second
                        // focus-revealed swipe button would crowd the same edge.
                        revealOnFocus = false
                    ) {
                        SpotifyMediaRow(
                            item,
                            Modifier.onFocusChanged { if (it.isFocused) focusedKey = key },
                            secondary = item.creator,
                            // The side controls get the right edge while they are visible.
                            trailing = if (gutter > 0.dp) null else formatDuration(item.durationMs),
                            onClick = { onPlay(item) }
                        )
                    }
                    // Composed only while the gutter has width, so a faded-out control can never
                    // be tapped.
                    if (gutter > 0.dp) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .graphicsLayer {
                                    alpha = gutterAlpha
                                    translationX = (1f - gutterAlpha) * GUTTER_SLIDE.toPx()
                                }
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(GutterGap)) {
                                RowActionSquare(
                                    color = SidekitTheme.colors.successA1,
                                    icon = SidekitIcon.QueueMusic,
                                    label = "Add ${item.title} to queue",
                                    onClick = { onQueue(item) }
                                )
                                RowActionSquare(
                                    color = SidekitTheme.colors.dangerA1,
                                    icon = SidekitIcon.DeleteForever,
                                    label = "Remove ${item.title} from history",
                                    onClick = { onRemove(item) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** How long a row must hold the focus before its gutter control appears. */
private const val GUTTER_DELAY_MS = 250L

/** How far the gutter control slides in from the right as it appears. */
private val GUTTER_SLIDE = 12.dp

/** How long the gutter takes to open or close. */
private const val GUTTER_ANIM_MS = 160

/** Gap between two gutter squares. */
private val GutterGap = 3.dp
