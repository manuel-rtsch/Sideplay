package io.github.manuelrtsch.sidetrack

import android.os.Looper
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToIndexAction
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
import hrln.interactive.sidekit.design.input.KeypadTiming
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

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
                    SearchResultsScreen(
                        SearchUiState(query = query, searchResults = results, searchScopes = scopes),
                        SavedUiState(),
                        ChromeUiState(),
                        supportedScopes = supportedScopes,
                        onBack = {},
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

    /** Holds [key] on the row named [from], waiting out the keypad long-press threshold. */
    private fun hold(key: Key, from: String) {
        compose.onNodeWithText(from).requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { keyDown(key) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(KeypadTiming.LONG_PRESS_MS + 100))
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { keyUp(key) }
        compose.waitForIdle()
    }

    /** A held Right on a result row switches to the next queryable scope. */
    @Test
    fun holdingRightOnAResultRowSwitchesToTheAdjacentScope() {
        show(query = "aurora")
        // "aurora" finds tracks by Aurora Vale, then the (single) artist and the two albums.
        hold(Key.DirectionRight, "Afterglow")
        compose.onNodeWithContentDescription("Artists").assertIsSelected()
        // A category never visited has no position to restore, so the cursor stays on its row.
        compose.onNodeWithContentDescription("Artists").assertIsFocused()
        compose.onNodeWithText("Aurora Vale").assertExists()
    }

    /** A held Right skips a category that the user turned off, landing on the next queryable one. */
    @Test
    fun holdingRightSkipsScopesThatAreNotQueried() {
        val scopes = SearchScope.entries.toSet() - SearchScope.Artists
        show(query = "aurora", scopes = scopes)
        hold(Key.DirectionRight, "Afterglow")
        compose.onNodeWithContentDescription("Albums").assertIsSelected()
        compose.onNodeWithContentDescription("Albums").assertIsFocused()
        compose.onNodeWithText("Night Signals").assertExists()
    }

    /** A held arrow back into a visited scope restores its deeper position rather than its row. */
    @Test
    fun heldArrowRestoresTheSettledListPositionOfTheReturnedScope() {
        show(query = "aurora")
        // Settle Tracks partway down its list so the category keeps a deeper viewport.
        // The search field above the list also scrolls, so target the lazy list by its
        // scroll-to-index action rather than any scrollable node.
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(3)
        compose.waitForIdle()
        compose.onNodeWithText("Midnight Radio").requestFocus()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        // A held Right leaves from the settled track row, so Tracks keeps that deeper position.
        hold(Key.DirectionRight, "Midnight Radio")
        compose.onNodeWithContentDescription("Artists").assertIsSelected()
        // A held Left from the artist row lands back inside the settled Tracks list, at the deeper
        // scroll position, so the category row is scrolled out of composition.
        hold(Key.DirectionLeft, "Aurora Vale")
        compose.onNodeWithContentDescription("Tracks").assertDoesNotExist()
        compose.onNodeWithText("Midnight Radio").assertExists()
    }
}
