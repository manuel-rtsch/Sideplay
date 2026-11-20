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

// The held-arrow scope switch, the search counterpart of the library's libraryCategoryHoldSwitch.
// Tap keeps its old gutter meaning; a hold steps one category. The CategoryHoldGate it shares is
// swallowed at the screen root so a hold never walks sideways through every category.

/**
 * A held Left/Right on a search result row body steps to the adjacent scope. A tap keeps its old
 * meaning, so the press is held back until release or the keypad long-press threshold fires.
 * Steps skip scopes that are not in [includedScopes]: a category the user switched off, or one the
 * service cannot answer, is never a destination.
 */
@Composable
internal fun Modifier.searchCategoryHoldSwitch(
    scope: SearchScope,
    includedScopes: Set<SearchScope>,
    enabled: Boolean,
    gate: CategoryHoldGate,
    onSwitch: (SearchScope) -> Unit
): Modifier {
    val currentScope by rememberUpdatedState(scope)
    val currentIncluded by rememberUpdatedState(includedScopes)
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
            val next = adjacentSearchScope(currentScope, step, currentIncluded) ?: return@HoldToAct
            gate.switched = true
            currentOnSwitch(next)
        })
    }
    LaunchedEffect(enabled) {
        if (!enabled) hold.reset()
    }
    DisposableEffect(hold) {
        onDispose { hold.reset() }
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

/** The next scope in [step] order that is part of the queried set, skipping excluded slots. */
private fun adjacentSearchScope(
    from: SearchScope,
    step: Int,
    included: Set<SearchScope>
): SearchScope? {
    val scopes = SearchScope.entries
    var index = from.ordinal + step
    while (index in scopes.indices) {
        val candidate = scopes[index]
        if (candidate in included) return candidate
        index += step
    }
    return null
}
