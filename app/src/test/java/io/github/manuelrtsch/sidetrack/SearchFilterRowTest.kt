package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
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
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.T9Words
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
    private val navidromeScopes: Set<SearchScope> = ServiceCapabilities.Subsonic.availableSearchScopes

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
                    SearchResultsScreen(
                        state,
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
    fun theSearchActionSubmitsTheTypedTerm() {
        var submitted: String? = null
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(query = "night"),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = { submitted = it },
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Clear search history").assertDoesNotExist()
        compose.onNodeWithText("Search").performClick()
        assertEquals("night", submitted)
    }

    @Test
    fun theGamepadsASubmitsFromTheField() {
        var submitted: String? = null
        compose.setContent {
            val scope = rememberCoroutineScope()
            val keypad = remember { Keypad.forTesting(scope, T9Words.Empty, KeypadModule.GamePad) }
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas(keypad = keypad) {
                    SearchScreen(
                        SearchUiState(query = "night"),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = { submitted = it },
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).requestFocus()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.ButtonA) }
        assertEquals("night", submitted)
    }

    @Test
    fun clearingTheHistoryOffersUndoWithoutOpeningTheKeyboard() {
        var search by mutableStateOf(SearchUiState(searchHistory = listOf("night", "aurora")))
        var cleared = 0
        var restored: List<String>? = null
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        search,
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {},
                        onClearHistory = {
                            cleared += 1
                            search = search.copy(searchHistory = emptyList())
                        },
                        onRestoreHistory = {
                            restored = it
                            search = search.copy(searchHistory = it)
                        }
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Clear search history").performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
        // Clearing must leave focus on the bar: focusing the field would raise the keyboard over Undo.
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithText("Undo").performClick()
        assertEquals(listOf("night", "aurora"), restored)
    }

    @Test
    fun landingKeepsTheSearchInRowAndOffersHistory() {
        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SearchScreen(
                        SearchUiState(searchHistory = listOf("night", "aurora")),
                        ChromeUiState(),
                        onBack = {},
                        onSearch = {},
                        onClear = {},
                        onRemoveHistory = {},
                        onScopes = {},
                        onNowPlaying = {},
                        onDismissNotice = {},
                        onDismissNowPlaying = {}
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Search in").assertExists()
        compose.onNodeWithText("RECENT SEARCHES").assertExists()
        compose.onNodeWithText("night").assertExists()
        compose.onNodeWithContentDescription("Tracks").assertDoesNotExist()
    }
}
