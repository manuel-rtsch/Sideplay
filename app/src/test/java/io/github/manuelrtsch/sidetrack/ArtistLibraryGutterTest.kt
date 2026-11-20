package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class ArtistLibraryGutterTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aCategoryBecomingFocusedDoesNotStealTheArtistOptions() {
        val artist = MockSpotifyDataSource.artists.first()
        val libraries = LibraryFilter.entries.associateWith {
            runBlocking { MockSpotifyDataSource.library(it).items }
        }
        var filter by mutableStateOf(LibraryFilter.Artists)
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
        compose.onNodeWithText(artist.name).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Actions for ${artist.name}").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Artist radio for ${artist.name}").assertExists()
        runCatching {
            compose.onNodeWithContentDescription("Liked Songs").requestFocus()
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Artist radio for ${artist.name}").assertExists()
    }
}
