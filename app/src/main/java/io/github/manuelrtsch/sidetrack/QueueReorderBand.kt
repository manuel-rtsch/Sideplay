package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.platform.LocalDensity
import io.github.manuelrtsch.sidetrack.rememberMediaRowPitch
import io.github.manuelrtsch.sidetrack.ui.QueueReorderState
import kotlin.math.roundToInt

/**
 * Keeps the focused or lifted queue row inside a band below the scaffold's top padding.
 *
 * Sidekit's list scrolls a focused row into view with an animation, but a held key repeats faster
 * than that animation finishes: the focus outruns the scroll, the row leaves the viewport and the
 * list disposes it. Parking the row in the same measure pass keeps the scroll level with the key.
 */
internal class QueueRowBand(
    private val listState: LazyListState,
    private val pitchPx: Float,
    private val bandTop: Float,
    private val firstQueueItem: Int
) {
    private var lastKept = -1

    fun keep(index: Int, key: Any) {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val offset = item.offset - info.viewportStartOffset
        val target = bandTop + pitchPx
        if (offset < target - 1f || offset > target + 1f) {
            listState.requestScrollToItem(firstQueueItem + index, -pitchPx.roundToInt())
        }
    }

    /** Parks the row above the lifted one, so the row it is passing stays composed and focusable. */
    fun trackLift(index: Int) {
        if (index < 0) {
            lastKept = -1
        } else if (index != lastKept) {
            val lifted = firstQueueItem + index
            listState.requestScrollToItem(if (index == 0) 0 else lifted - 1)
            lastKept = index
        }
    }
}

@Composable
internal fun rememberQueueRowBand(
    padding: PaddingValues,
    listState: LazyListState,
    firstQueueItem: Int
): QueueRowBand {
    val density = LocalDensity.current
    val pitch = with(density) { rememberMediaRowPitch().toPx() }
    val top = with(density) { padding.calculateTopPadding().toPx() }
    return remember(listState, pitch, top, firstQueueItem) { QueueRowBand(listState, pitch, top, firstQueueItem) }
}

/**
 * The keypad bindings of reorder mode: Up/Down nudge the drop slot, Confirm drops, Left/Right are
 * swallowed so focus cannot wander off the lifted row and strand it. Returns whether the key was
 * consumed.
 */
internal fun queueReorderKey(
    event: ComposeKeyEvent,
    reorder: QueueReorderState,
    onMove: (Int, Int) -> Unit,
    onFinish: () -> Unit
): Boolean {
    val native = event.nativeKeyEvent
    if (native.action != KeyEvent.ACTION_DOWN) return false
    return when (native.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
            val delta = if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else +1
            reorder.step(delta)?.let { (from, to) -> onMove(from, to) }
            true
        }

        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true

        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
            onFinish()
            true
        }

        else -> false
    }
}
