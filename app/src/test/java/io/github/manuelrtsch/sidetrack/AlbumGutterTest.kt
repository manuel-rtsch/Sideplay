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
 * Album rows share the artist rows' grey meatball gutter. The bar offers Add to queue only;
 * Save/Unsave stays on the album page.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class AlbumGutterTest {
    @get:Rule val compose = createComposeRule()

    private val album = MockSpotifyDataSource.albums.first()
    private val key = album.key.encode()
    private val actions = "Actions for ${album.title}"

    private fun showRow(onQueue: () -> Unit = {}): Pair<() -> RowGutterState, () -> RowActionBarState> {
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
    fun optionsOfferQueueOnly() {
        val (gutter, bar) = showRow()
        openGutter(gutter)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Add ${album.title} to queue"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
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
