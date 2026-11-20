package io.github.manuelrtsch.sidetrack

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import hrln.interactive.sidekit.design.input.KeypadTiming
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The category row is part of the list: it scrolls away and Up returns to the selected button. */
@RunWith(AndroidJUnit4::class)
class LibraryFilterRowTest {
    @get:Rule val compose = createComposeRule()

    /** What Navidrome can fill: no Shows, because the Subsonic podcast endpoints are stubs. */
    private val navidromeFilters: Set<LibraryFilter> = ServiceCapabilities.Navidrome.availableLibraryFilters

    private fun show(
        filter: LibraryFilter,
        supportedFilters: Set<LibraryFilter> = LibraryFilter.entries.toSet(),
        onFilter: (LibraryFilter) -> Unit = {}
    ) {
        val state = LibraryUiState(
            libraryItems = runBlocking { MockSpotifyDataSource.library(filter).items },
            filter = filter
        )
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    LibraryScreen(
                        state,
                        SavedUiState(),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onFilter = onFilter,
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
                        onCreatePlaylist = {},
                        canCreatePlaylist = false,
                        supportedFilters = supportedFilters,
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
    fun aCategoryTheServiceCannotFillIsMutedAndSkipped() {
        var picked: LibraryFilter? = null
        show(LibraryFilter.Artists, navidromeFilters) { picked = it }
        // Navidrome has no Shows section: the button keeps its slot but says why it is dead.
        compose.onNodeWithContentDescription("Shows, not available").assertExists()
        compose.onNodeWithContentDescription("Shows").assertDoesNotExist()
        // Right from the last available category has nowhere to go: the muted button is skipped
        // instead of taking focus, so no press can select a category that would load nothing.
        compose.onNodeWithContentDescription("Followed artists").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Followed artists").assertIsFocused()
        assertEquals(null, picked)
    }

    @Test
    fun returningToACategoryRestoresItsRowAndFocus() {
        val libraries = LibraryFilter.entries.associateWith {
            runBlocking { MockSpotifyDataSource.library(it).items }
        }
        var filter by mutableStateOf(LibraryFilter.Albums)
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    LibraryScreen(
                        LibraryUiState(libraryItems = libraries.getValue(filter), filter = filter),
                        SavedUiState(),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onFilter = { filter = it },
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
                        onCreatePlaylist = {},
                        canCreatePlaylist = false,
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        // Read the last album so the category has a cursor worth remembering.
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(6)
        compose.waitForIdle()
        compose.onNodeWithText("Soft Focus").requestFocus()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // Climb back to the category row, as the user does today, and switch away and back.
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Playlists").requestFocus()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Playlists").assertIsSelected()
        compose.onNodeWithContentDescription("Albums").requestFocus()
        compose.waitForIdle()

        // The restored viewport scrolled the category row away again, so the row itself is no
        // longer on screen: the cursor is back on the album we left.
        compose.onNodeWithContentDescription("Albums").assertIsNotDisplayed()
        compose.onNodeWithText("Soft Focus").assertIsFocused()

        // A held Right steps one category without needing the filter row, and the title says
        // which category is now live.
        compose.onNodeWithText("Soft Focus").performKeyInput { keyDown(Key.DirectionRight) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeypadTiming.LONG_PRESS_MS + 100))
        compose.waitForIdle()
        compose.onNodeWithText("Followed artists").assertExists()
    }

    @Test
    fun upFromFirstRowReturnsToTheRowWhenItHadScrolledAway() {
        show(LibraryFilter.Albums)
        // Put the first album at the top of the viewport, so the category row above it is no
        // longer composed.
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(1)
        compose.waitForIdle()
        compose.onNodeWithText("Night Signals").requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
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
