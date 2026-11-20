package io.github.manuelrtsch.sidetrack

import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Where the cursor lands when a category swap happens from the row and from a held arrow. */
@RunWith(AndroidJUnit4::class)
class LibraryCategorySwitchFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun show(start: LibraryFilter) {
        val libraries = LibraryFilter.entries.associateWith {
            runBlocking { MockSpotifyDataSource.library(it).items }
        }
        var filter by mutableStateOf(start)
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    LibraryScreen(
                        LibraryUiState(
                            libraryPages = mapOf(filter to LibraryPage(libraries.getValue(filter))),
                            filter = filter
                        ),
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
    }

    private fun holdRight(from: String) {
        compose.onNodeWithText(from).requestFocus()
        compose.waitForIdle()
        compose.onNodeWithText(from).performKeyInput { keyDown(Key.DirectionRight) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeypadTiming.LONG_PRESS_MS + 100))
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { keyUp(Key.DirectionRight) }
        compose.waitForIdle()
    }

    @Test
    fun tappingRightOnTheRowKeepsTheCursorOnTheRow() {
        show(LibraryFilter.LikedSongs)
        compose.onNodeWithContentDescription("Liked songs").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Playlists").assertIsSelected()
        compose.onNodeWithContentDescription("Playlists").assertIsFocused()
    }

    @Test
    fun holdingRightOnAPlaylistRowStaysOnAlbums() {
        show(LibraryFilter.Playlists)
        holdRight("Late Night Drive")
        compose.onNodeWithContentDescription("Albums").assertIsSelected()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
        compose.onNodeWithText("Night Signals").assertExists()
    }

    /** Stepping across the row is browsing: each press keeps the cursor on the row. */
    @Test
    fun steppingAcrossVisitedCategoriesKeepsTheCursorOnTheRow() {
        show(LibraryFilter.LikedSongs)
        // Settle a row in Playlists, so the category has a viewport to come back to.
        holdRight("Afterglow")
        compose.onNodeWithContentDescription("Playlists").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNodeWithText("Late Night Drive").requestFocus()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // Climb back to the row, step away and back to the now-visited category.
        compose.onAllNodes(hasScrollAction())[0].performScrollToIndex(0)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Playlists").requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionLeft) }
        compose.waitForIdle()

        // Back in Playlists: the row keeps the cursor instead of diving into the restored list.
        compose.onNodeWithContentDescription("Playlists").assertIsSelected()
        compose.onNodeWithContentDescription("Playlists").assertIsFocused()
    }
}
