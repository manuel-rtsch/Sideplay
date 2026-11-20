package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import hrln.interactive.sidekit.design.input.HoldToAct
import hrln.interactive.sidekit.design.input.KeypadTiming

// The held-arrow category switch: a tap keeps its old gutter meaning, a hold steps one category.
// The gate lives with the modifier so every screen that owns the row swallows the repeats the
// same way.

/** One category step per physical hold, even if the key repeats faster than the composable. */
internal class CategoryHoldGate {
    var keyCode: Int? = null
    var switched: Boolean = false

    fun reset() {
        keyCode = null
        switched = false
    }
}

/** Swallows the rest of a held arrow at the screen root, before focus can land on the category row. */
internal fun Modifier.consumeCategoryHoldRepeats(gate: CategoryHoldGate): Modifier = onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (gate.switched && native.keyCode == gate.keyCode &&
        (native.action == KeyEvent.ACTION_DOWN || native.action == KeyEvent.ACTION_UP)
    ) {
        if (native.action == KeyEvent.ACTION_UP) gate.reset()
        true
    } else {
        false
    }
}

/**
 * A held Left/Right on a library row body steps to the adjacent category. A tap keeps its old
 * meaning (into the gutter on Right, back out on Left), so the press is held back until release
 * or the shared keypad long-press threshold fires.
 */
@Composable
internal fun Modifier.libraryCategoryHoldSwitch(
    filter: LibraryFilter,
    supportedFilters: Set<LibraryFilter>,
    enabled: Boolean,
    gate: CategoryHoldGate,
    onSwitch: (LibraryFilter) -> Unit
): Modifier {
    val currentFilter by rememberUpdatedState(filter)
    val currentSupported by rememberUpdatedState(supportedFilters)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentOnSwitch by rememberUpdatedState(onSwitch)
    val focusManager = LocalFocusManager.current
    val hold = remember {
        HoldToAct(holdMs = KeypadTiming.LONG_PRESS_MS, onHold = { action, _ ->
            if (!currentEnabled || gate.switched) return@HoldToAct
            val step = when (action) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> return@HoldToAct
            }
            val next = adjacentLibraryFilter(currentFilter, step, currentSupported) ?: return@HoldToAct
            gate.switched = true
            currentOnSwitch(next)
        })
    }
    LaunchedEffect(enabled) {
        if (!enabled) {
            hold.reset()
            gate.reset()
        }
    }
    DisposableEffect(hold) {
        onDispose {
            hold.reset()
            gate.reset()
        }
    }
    return onPreviewKeyEvent { event ->
        if (!currentEnabled) return@onPreviewKeyEvent false
        val native = event.nativeKeyEvent
        when {
            // A hold already stepped. Swallow every repeat until the physical key comes up, even
            // if the input source reports each repeat as a fresh press with a zero repeat count.
            native.action == KeyEvent.ACTION_DOWN &&
                gate.switched &&
                native.keyCode == gate.keyCode -> return@onPreviewKeyEvent true

            native.action == KeyEvent.ACTION_UP && native.keyCode == gate.keyCode -> gate.reset()

            native.action == KeyEvent.ACTION_DOWN && native.repeatCount == 0 -> gate.keyCode = native.keyCode
        }
        when (
            val result = hold.handle(native) { keyCode ->
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
                    else -> null
                }
            }
        ) {
            HoldToAct.Result.Pass -> false

            HoldToAct.Result.Consume -> true

            is HoldToAct.Result.Deliver -> {
                // A tap: replay the direction the swallowed press would have performed.
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> focusManager.moveFocus(FocusDirection.Left)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> focusManager.moveFocus(FocusDirection.Right)
                }
                true
            }
        }
    }
}

/** The next category in [step] order that the service can fill, skipping empty slots. */
private fun adjacentLibraryFilter(
    from: LibraryFilter,
    step: Int,
    supported: Set<LibraryFilter>
): LibraryFilter? {
    val filters = LibraryFilter.entries
    var index = from.ordinal + step
    while (index in filters.indices) {
        val candidate = filters[index]
        if (candidate in supported) return candidate
        index += step
    }
    return null
}
