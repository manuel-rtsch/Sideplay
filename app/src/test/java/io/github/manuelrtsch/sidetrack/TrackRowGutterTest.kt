package io.github.manuelrtsch.sidetrack

import android.os.Looper
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * A row's gutter has to open once and then stay open, whichever hand opened it. It is the only
 * route to Save and Add to queue from a track list, and it is invisible to a screenshot test: the
 * row renders correctly whether the gutter is open, opening, or blinking shut again.
 *
 * The keypad path drives [TrackRowFocusState] directly rather than through the Compose focus
 * system, because the failure it guards against is an event-ordering one: a control that appears
 * *inside* an open gutter reports `isFocused = false` once when it is attached, and treating that
 * as "the row lost focus" closes the gutter on the frame it opened. On a device that reads as a
 * flash. The touch path goes through a real long press, since that is the gesture being added.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrackRowGutterTest {
    @get:Rule val compose = createComposeRule()

    private val track = MockSpotifyDataSource.albums.first().tracks.first()
    private val key = track.id
    private val save = "Save ${track.title} to Liked Songs"
    private val longPressHoldMs = 700L
    private val queue = "Add ${track.title} to queue"

    private fun showRow(
        saved: Boolean = false,
        onPlay: () -> Unit = {},
        onSave: () -> Unit = {}
    ): () -> TrackRows {
        compose.mainClock.autoAdvance = false
        var rows: TrackRows? = null
        compose.setContent {
            SidekitPreviewFrame {
                val state = rememberTrackRows(
                    SpotifyUiState(savedTrackIds = if (saved) setOf(track.id) else emptySet()),
                    onSave = { onSave() },
                    onEnsureSaved = {},
                    onQueue = {}
                )
                rows = state
                LazyColumn {
                    item(key = key) {
                        TrackRow(key, track, state, onClick = onPlay)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        return { requireNotNull(rows) }
    }

    /**
     * Steps past the gutter delay, the same 250 ms the focus has to settle for, and far enough into
     * the 160 ms animation for the squares to be composed. The effect's `delay` runs on the main
     * looper, so the looper has to idle as well as the frame clock.
     */
    private fun settle() {
        repeat(4) {
            compose.mainClock.advanceTimeBy(120)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(120))
            compose.waitForIdle()
        }
    }

    /**
     * Holds the row for the platform's long-press timeout, as a finger on a touch screen would.
     *
     * The press is split around the wait on purpose: [rowLongPress] times the hold with a
     * coroutine timeout, so the looper has to run while the finger is still down. A single
     * injected `longClick()` only moves the events' timestamps, which satisfies the framework's
     * timestamp-based detectors but not this one.
     */
    private fun longPressRow() {
        compose.onNodeWithText(track.title).performTouchInput {
            down(center)
            // Hold past the platform's long-press timeout, then release.
            advanceEventTime(longPressHoldMs)
            up()
        }
        settle()
    }

    @Test
    fun gutterOpensAfterTheFocusSettles() {
        val rows = showRow()
        assertNull(rows().focus.openKey)
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        assertEquals(key, rows().focus.openKey)
        compose.onNodeWithContentDescription(save).assertExists()
        compose.onNodeWithContentDescription(queue).assertExists()
    }

    @Test
    fun gutterStaysOpenWhileTheRowKeepsFocus() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(save).assertExists()
        // The squares are composed now. Their attach-time focus event must not close the gutter.
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        compose.waitForIdle()
        assertEquals(key, rows().focus.openKey)
        compose.onNodeWithContentDescription(save).assertExists()
        compose.onNodeWithContentDescription(queue).assertExists()
    }

    @Test
    fun gutterClosesWhenTheFocusLeaves() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(save).assertExists()
        compose.runOnIdle { rows().focus.rowUnfocused(key) }
        settle()
        assertNull(rows().focus.openKey)
        compose.onNodeWithContentDescription(save).assertDoesNotExist()
    }

    @Test
    fun gutterReflectsTheSavedState() {
        val rows = showRow(saved = true)
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription("Remove ${track.title} from Liked Songs").assertExists()
    }

    @Test
    fun gutterCarriesExactlyTwoSquares() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(save).assertExists()
        compose.onNodeWithContentDescription(queue).assertExists()
        // Save and Queue are the ceiling; navigation goes to Options.
        compose.onNodeWithContentDescription("Open artist").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open album").assertDoesNotExist()
    }

    @Test
    fun draggingTheRowDoesNotRevealTheGutter() {
        val rows = showRow()
        compose.onNodeWithText(track.title).performTouchInput { swipeLeft() }
        settle()
        assertNull(rows().focus.touchedKey)
        assertNull(rows().focus.openKey)
        compose.onNodeWithContentDescription(save).assertDoesNotExist()
    }

    @Test
    fun longPressRevealsTheGutterWithoutAnyFocus() {
        val rows = showRow()
        longPressRow()
        assertEquals(key, rows().focus.touchedKey)
        assertNull(rows().focus.openKey)
        compose.onNodeWithContentDescription(save).assertExists()
        compose.onNodeWithContentDescription(queue).assertExists()
    }

    @Test
    fun longPressSquaresAreTappable() {
        var saves = 0
        showRow(onSave = { saves++ })
        longPressRow()
        compose.onNodeWithContentDescription(save).performClick()
        assertEquals(1, saves)
    }

    @Test
    fun keypadFocusDropsATouchReveal() {
        val rows = showRow()
        longPressRow()
        assertEquals(key, rows().focus.touchedKey)
        // Down on the keypad: the focus lands on another row, so the held row gives its squares up.
        compose.runOnIdle { rows().focus.rowFocused("next-row") }
        settle()
        assertNull(rows().focus.touchedKey)
        compose.onNodeWithContentDescription(save).assertDoesNotExist()
    }

    @Test
    fun aTouchRevealElsewhereClosesTheFocusedGutter() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(save).assertExists()
        // Holding a different row while the keypad still points at this one must not show both.
        compose.runOnIdle { rows().focus.reveal("other-row") }
        settle()
        assertEquals("other-row", rows().focus.touchedKey)
        compose.onNodeWithContentDescription(save).assertDoesNotExist()
    }

    @Test
    fun aTouchRevealBeforeTheFocusSettlesStillLeavesOneGutter() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        // Long-pressed before the 250 ms settle fires, so the pending settle would open this row.
        compose.runOnIdle { rows().focus.reveal("other-row") }
        settle()
        assertEquals(false, rows().focus.isOpen(key))
        assertEquals(true, rows().focus.isOpen("other-row"))
        compose.onNodeWithContentDescription(save).assertDoesNotExist()
    }

    @Test
    fun tappingARevealedRowClosesItInsteadOfPlaying() {
        var plays = 0
        val rows = showRow(onPlay = { plays++ })
        longPressRow()
        compose.onNodeWithText(track.title).performClick()
        settle()
        assertNull(rows().focus.touchedKey)
        assertEquals(0, plays)
        compose.onNodeWithContentDescription(save).assertDoesNotExist()
    }
}
