package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
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
