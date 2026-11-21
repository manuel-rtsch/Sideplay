package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.input.key.Key
import hrln.interactive.sidekit.design.input.KeypadModule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search field is reachable from every keypad. T9 and QWERTY submit through Sidekit's typing
 * engine; the two letterless modules have to be given a key that means "search" here.
 */
class SearchSubmitKeyTest {
    @Test
    fun `the game controller's A submits`() {
        assertTrue(isSearchSubmitKey(Key.ButtonA, KeypadModule.GamePad))
    }

    @Test
    fun `the sundial's centre submits`() {
        assertTrue(isSearchSubmitKey(Key.MediaPlayPause, KeypadModule.Sundial))
    }

    @Test
    fun `enter submits on the letterless keypads`() {
        assertTrue(isSearchSubmitKey(Key.Enter, KeypadModule.GamePad))
        assertTrue(isSearchSubmitKey(Key.Enter, KeypadModule.Sundial))
        assertTrue(isSearchSubmitKey(Key.NumPadEnter, KeypadModule.Sundial))
    }

    @Test
    fun `typing keypads keep Enter for their completion engine`() {
        assertFalse(isSearchSubmitKey(Key.Enter, KeypadModule.T9))
        assertFalse(isSearchSubmitKey(Key.Enter, KeypadModule.Qwerty))
        assertFalse(isSearchSubmitKey(Key.ButtonA, KeypadModule.T9))
    }

    @Test
    fun `media play stays media on the other keypads`() {
        assertFalse(isSearchSubmitKey(Key.MediaPlayPause, KeypadModule.GamePad))
        assertFalse(isSearchSubmitKey(Key.MediaPlayPause, KeypadModule.T9))
    }
}
