package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The experimental song layout is behind a persisted setting, so the wiring is what can break:
 * the Options action has to flip it and the screen has to swap layouts without losing the bar it
 * was toggled from.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkBackdropTest {
    @get:Rule val compose = createComposeRule()

    private val settings by lazy { SidetrackLocalSettings(RuntimeEnvironment.getApplication()) }
    private val track = MockSpotifyDataSource.recentlyPlayed.first()
    private val cover = "${track.title} artwork"

    @After
    fun resetSetting() {
        settings.artworkBackdrop = false
    }

    @Test
    fun theOptionsActionSwitchesTheSongLayout() {
        settings.artworkBackdrop = false
        val chrome = ChromeState()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SidekitTheme(spacing = SidekitSpacing.Compact) {
                    SidekitCanvas(chrome = chrome) {
                        NowPlayingUnderTest(SpotifyUiState(current = track, isPlaying = true))
                    }
                }
            }
        }
        // The old layout: the cover is a tile and a focus target.
        compose.onNodeWithContentDescription(cover).assertExists()

        compose.runOnIdle { chrome.toggleOptions() }
        compose.onNodeWithContentDescription("Art backdrop off").performClick()
        compose.waitForIdle()

        // The tiled cover is gone; the title now sits on the artwork itself.
        assertEquals(true, settings.artworkBackdrop)
        compose.onNodeWithContentDescription(cover).assertDoesNotExist()
        compose.onNodeWithText(track.title).assertExists()
        // A toggle that slams the bar shut would make comparing the two layouts two key presses
        // each way instead of one.
        compose.onNodeWithContentDescription("Art backdrop on").assertExists()
    }
}
