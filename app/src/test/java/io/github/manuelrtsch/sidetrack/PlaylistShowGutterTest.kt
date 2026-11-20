package io.github.manuelrtsch.sidetrack

import android.os.Looper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Playlists and shows use the same meatball gutter as tracks, albums and artists. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class PlaylistShowGutterTest {
    @get:Rule val compose = createComposeRule()

    private val playlist = MockSpotifyDataSource.playlists.first()
    private val show = MockSpotifyDataSource.shows.first()
    private val playlistKey = playlist.key.encode()
    private val showKey = show.key.encode()

    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(120)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            compose.waitForIdle()
        }
    }

    private fun showPlaylist(
        saved: Boolean = false,
        onSave: () -> Unit = {},
        onQueue: () -> Unit = {}
    ): Pair<() -> RowGutterState, () -> RowActionBarState> {
        compose.mainClock.autoAdvance = false
        var gutter: RowGutterState? = null
        var bar: RowActionBarState? = null
        compose.setContent {
            SidekitPreviewFrame {
                val gutterState = rememberRowGutterState()
                val barState = rememberRowActionBarState()
                gutter = gutterState
                bar = barState
                LazyColumn {
                    item(key = playlistKey) {
                        PlaylistGutterRow(
                            playlistKey,
                            playlist,
                            gutterState,
                            barState,
                            saved,
                            onSave = onSave,
                            onAddToQueue = onQueue,
                            onClick = {}
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        return { requireNotNull(gutter) } to { requireNotNull(bar) }
    }

    private fun showShow(
        saved: Boolean = false,
        onSave: () -> Unit = {},
        onQueueLatest: () -> Unit = {}
    ): Pair<() -> RowGutterState, () -> RowActionBarState> {
        compose.mainClock.autoAdvance = false
        var gutter: RowGutterState? = null
        var bar: RowActionBarState? = null
        compose.setContent {
            SidekitPreviewFrame {
                val gutterState = rememberRowGutterState()
                val barState = rememberRowActionBarState()
                gutter = gutterState
                bar = barState
                LazyColumn {
                    item(key = showKey) {
                        ShowGutterRow(
                            showKey,
                            show,
                            gutterState,
                            barState,
                            saved,
                            onSave = onSave,
                            onAddLatestToQueue = onQueueLatest,
                            onClick = {}
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        return { requireNotNull(gutter) } to { requireNotNull(bar) }
    }

    private fun openGutter(state: () -> RowGutterState, key: String) {
        compose.runOnIdle { state().rowFocused(key) }
        settle()
    }

    @Test
    fun playlistOffersSaveAndQueue() {
        val (gutter, bar) = showPlaylist()
        openGutter(gutter, playlistKey)
        compose.onNodeWithContentDescription("Actions for ${playlist.name}").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Save ${playlist.name} to your library", "Add ${playlist.name} to queue"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun savedPlaylistOffersUndo() {
        var saves = 0
        val (gutter, bar) = showPlaylist(saved = true, onSave = { saves++ })
        openGutter(gutter, playlistKey)
        compose.onNodeWithContentDescription("Actions for ${playlist.name}").performClick()
        compose.runOnIdle { bar().selection?.actions?.first()?.onClick?.invoke() }
        compose.waitForIdle()
        assertEquals(1, saves)
        compose.runOnIdle {
            assertEquals(
                "Undo remove ${playlist.name} from your library",
                bar().undo?.contentDescription
            )
            bar().undo?.onClick?.invoke()
        }
        assertEquals(2, saves)
    }

    @Test
    fun showOffersSaveAndLatestEpisode() {
        val (gutter, bar) = showShow()
        openGutter(gutter, showKey)
        compose.onNodeWithContentDescription("Actions for ${show.name}").performClick()
        compose.runOnIdle {
            assertEquals(
                listOf(
                    "Save ${show.name} to your library",
                    "Add latest episode of ${show.name} to queue"
                ),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun showLatestActionQueues() {
        var queued = 0
        val (gutter, bar) = showShow(onQueueLatest = { queued++ })
        openGutter(gutter, showKey)
        compose.onNodeWithContentDescription("Actions for ${show.name}").performClick()
        compose.runOnIdle {
            bar().selection?.actions?.first { it.key == "addLatestToQueue" }?.onClick?.invoke()
        }
        assertEquals(1, queued)
    }
}
