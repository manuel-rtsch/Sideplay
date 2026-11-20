package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Where the search cursor lands when a scope swap happens from the category row. */
@RunWith(AndroidJUnit4::class)
class SearchCategorySwitchFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun show(
        query: String = "night",
        scopes: Set<SearchScope> = SearchScope.entries.toSet(),
        supportedScopes: Set<SearchScope> = SearchScope.entries.toSet()
    ) {
        val results = runBlocking { MockSpotifyDataSource.search(query, scopes, 0, 100) }
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(query = query, searchResults = results, searchScopes = scopes),
                        SavedUiState(),
                        ChromeUiState(),
                        supportedScopes = supportedScopes,
                        onBack = {},
                        onQuery = {},
                        onClear = {},
                        onToggleScope = {},
                        onTrack = {},
                        onAddToQueue = {},
                        onArtist = {},
                        onAlbum = {},
                        onPlaylist = {},
                        onShow = {},
                        onSaveTrack = {},
                        onEnsureSavedTrack = {},
                        onLoadMore = {},
                        onRetry = {},
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

    /** Selecting a category from the row keeps the cursor on the row so Left/Right can keep stepping. */
    @Test
    fun selectingACategoryKeepsTheCursorOnTheRow() {
        show()
        // Right from Artists moves to the adjacent answerable category, Albums, which for "night"
        // has a single result: Night Signals.
        compose.onNodeWithContentDescription("Artists").requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsSelected()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
        compose.onNodeWithText("Night Signals").assertExists()
    }

    /** Up from the first result returns to the selected category row. */
    @Test
    fun upFromFirstResultReturnsToTheSelectedCategoryRow() {
        show()
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Night Signals").requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
    }

    /** A category dropped from the queried set moves the selection (and the cursor) with it. */
    @Test
    fun aCategoryNoLongerIncludedMovesTheSelectionAndCursor() {
        // Tracks and Artists are no longer queried, so the only answerable result is Albums.
        val scopes = SearchScope.entries.toSet() - setOf(SearchScope.Tracks, SearchScope.Artists)
        show(scopes = scopes)
        // The selection leaves its default (Tracks) for the first included category that can
        // answer, and the cursor follows it onto the row rather than leaving it behind.
        compose.onNodeWithContentDescription("Albums").assertIsSelected()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
        compose.onNodeWithText("Night Signals").assertExists()
    }
}
