package io.github.manuelrtsch.sidetrack

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.R
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.QueueIndication
import io.github.manuelrtsch.sidetrack.ui.QueueReorderState
import io.github.manuelrtsch.sidetrack.ui.SidekitSwipeRow
import io.github.manuelrtsch.sidetrack.ui.SwipeAction
import io.github.manuelrtsch.sidetrack.ui.SwipeRevealGroup
import io.github.manuelrtsch.sidetrack.ui.longPressHaptic
import io.github.manuelrtsch.sidetrack.ui.queueReorder

internal class QueueRowHost(
    val listState: LazyListState,
    val reorder: QueueReorderState,
    val swipes: SwipeRevealGroup,
    val rowActions: RowActionBarState,
    val rowFocus: FocusRequester,
    val firstUpcomingFocus: FocusRequester,
    val moreFocus: LoadMoreFocusState,
    val firstQueueItem: Int,
    val reorderMode: Boolean,
    val focusedKey: String?,
    val gutterKey: String?,
    val current: MediaItem?,
    val lastIndex: Int,
    val onFocusedKey: (String?) -> Unit,
    val keepRowInBand: (Int, Any) -> Unit,
    val openRowActions: (Int, MediaItem) -> Unit,
    val onJump: (Int) -> Unit,
    val onMoveUpcoming: (Int, Int) -> Unit,
    val onCommitReorder: () -> Unit,
    val closeSwipes: () -> Unit
)

@Composable
internal fun QueueUpcomingRow(
    index: Int,
    item: MediaItem,
    key: String,
    host: QueueRowHost
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val selected = host.rowActions.selection?.key == key
    CloseRowActionsWhenDisposed(selected, host.rowActions)
    val pickedUp = host.reorder.from == index
    // A swiped-open row already shows its Remove button; the gutter would be
    // a second copy of the same action.
    val focused = host.gutterKey == key && !pickedUp && !host.reorderMode && host.swipes.openKey == null
    val square = rememberMediaRowMetrics().height
    val targetGutter = when {
        pickedUp -> LiftedGutter
        focused -> square
        else -> 0.dp
    }
    // The gutter opens and closes rather than snapping: the row narrows as the
    // control slides in, and widens again as it leaves.
    val gutter by animateDpAsState(targetGutter, tween(GUTTER_ANIM_MS), label = "gutter")
    val gutterAlpha by animateFloatAsState(
        if (targetGutter > 0.dp) 1f else 0f,
        tween(GUTTER_ANIM_MS),
        label = "gutterAlpha"
    )
    // The control stays composed while it fades out, so it has something to animate.
    val gutterMode = when {
        pickedUp -> GutterMode.Lifted
        focused -> GutterMode.Actions
        else -> null
    }
    var lastGutterMode by remember { mutableStateOf<GutterMode?>(null) }
    if (gutterMode != null) lastGutterMode = gutterMode
    val shownGutter = gutterMode ?: lastGutterMode
    val gutterPx = with(density) { gutter.toPx() }
    CompositionLocalProvider(LocalIndication provides QueueIndication) {
        Box(
            Modifier
                // The picked-up row is the thing being looked at: never fade it.
                .then(
                    if (pickedUp) {
                        Modifier
                    } else {
                        Modifier.listFade(host.listState, host.firstQueueItem + index)
                    }
                )
                .zIndex(if (pickedUp) 1f else 0f)
                .graphicsLayer {
                    // The queue reorders as the finger crosses each row, so only
                    // the picked-up row is offset: it is the one under the finger.
                    translationY = if (pickedUp) host.reorder.translation else 0f
                    if (pickedUp) {
                        scaleX = 1.02f
                        scaleY = 1.02f
                    }
                }
                .queueReorder(
                    state = host.reorder,
                    index = index,
                    count = host.lastIndex + 1,
                    key = key,
                    onPickUp = {
                        host.closeSwipes()
                        view.longPressHaptic()
                    },
                    onDrop = host.onMoveUpcoming,
                    onDropEnd = { if (!host.reorderMode) host.onCommitReorder() }
                )
        ) {
            SidekitSwipeRow(
                key = key,
                group = host.swipes,
                // A focused or picked-up row slides left to leave the gutter free
                // on its right; its width stays the same, as with a touch swipe.
                modifier = Modifier.graphicsLayer { translationX = -gutterPx },
                action = SwipeAction(
                    label = "Actions for ${item.title}",
                    icon = SidekitIcon.Apps,
                    iconRes = R.drawable.ic_more_horiz,
                    color = SidekitTheme.colors.surfaceA4,
                    onClick = { host.openRowActions(index, item) }
                ),
                // The gutter is the keypad's Remove; a second focus-revealed
                // delete square would crowd the same edge.
                revealOnFocus = false
            ) {
                MediaRow(
                    item,
                    Modifier
                        .onFocusChanged {
                            if (it.isFocused) {
                                host.onFocusedKey(key)
                                // Never fight a lift: the drag and the keypad both
                                // scroll the row themselves.
                                if (host.reorder.from == null) host.keepRowInBand(index, key)
                            }
                        }
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, SidekitTheme.colors.light, SidekitTheme.shapes.item)
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (host.focusedKey == key) {
                                Modifier.focusRequester(host.rowFocus)
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (host.current == null && index == 0) {
                                Modifier.focusRequester(host.firstUpcomingFocus)
                            } else {
                                Modifier
                            }
                        )
                        .then(host.moreFocus.rowModifier(index)),
                    secondary = item.creator,
                    // The side control gets the right edge while it is visible.
                    trailing = if (gutter > 0.dp) null else formatDuration(item.durationMs),
                    // Enter has one meaning on every row: play it now. Reorder
                    // lives in Options, so the row body never turns into a menu.
                    onClick = { host.onJump(index) }
                )
            }
            // Composed only while the gutter has width, so a faded-out control
            // can never be tapped.
            if (gutter > 0.dp) {
                Box(
                    Modifier
                        .matchParentSize()
                        .graphicsLayer {
                            alpha = gutterAlpha
                            translationX = (1f - gutterAlpha) * GUTTER_SLIDE.toPx()
                        },
                    contentAlignment = Alignment.CenterEnd
                ) {
                    when (shownGutter) {
                        // A lifted row shows the bare arrows, as Arrange apps does:
                        // the row is the thing being moved, the arrows show where.
                        GutterMode.Lifted -> Column(
                            Modifier.align(Alignment.CenterEnd).width(LiftedGutter),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (index > 0) ReorderArrow(up = true, size = LiftedIconSize)
                            if (index < host.lastIndex) ReorderArrow(up = false, size = LiftedIconSize)
                        }

                        GutterMode.Actions -> GutterSquare(
                            color = SidekitTheme.colors.surfaceA4,
                            label = "Actions for ${item.title}",
                            content = { MeatballIcon() },
                            onClick = { host.openRowActions(index, item) }
                        )

                        null -> Unit
                    }
                }
            }
        }
    }
}
