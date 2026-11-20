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

/**
 * Album rows share the artist rows' grey meatball gutter. The bar offers Save/Unsave and Add to
 * queue; unsaving has to offer Undo because the row leaves the saved list immediately.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class AlbumGutterTest {
    @get:Rule val compose = createComposeRule()

    private val album = MockSpotifyDataSource.albums.first()
    private val key = album.key.encode()
    private val actions = "Actions for ${album.title}"

    private fun showRow(
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
                    item(key = key) {
                        AlbumGutterRow(
                            key,
                            album,
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

    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(120)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            compose.waitForIdle()
        }
    }

    private fun openGutter(state: () -> RowGutterState) {
        compose.runOnIdle { state().rowFocused(key) }
        settle()
    }

    @Test
    fun optionsOfferSaveAndQueue() {
        val (gutter, bar) = showRow()
        openGutter(gutter)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Save ${album.title} to your library", "Add ${album.title} to queue"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun savedAlbumOffersUnsave() {
        val (gutter, bar) = showRow(saved = true)
        openGutter(gutter)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Remove ${album.title} from your library", "Add ${album.title} to queue"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun unsavingOffersUndoInTheBar() {
        var saves = 0
        val (gutter, bar) = showRow(saved = true, onSave = { saves++ })
        openGutter(gutter)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            bar().selection?.actions?.first()?.onClick?.invoke()
        }
        compose.waitForIdle()
        assertEquals(1, saves)
        compose.runOnIdle {
            assertEquals(
                "Undo remove ${album.title} from your library",
                bar().undo?.contentDescription
            )
            bar().undo?.onClick?.invoke()
        }
        assertEquals(2, saves)
    }

    @Test
    fun queueActionQueuesTheAlbum() {
        var queued = 0
        val (gutter, bar) = showRow(onQueue = { queued++ })
        openGutter(gutter)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            bar().selection?.actions?.first { it.key == "addToQueue" }?.onClick?.invoke()
        }
        assertEquals(1, queued)
    }
}
