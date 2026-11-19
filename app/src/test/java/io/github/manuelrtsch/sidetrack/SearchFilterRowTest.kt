package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Search results browse one category at a time, chosen from the same row the library uses. */
@RunWith(AndroidJUnit4::class)
class SearchFilterRowTest {
    @get:Rule val compose = createComposeRule()

    /** What Navidrome can answer: no Shows, because the Subsonic podcast endpoints are stubs. */
    private val navidromeScopes: Set<SearchScope> = ServiceCapabilities.Navidrome.availableSearchScopes

    private fun show(
        query: String = "night",
        scopes: Set<SearchScope> = SearchScope.entries.toSet(),
        supportedScopes: Set<SearchScope> = SearchScope.entries.toSet()
    ) {
        val results = runBlocking {
            MockSpotifyDataSource.search(query, scopes, 0, 100)
        }
        val state = SearchUiState(query = query, searchResults = results, searchScopes = scopes)
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        state,
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

    @Test
    fun showsOneCategoryAtATime() {
        show()
        compose.onNodeWithContentDescription("Tracks").assertIsSelected()
        compose.onNodeWithText("Midnight Radio").assertExists()
        compose.onNodeWithText("Night Signals").assertDoesNotExist()
    }

    @Test
    fun selectingACategorySwapsTheList() {
        show()
        compose.onNodeWithContentDescription("Albums").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsSelected()
        compose.onNodeWithText("Night Signals").assertExists()
        compose.onNodeWithText("Midnight Radio").assertDoesNotExist()
    }

    @Test
    fun excludedCategoriesAreMutedAndSkipped() {
        show(scopes = setOf(SearchScope.Tracks, SearchScope.Albums))
        compose.onNodeWithContentDescription("Artists, off").assertExists()
        compose.onNodeWithContentDescription("Tracks").requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Albums").assertIsSelected()
        compose.onNodeWithContentDescription("Tracks, off").assertDoesNotExist()
    }

    @Test
    fun lastSearchCategoryCannotBeTurnedOff() {
        var toggles = 0
        val state = SearchUiState(searchScopes = setOf(SearchScope.Tracks))
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScopesScreen(
                        state,
                        ChromeUiState(),
                        onBack = {},
                        onToggleScope = { toggles += 1 },
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Tracks").assertIsNotEnabled()
        compose.onNodeWithText("Tracks").performClick()
        compose.waitForIdle()
        assertEquals(0, toggles)
        compose.onNodeWithText("Albums").performClick()
        compose.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test
    fun aCategoryTheServiceCannotAnswerIsMutedInTheRow() {
        show(scopes = navidromeScopes, supportedScopes = navidromeScopes)
        compose.onNodeWithContentDescription("Shows, not available").assertExists()
        // Left/Right walk only the answerable categories, so Shows is never selected.
        compose.onNodeWithContentDescription("Playlists").requestFocus()
        compose.waitForIdle()
        compose.onNode(isFocused()).performKeyInput { pressKey(Key.DirectionRight) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Playlists").assertIsFocused()
    }

    @Test
    fun aCategoryTheServiceCannotAnswerCannotBeTurnedOn() {
        var toggles = 0
        val state = SearchUiState(searchScopes = navidromeScopes)
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScopesScreen(
                        state,
                        ChromeUiState(),
                        supportedScopes = navidromeScopes,
                        onBack = {},
                        onToggleScope = { toggles += 1 },
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Shows").assertIsNotEnabled()
        compose.onNodeWithText("Not available").assertExists()
        compose.onNodeWithText("Shows").performClick()
        compose.waitForIdle()
        assertEquals(0, toggles)
    }

    @Test
    fun typingStateKeepsTheSearchInRow() {
        show(query = "n")
        compose.onNodeWithText("Search in").assertExists()
        compose.onNodeWithText("Keep typing").assertExists()
        compose.onNodeWithContentDescription("Tracks").assertDoesNotExist()
    }
}
