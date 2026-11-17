package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.ChromeState
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * The experimental song layout is behind a persisted setting, so the wiring is what can break:
 * the setting has to swap the layout, and the Options bar must stay at three squares in either
 * layout.
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

    private fun showNowPlaying(chrome: ChromeState) {
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SidekitTheme(spacing = SidekitSpacing.Compact) {
                    SidekitCanvas(chrome = chrome) {
                        NowPlayingUnderTest(SpotifyUiState(current = track, isPlaying = true))
                    }
                }
            }
        }
    }

    @Test
    fun theSettingSwitchesTheSongLayout() {
        settings.artworkBackdrop = false
        showNowPlaying(ChromeState())
        // The old layout: the cover is a tile and a focus target.
        compose.onNodeWithContentDescription(cover).assertExists()

        compose.runOnIdle { settings.artworkBackdrop = true }
        compose.waitForIdle()

        // The tiled cover is gone; the title now sits on the artwork itself.
        compose.onNodeWithContentDescription(cover).assertDoesNotExist()
        compose.onNodeWithText(track.title).assertExists()
    }

    @Test
    fun theNormalOptionsBarKeepsThreeButtons() {
        settings.artworkBackdrop = false
        val chrome = ChromeState()
        showNowPlaying(chrome)

        compose.runOnIdle { chrome.toggleOptions() }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Open queue").assertExists()
        compose.onNodeWithContentDescription("Song radio").assertExists()
        compose.onNodeWithContentDescription("Sleep timer").assertExists()
        // The backdrop toggle lives in Settings; a fourth square would crowd the bar.
        compose.onNodeWithContentDescription("Art backdrop off").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open album").assertDoesNotExist()
    }

    @Test
    fun theBackdropOptionsKeepSongRadio() {
        settings.artworkBackdrop = true
        val chrome = ChromeState()
        showNowPlaying(chrome)

        compose.runOnIdle { chrome.toggleOptions() }
        compose.waitForIdle()

        // The artwork itself opens the album, so the middle square stays Song radio in both
        // layouts and the bar keeps its three buttons.
        compose.onNodeWithContentDescription("Open queue").assertExists()
        compose.onNodeWithContentDescription("Song radio").assertExists()
        compose.onNodeWithContentDescription("Sleep timer").assertExists()
        compose.onNodeWithContentDescription("Open album").assertDoesNotExist()
        compose.onNodeWithContentDescription("Art backdrop on").assertDoesNotExist()
    }
}
