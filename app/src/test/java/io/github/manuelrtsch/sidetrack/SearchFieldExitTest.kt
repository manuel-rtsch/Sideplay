package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.T9Words
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * A keypad that types is the search field's input, so the field is focused when the page opens.
 * Up / Down must still take the user out of it: on the mini QWERTY the T / D / B / J cross types
 * letters there, and its suggestion rocker is the only direction key left. Once out, a letter
 * belongs to the page's controls, not to the field.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchFieldExitTest {
    @get:Rule val compose = createComposeRule()

    private fun show(module: KeypadModule): Keypad {
        lateinit var keypad: Keypad
        compose.setContent {
            val scope = rememberCoroutineScope()
            keypad = remember { Keypad.forTesting(scope, T9Words.parse("night\nhello"), module) }
            SidekitTheme {
                SidekitCanvas(keypad = keypad) {
                    SearchScreen(
                        SearchUiState(searchHistory = listOf("night", "hello")),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        return keypad
    }

    private fun focusField() {
        // Request focus directly: a click would put the window in touch mode, and Android
        // swallows the next key to leave it, which is not the state the bug is about.
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.waitForIdle()
    }

    private fun fieldKey(key: Key) {
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `the field takes the keypad when the page opens`() {
        show(KeypadModule.T9)
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun `up leaves the field on T9`() {
        show(KeypadModule.T9)
        focusField()
        fieldKey(Key.DirectionUp)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `down leaves the field on T9`() {
        show(KeypadModule.T9)
        focusField()
        fieldKey(Key.DirectionDown)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `a letter does not take the field back after leaving it`() {
        val keypad = show(KeypadModule.T9)
        focusField()
        fieldKey(Key.DirectionDown)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()

        // What the activity does with the T9 3 key: the keypad's fallback used to focus the field
        // again, so navigating the page with a letter pad was impossible.
        compose.runOnIdle {
            keypad.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3, 0))
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun `the qwerty rocker leaves the field with nothing to complete`() {
        show(KeypadModule.Qwerty)
        focusField()
        fieldKey(Key.CtrlLeft)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
