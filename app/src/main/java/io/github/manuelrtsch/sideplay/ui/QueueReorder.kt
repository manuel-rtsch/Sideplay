package io.github.manuelrtsch.sideplay.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pick-up-and-drop reordering for the queue's upcoming tail, touch only.
 *
 * A long press lifts the row under the finger ([from]); dragging translates it and opens a gap at
 * the slot it would drop into ([to]). Releasing commits `from` to `to`.
 *
 * The same lifted state backs keypad reordering, but the keypad steps the song through the data
 * instead of sliding a copy: [step] commits one-row moves as they happen, which is what lets the
 * screen scroll the moved row into view in the same measure pass (see Sidekit's Arrange apps). The
 * original index is kept in [origin] so Back can put the track back.
 *
 * Every queue row is exactly one `itemHeight + itemOuterGap` tall, so the drop slot is derived from
 * how many rows the picked-up row has travelled rather than from layout hit-testing. That distance
 * is the finger's own travel plus the pixels the auto-scroll loop scrolled: on screen the row stays
 * under the finger while the rows behind it flow past.
 */
@Stable
class QueueReorderState internal constructor(
    internal val listState: LazyListState,
    private val pitchPx: Float
) {
    /** Upcoming index currently picked up, or null when nothing is being dragged. */
    var from by mutableStateOf<Int?>(null)
        private set

    /** Upcoming index the picked-up row would drop into; -1 while idle. */
    var to by mutableIntStateOf(-1)
        private set

    /** Vertical translation of the picked-up row, in pixels, including auto-scroll compensation. */
    var translation by mutableFloatStateOf(0f)
        private set

    /** True while the lift came from the keypad button rather than a touch long press. */
    var keypad by mutableStateOf(false)
        private set

    /** Upcoming index the keypad lift started from; -1 while idle. Back restores it. */
    var origin by mutableIntStateOf(-1)
        private set

    private var pointerDelta = 0f
    private var scrolled = 0f
    private var startOffset = 0f
    private var count = 0

    val active: Boolean get() = from != null

    internal fun begin(index: Int, count: Int, listOffset: Float, keypad: Boolean = false) {
        from = index
        to = index
        origin = index
        this.count = count
        this.keypad = keypad
        startOffset = listOffset
        pointerDelta = 0f
        scrolled = 0f
        translation = 0f
    }

    /**
     * Moves the picked-up row with the finger and hands back the move the caller must commit, or
     * null while the row stays in its slot.
     *
     * The queue itself is reordered as the finger crosses each row, so the row's slot travels with
     * it. That is what keeps a long drag alive: a row whose slot is left behind scrolls out of the
     * list and is disposed, which cancels the gesture. The translation is then whatever puts the
     * row back under the finger, so the row still follows the touch exactly.
     */
    internal fun drag(dy: Float): Pair<Int, Int>? {
        val current = from ?: return null
        if (keypad) return null
        pointerDelta += dy
        // The row may only take a slot that is on screen: a slot outside the viewport is not
        // composed, so the list would dispose the row and end the drag on the spot.
        val info = listState.layoutInfo
        val firstOnScreen = origin + floor((info.viewportStartOffset + scrolled - startOffset) / pitchPx).toInt()
        val lastOnScreen = origin + floor((info.viewportEndOffset + scrolled - startOffset) / pitchPx).toInt()
        val low = firstOnScreen.coerceIn(0, (count - 1).coerceAtLeast(0))
        val high = lastOnScreen.coerceIn(low, (count - 1).coerceAtLeast(0))
        val desired = (origin + ((pointerDelta + scrolled) / pitchPx).roundToInt()).coerceIn(low, high)
        if (desired == current) {
            updateTranslation()
            return null
        }
        from = desired
        to = desired
        updateTranslation()
        return current to desired
    }

    /**
     * Keypad move: steps the lifted row one slot and hands back the move the caller must commit to
     * the queue, or null at the ends. The data move is immediate so the list can scroll the row
     * into view in the same measure pass; [origin] remembers where it started.
     */
    internal fun step(delta: Int): Pair<Int, Int>? {
        if (!keypad) return null
        val current = from ?: return null
        val next = (current + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        if (next == current) return null
        from = next
        to = next
        translation = 0f
        return current to next
    }

    /**
     * Whatever puts the row back under the finger: the finger's travel plus the auto-scroll, less
     * the distance its slot has already moved with the data.
     */
    private fun updateTranslation() {
        val current = from ?: return
        translation = pointerDelta + scrolled - (current - origin) * pitchPx
    }

    /** Ends the drag. Every move was committed as it happened, so there is nothing left to drop. */
    internal fun end() = reset()

    internal fun cancel() = reset()

    private fun reset() {
        from = null
        to = -1
        origin = -1
        translation = 0f
        pointerDelta = 0f
        scrolled = 0f
        keypad = false
    }

    /**
     * Keeps a long drag alive past the viewport edges: while the picked-up row is near the top or
     * bottom, scroll a fraction of a pitch per frame. The scrolled pixels are added to the row's
     * translation so it stays under the finger, and the drop slot advances with them.
     */
    internal suspend fun autoScroll() {
        while (active && !keypad) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val edge = pitchPx * 0.6f
            val visualTop = startOffset + pointerDelta
            val visualBottom = visualTop + pitchPx
            val nearTop = info.viewportStartOffset + edge
            val nearBottom = info.viewportEndOffset - edge
            val delta = when {
                visualTop < nearTop -> (visualTop - nearTop).coerceAtLeast(-pitchPx)
                visualBottom > nearBottom -> (visualBottom - nearBottom).coerceAtMost(pitchPx)
                else -> 0f
            }
            if (delta == 0f) continue
            // Stop at the end the row has already reached. Scrolling on would carry its slot out
            // of the viewport, and the list would dispose the row mid-drag.
            val current = from ?: continue
            if (delta < 0 && current <= 0) continue
            if (delta > 0 && current >= count - 1) continue
            val step = (delta * 0.35f).coerceIn(-MAX_AUTO_SCROLL_PX, MAX_AUTO_SCROLL_PX)
            val consumed = listState.scrollBy(step)
            if (consumed != 0f) {
                scrolled += consumed
                updateTranslation()
            }
        }
    }
}

/** How far the auto-scroll may travel in one frame, so a held finger cannot fling the list. */
private const val MAX_AUTO_SCROLL_PX = 16f

/** One long-press drag at a time, measured against the SideKit row pitch. */
@Composable
fun rememberQueueReorderState(listState: LazyListState): QueueReorderState {
    val pitch = with(LocalDensity.current) {
        (SidekitTheme.spacing.itemHeight + SidekitTheme.spacing.itemOuterGap).toPx()
    }
    return remember(listState, pitch) { QueueReorderState(listState, pitch) }
}

/** Runs the auto-scroll loop for as long as a row is picked up. */
@Composable
fun QueueReorderAutoScroll(state: QueueReorderState) {
    LaunchedEffect(state.active) {
        if (state.active) state.autoScroll()
    }
}

/**
 * Long press to pick the row at [index] up, drag to choose a slot, release to drop.
 *
 * The detector sits outside the row's horizontal swipe, so a horizontal move is consumed by the
 * swipe first and cancels the pending long press; a vertical move after the press is consumed here
 * and cancels the row's tap, so picking a track up never also opens it.
 */
@Composable
fun Modifier.queueReorder(
    state: QueueReorderState,
    index: Int,
    count: Int,
    key: Any,
    onPickUp: () -> Unit = {},
    onDrop: (from: Int, to: Int) -> Unit
): Modifier {
    // The row's index changes every time the drag reorders the queue, so the gesture must not be
    // keyed on it: a restarted pointer input would drop the row mid-drag. The latest values are
    // read through these instead.
    val latestIndex by rememberUpdatedState(index)
    val latestCount by rememberUpdatedState(count)
    val latestPickUp by rememberUpdatedState(onPickUp)
    val latestDrop by rememberUpdatedState(onDrop)
    return pointerInput(state, key) {
        try {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    val offset = state.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
                    state.begin(latestIndex, latestCount, offset.toFloat())
                    latestPickUp()
                },
                onDrag = { change, delta ->
                    change.consume()
                    state.drag(delta.y)?.let { (from, to) -> latestDrop(from, to) }
                },
                onDragEnd = { state.end() },
                onDragCancel = { state.cancel() }
            )
        } finally {
            // A row that scrolls out of the list is disposed mid-drag, which cancels this coroutine
            // without a callback: never leave a lift behind.
            if (state.active) state.cancel()
        }
    }
}
