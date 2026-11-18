package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The category row is part of the list: it scrolls away and Up returns to the selected button. */
@RunWith(AndroidJUnit4::class)
class LibraryFilterRowTest {
    @get:Rule val compose = createComposeRule()

    private fun show(filter: LibraryFilter) {
        val state = SpotifyUiState(
            libraryItems = runBlocking { MockSpotifyDataSource.library(filter).items },
            filter = filter
        )
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    LibraryScreen(
                        state.library,
                        state.saved,
                        state.chrome,
                        onBack = {},
                        onSearch = {},
                        onFilter = {},
                        onTrack = {},
                        onAddToQueue = {},
                        onPlaylist = {},
                        onAlbum = {},
                        onArtist = {},
                        onShow = {},
                        onSaveTrack = {},
                        onEnsureSavedTrack = {},
                        onLoadMore = {},
                        onNowPlaying = {},
                        onRetry = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun rowScrollsAwayWithTheList() {
        show(LibraryFilter.Albums)
        compose.onNodeWithText("Night Signals").requestFocus()
        compose.waitForIdle()
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(4)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsNotDisplayed()
    }

    @Test
    fun upFromFirstRowReturnsToTheSelectedRow() {
        show(LibraryFilter.Shows)
        compose.onNodeWithContentDescription("Shows").assertIsSelected()
        compose.onNodeWithText("The Long Way Home").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithText("The Long Way Home").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Shows").assertIsFocused()
    }
}
