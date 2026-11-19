package io.github.manuelrtsch.sidetrack.ui

import androidx.compose.foundation.lazy.LazyListState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keypad reordering commits one row per step, so the state machine — not the gesture — is what the
 * screen's Back/Undo and shuffle handling depend on. The touch drag needs a laid-out list; its
 * slot math is pinned through the keypad path and the no-op case here.
 */
class QueueReorderTest {
    private val pitch = 64f

    @Test fun `a lift starts on the picked row and has not moved yet`() {
        val state = state()

        state.begin(index = 2, count = 5, listOffset = 0f, keypad = true)

        assertEquals(2, state.from)
        assertEquals(2, state.to)
        assertEquals(2, state.origin)
        assertEquals(0f, state.translation)
        assertTrue(state.keypad)
        assertTrue(state.active)
        assertFalse(state.hasMoved)
    }

    @Test fun `stepping down reports the move the caller has to commit`() {
        val state = state()
        state.begin(index = 2, count = 5, listOffset = 0f, keypad = true)

        assertEquals(2 to 3, state.step(1))
        assertEquals(3, state.from)
        assertEquals(3, state.to)
        assertEquals(2, state.origin)
        assertTrue(state.hasMoved)
    }

    @Test fun `stepping up reports the move the caller has to commit`() {
        val state = state()
        state.begin(index = 2, count = 5, listOffset = 0f, keypad = true)

        assertEquals(2 to 1, state.step(-1))
        assertEquals(1, state.from)
        assertEquals(1, state.to)
    }

    @Test fun `stepping past the last row reports nothing`() {
        val state = state()
        state.begin(index = 4, count = 5, listOffset = 0f, keypad = true)

        assertNull(state.step(1))
        assertEquals(4, state.from)
        assertFalse(state.hasMoved)
    }

    @Test fun `stepping past the first row reports nothing`() {
        val state = state()
        state.begin(index = 0, count = 5, listOffset = 0f, keypad = true)

        assertNull(state.step(-1))
        assertEquals(0, state.from)
        assertFalse(state.hasMoved)
    }

    @Test fun `stepping a single-row queue reports nothing`() {
        val state = state()
        state.begin(index = 0, count = 1, listOffset = 0f, keypad = true)

        assertNull(state.step(1))
        assertNull(state.step(-1))
    }

    @Test fun `a touch lift ignores keypad steps`() {
        val state = state()
        state.begin(index = 1, count = 4, listOffset = 0f)

        assertNull(state.step(1))
        assertEquals(1, state.from)
        assertFalse(state.keypad)
    }

    @Test fun `stepping without a lift reports nothing`() {
        val state = state()

        assertNull(state.step(1))
        assertFalse(state.active)
    }

    @Test fun `ending a lift clears every field Back reads`() {
        val state = state()
        state.begin(index = 2, count = 5, listOffset = 0f, keypad = true)
        state.step(1)

        state.end()

        assertNull(state.from)
        assertEquals(-1, state.to)
        assertEquals(-1, state.origin)
        assertEquals(0f, state.translation)
        assertFalse(state.keypad)
        assertFalse(state.hasMoved)
        assertFalse(state.active)
    }

    @Test fun `cancelling a lift clears every field Back reads`() {
        val state = state()
        state.begin(index = 2, count = 5, listOffset = 0f, keypad = true)
        state.step(-1)

        state.cancel()

        assertNull(state.from)
        assertEquals(-1, state.origin)
        assertFalse(state.hasMoved)
    }

    @Test fun `a drag shorter than half a row keeps the row in its slot`() {
        val state = state()
        state.begin(index = 1, count = 4, listOffset = 0f)

        assertNull(state.drag(dy = pitch * 0.4f))
        assertEquals(1, state.from)
        assertFalse(state.hasMoved)
        assertEquals(pitch * 0.4f, state.translation)
    }

    @Test fun `a drag without a lift reports nothing`() {
        val state = state()

        assertNull(state.drag(dy = pitch))
        assertEquals(0f, state.translation)
    }

    @Test fun `a keypad lift moved by the touch ignores the drag`() {
        val state = state()
        state.begin(index = 1, count = 4, listOffset = 0f, keypad = true)

        assertNull(state.drag(dy = pitch * 2f))
        assertEquals(1, state.from)
    }

    private fun state() = QueueReorderState(LazyListState(), pitchPx = pitch)
}
