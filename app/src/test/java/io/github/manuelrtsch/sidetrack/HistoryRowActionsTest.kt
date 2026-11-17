package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The History row's actions trigger must publish its actions to the contextual bar and keep them
 * there. History is the one list whose row click navigates, so a click that leaks past the trigger
 * would leave the screen instead of opening the bar.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryRowActionsTest {
    @get:Rule val compose = createComposeRule()

    private val recent = MockSpotifyDataSource.recentlyPlayed.take(3)

    private fun showHistory(onPlay: () -> Unit = {}) {
        compose.setContent {
            hrln.interactive.sidekit.design.theme.SidekitTheme(
                spacing = hrln.interactive.sidekit.design.theme.SidekitSpacing.Compact
            ) {
                hrln.interactive.sidekit.design.canvas.SidekitCanvas {
                    HistoryScreen(
                        PlaybackUiState(recent = recent),
                        ChromeUiState(),
                        onBack = {},
                        onPlay = { onPlay() },
                        onAddToQueue = {},
                        onRemove = { null },
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** A left swipe is the touch route to the gutter, so the trigger exists before it is pressed. */
    private fun revealTrigger(title: String) {
        compose.onNodeWithText(title).performTouchInput { swipeLeft() }
        compose.waitForIdle()
    }

    @Test
    fun triggerOpensTheContextualBar() {
        showHistory()
        val title = recent.first().title
        revealTrigger(title)

        compose.onNodeWithContentDescription("Actions for $title").performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Add $title to queue").assertExists()
        compose.onNodeWithContentDescription("Remove $title from history").assertExists()
    }

    @Test
    fun triggerDoesNotStartTheTrack() {
        var played = false
        showHistory(onPlay = { played = true })
        val title = recent.first().title
        revealTrigger(title)

        compose.onNodeWithContentDescription("Actions for $title").performClick()
        compose.waitForIdle()

        assert(!played) { "the actions trigger must not also play the row" }
    }
}
