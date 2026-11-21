package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
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

/** A pasted paragraph must not become a playlist name that no row can show. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CreatePlaylistNameTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a long pasted name is cut at the cap`() {
        compose.setContent {
            SidekitTheme {
                SidekitCanvas {
                    CreatePlaylistScreen(
                        ChromeUiState(),
                        onBack = {},
                        onSubmit = { _, _ -> },
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        val field = compose.onNodeWithContentDescription("Playlist name")
        field.performTextInput("n".repeat(PLAYLIST_NAME_MAX + 25))
        field.assertTextEquals("n".repeat(PLAYLIST_NAME_MAX))
    }

    @Test
    fun `a letter does not take the name field back after leaving it`() {
        lateinit var keypad: Keypad
        compose.setContent {
            val scope = rememberCoroutineScope()
            keypad = remember { Keypad.forTesting(scope, T9Words.parse("playlist"), KeypadModule.T9) }
            SidekitTheme {
                SidekitCanvas(keypad = keypad) {
                    CreatePlaylistScreen(
                        ChromeUiState(),
                        onBack = {},
                        onSubmit = { _, _ -> },
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()

        val field = compose.onNode(hasSetTextAction())
        field.assertIsFocused()
        field.performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        field.assertIsNotFocused()

        // The keypad's fallback used to focus the name field again, so a letter could not move
        // through the form after the field had been left.
        compose.runOnIdle {
            keypad.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_3, 0))
        }
        compose.waitForIdle()
        field.assertIsNotFocused()
    }

    @Test
    fun `recent tracks start unselected and toggle as seeds`() {
        val track = Track(
            id = "seed-1",
            title = "Night Signals",
            artist = "Artist",
            artistId = "artist",
            albumId = "album",
            artRes = 0,
            durationMs = 180_000L
        )
        compose.setContent {
            SidekitTheme {
                SidekitCanvas {
                    CreatePlaylistScreen(
                        ChromeUiState(),
                        recentTracks = listOf(track),
                        onBack = {},
                        onSubmit = { _, _ -> },
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        val row = compose.onNodeWithText("Night Signals")
        row.assertIsOff()
        row.performClick()
        row.assertIsOn()
    }
}
