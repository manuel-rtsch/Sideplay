package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Which row of a list is showing its gutter, and why.
 *
 * A row reveals its squares for two reasons, and they behave differently on purpose:
 *
 * - **Focus.** The keypad border lands on a row and the squares follow once the focus settles, so
 *   scrubbing Up/Down through a list does not flash a pair of buttons on every row it passes. The
 *   squares stay while the focus is on the row or inside its own gutter.
 * - **Touch.** A long press reveals them immediately — a touch screen has no focus border to wait
 *   for — and they stay until the user dismisses them, exactly like a swipe-revealed button.
 *
 * Both live here so every gutter in the app (track rows, history rows, the queue) opens the same
 * way, and so one row at a time can be open: the state is per list, not per row.
 */
@Stable
open class RowGutterState {
    /** The row holding focus, or null. */
    var focusedKey: Any? by mutableStateOf(null)
        internal set

    /** The focused row whose gutter is open, once the focus has settled. */
    var openKey: Any? by mutableStateOf(null)
        internal set

    /** The row revealed by a long press, or null. */
    var touchedKey: Any? by mutableStateOf(null)
        internal set

    /**
     * True while [key]'s squares should be showing. A touch reveal is exclusive: while one row is
     * showing squares, nothing the keypad has open may show as well. Holding one row while the
     * focus sits on another is two gutters otherwise.
     */
    fun isOpen(key: Any): Boolean {
        val touched = touchedKey
        return if (touched != null) touched == key else openKey == key
    }

    fun rowFocused(key: Any) {
        // The keypad takes over: a long press elsewhere gives its squares up as soon as the focus
        // lands on a different row.
        if (touchedKey != null && touchedKey != key) touchedKey = null
        focusedKey = key
    }

    fun rowUnfocused(key: Any) {
        if (focusedKey == key) focusedKey = null
    }

    /**
     * A long press on [key]. Deliberately not debounced: a hold that lasted long enough to register
     * was a deliberate act, and the squares are the only feedback it gets.
     */
    fun reveal(key: Any) {
        // Drop whatever the focus had open: a settle that is still pending would otherwise open
        // the focused row's gutter a frame later, next to this one.
        if (openKey != key) openKey = null
        touchedKey = key
    }

    /** Dismisses the long-press reveal, either for one row or for whichever row has it. */
    fun touchDismissed(key: Any? = null) {
        if (key == null || touchedKey == key) touchedKey = null
    }
}

/** Remembers a list's gutter state and settles the focus into it. */
@Composable
fun rememberRowGutterState(): RowGutterState {
    val state = remember { RowGutterState() }
    state.settle()
    return state
}

/**
 * Debounces the focus into the gutter. Scrubbing Up/Down through a list must not flash a pair of
 * buttons on every row it passes.
 */
@Composable
internal fun RowGutterState.settle() {
    LaunchedEffect(focusedKey) {
        val target = focusedKey
        if (target == null) {
            // A focus move from a row into its own gutter arrives as two events: the row goes
            // false and the square goes true. Give the square one frame to land before treating
            // this as the row being left for good.
            withFrameNanos { }
            if (focusedKey == null) openKey = null
            return@LaunchedEffect
        }
        // Close the previous row's gutter as soon as the focus border moves.
        if (openKey != target) openKey = null
        delay(GUTTER_DELAY_MS)
        openKey = target
    }
}

/**
 * Reports a long press on the row underneath and swallows the release, so holding a row does not
 * also count as a tap on it.
 *
 * Runs in the [PointerEventPass.Initial] pass, ahead of the row's own click (which consumes the
 * press in the main pass) and ahead of the swipe gesture. Releasing early, or dragging far enough
 * for the swipe to take over, cancels the press — a list still scrolls and still swipes.
 */
@Composable
fun Modifier.rowLongPress(onLongPress: () -> Unit): Modifier {
    // The callback is a new lambda on every composition, so keying the gesture on it would cancel
    // and restart the press detector on any recomposition — including the one a finger going down
    // causes. Read it through state instead and key on nothing.
    val current by rememberUpdatedState(onLongPress)
    return pointerInput(Unit) {
        listen(current)
    }
}

private suspend fun PointerInputScope.listen(longPress: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val timeout = viewConfiguration.longPressTimeoutMillis
        var held = false
        // True once the press released or a drag took it over; false while it is still down.
        var released = false
        var settledInTime = false
        // Compose's pointer-event scope has its own withTimeoutOrNull. It reports the timeout as
        // null instead of a coroutine TimeoutCancellationException, which is why the flag above
        // decides the outcome rather than the return value.
        withTimeoutOrNull(timeout) {
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id }
                when {
                    // The pointer is gone: the gesture ended under us.
                    change == null -> pressed = false

                    // A drag (the swipe) or the row's own click took the gesture over.
                    change.isConsumed -> {
                        pressed = false
                        released = true
                    }

                    !change.pressed -> {
                        pressed = false
                        released = true
                        // The hold can also be long with the timeout never running: a looper that
                        // was busy, or input that arrived as one batch. The timestamps still say
                        // how long the finger was down, and the release has to be swallowed here
                        // so it does not become a tap as well.
                        if (change.uptimeMillis - down.uptimeMillis >= timeout) {
                            held = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            settledInTime = true
        }
        if (!settledInTime) held = true
        if (!held) return@awaitEachGesture
        longPress()
        if (released) return@awaitEachGesture
        // Swallow the rest of the gesture: the release must not turn into a tap as well.
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
            pressed = event.changes.any { it.pressed }
        }
    }
}
