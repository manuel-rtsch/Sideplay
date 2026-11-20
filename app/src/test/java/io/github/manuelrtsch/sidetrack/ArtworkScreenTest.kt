package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The artwork viewer must never ask the network for a cover the catalog already knows is missing:
 * that fetch is what used to reach the SDK's cache trim and kill the process.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun aMissingCoverShowsTheStatusPageWithoutLoading() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackArtworkScreen(
                    artRes = 0,
                    artUrl = null,
                    label = "Playlist artwork",
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("No artwork available").assertExists()
        compose.onNodeWithText("There is no cover art to show for this item.").assertExists()
    }

    @Test
    fun aJsonNullUrlIsMissingToo() {
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackArtworkScreen(
                    artRes = 0,
                    artUrl = "null",
                    label = "Playlist artwork",
                    onBack = {}
                )
            }
        }

        compose.onNodeWithText("No artwork available").assertExists()
        compose.onNodeWithText("There is no cover art to show for this item.").assertExists()
    }

    @Test
    fun aLocalCoverStillRenders() {
        val artRes = MockSpotifyDataSource.albums.first().artRes
        compose.setContent {
            SidekitPreviewFrame {
                SidetrackArtworkScreen(
                    artRes = artRes,
                    artUrl = null,
                    label = "Album artwork",
                    onBack = {}
                )
            }
        }

        compose.onNodeWithContentDescription("Album artwork").assertExists()
        compose.onNodeWithText("No artwork available").assertDoesNotExist()
    }
}
