package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/** Share of the viewport the page keys move, leaving a slice of the old page in view to read on. */
private const val PAGE_OVERLAP = 0.1f

/**
 * Steps a read-only list with the keypad, for screens whose rows cannot take focus themselves.
 *
 * Sidekit's lists scroll by chasing focus (see `FocusBand`), so a body of plain text — episode
 * notes, a long description — never moves: every row is a bare `SidekitText`, focus search has
 * nothing to land on, and Up/Down fall through to the layout. Here the list is a focus target of
 * its own, and these handlers move it directly.
 *
 * Arrows move one [stepPx], the page keys most of a viewport, Home/End jump to the ends. A key is
 * only consumed when the list actually moved, so at either end focus search still gets Up and the
 * user can leave for the action bar the usual way. Movement is immediate rather than animated:
 * that is what the platform's own scrolling views do with a held key, and it keeps a key repeat
 * from queueing animations that outlive the press.
 */
fun Modifier.keyScroll(state: LazyListState, stepPx: Float): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    val layout = state.layoutInfo
    if (event.key == Key.MoveHome) return@onPreviewKeyEvent jumpTo(state, 0)
    if (event.key == Key.MoveEnd) return@onPreviewKeyEvent jumpTo(state, layout.totalItemsCount - 1)
    val page = layout.viewportSize.height * (1f - PAGE_OVERLAP)
    val delta = when (event.key) {
        Key.DirectionUp -> -stepPx
        Key.DirectionDown -> stepPx
        Key.PageUp -> -page
        Key.PageDown -> page
        else -> return@onPreviewKeyEvent false
    }
    state.dispatchRawDelta(delta) != 0f
}

/** True when the jump lands somewhere the list can still be sent, false once the edge is reached. */
private fun jumpTo(state: LazyListState, index: Int): Boolean {
    if (index < 0 || index >= state.layoutInfo.totalItemsCount) return false
    if (index == 0 && !state.canScrollBackward) return false
    state.requestScrollToItem(index)
    return true
}
