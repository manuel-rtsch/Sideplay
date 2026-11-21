package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
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
 * letters there, and its suggestion rocker is the only direction key left.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchFieldExitTest {
    @get:Rule val compose = createComposeRule()

    private fun show(module: KeypadModule) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val keypad = remember { Keypad.forTesting(scope, T9Words.parse("night\nhello"), module) }
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
    fun `the qwerty rocker leaves the field with nothing to complete`() {
        show(KeypadModule.Qwerty)
        focusField()
        fieldKey(Key.CtrlLeft)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }
}
