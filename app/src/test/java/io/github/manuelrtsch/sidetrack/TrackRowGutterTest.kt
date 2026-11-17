package io.github.manuelrtsch.sidetrack

import android.os.Looper
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.ActionTone
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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
 * flash. The touch path goes through a real left swipe, since that is the gesture being added.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class TrackRowGutterTest {
    @get:Rule val compose = createComposeRule()

    private val track = MockSpotifyDataSource.albums.first().tracks.first()
    private val key = track.id
    private val actions = "Actions for ${track.title}"

    @Before
    fun resetHaptics() = RecordingHapticShadow.reset()

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
                    SavedUiState(savedTrackIds = if (saved) setOf(track.key.encode()) else emptySet()),
                    onSave = { onSave() },
                    onEnsureSaved = {},
                    onAddToQueue = {}
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

    /** Swipes the row left, the touch route to its gutter. */
    private fun swipeRow() {
        compose.onNodeWithText(track.title).performTouchInput { swipeLeft() }
        settle()
    }

    @Test
    fun gutterOpensAfterTheFocusSettles() {
        val rows = showRow()
        assertNull(rows().focus.openKey)
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        assertEquals(key, rows().focus.openKey)
        compose.onNodeWithContentDescription(actions).assertExists()
    }

    @Test
    fun gutterStaysOpenWhileTheRowKeepsFocus() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).assertExists()
        // The trigger is composed now. Its attach-time focus event must not close the gutter.
        compose.mainClock.advanceTimeBy(2_000)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        compose.waitForIdle()
        assertEquals(key, rows().focus.openKey)
        compose.onNodeWithContentDescription(actions).assertExists()
    }

    @Test
    fun gutterClosesWhenTheFocusLeaves() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).assertExists()
        compose.runOnIdle { rows().focus.rowUnfocused(key) }
        settle()
        assertNull(rows().focus.openKey)
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }

    @Test
    fun gutterReflectsTheSavedState() {
        val rows = showRow(saved = true)
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Remove ${track.title} from Liked Songs", "Add ${track.title} to queue"),
                rows().bar.selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun unsavingOffersUndoInTheBar() {
        var saves = 0
        val rows = showRow(saved = true, onSave = { saves++ })
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            val unsave = rows().bar.selection?.actions?.first()
            assertEquals(ActionTone.Destructive, unsave?.tone)
            unsave?.onClick?.invoke()
        }
        compose.waitForIdle()

        assertEquals(1, saves)
        compose.runOnIdle {
            assertEquals(
                "Undo remove ${track.title} from Liked Songs",
                rows().bar.undo?.contentDescription
            )
            rows().bar.undo?.onClick?.invoke()
        }
        assertEquals(2, saves)
    }

    @Test
    fun gutterCarriesOneTrigger() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).assertExists()
        // The row itself no longer carries the actions; the contextual bar does.
        compose.onNodeWithContentDescription("Save ${track.title} to Liked Songs").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add ${track.title} to queue").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open artist").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open album").assertDoesNotExist()
    }

    @Test
    fun swipingTheRowRevealsTheGutter() {
        val rows = showRow()
        swipeRow()
        assertEquals(key, rows().focus.touchedKey)
        assertNull(rows().focus.openKey)
        compose.onNodeWithContentDescription(actions).assertExists()
    }

    @Test
    fun scrollingTheRowDoesNotRevealTheGutter() {
        val rows = showRow()
        compose.onNodeWithText(track.title).performTouchInput { swipeUp() }
        settle()
        assertNull(rows().focus.touchedKey)
        assertNull(rows().focus.openKey)
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }

    @Test
    fun swipeTriggerOpensTheBar() {
        val rows = showRow()
        swipeRow()
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Save ${track.title} to Liked Songs", "Add ${track.title} to queue"),
                rows().bar.selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun keypadFocusDropsATouchReveal() {
        val rows = showRow()
        swipeRow()
        assertEquals(key, rows().focus.touchedKey)
        // Down on the keypad: the focus lands on another row, so the swiped row gives its squares up.
        compose.runOnIdle { rows().focus.rowFocused("next-row") }
        settle()
        assertNull(rows().focus.touchedKey)
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }

    @Test
    fun aTouchRevealElsewhereClosesTheFocusedGutter() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).assertExists()
        // Swiping a different row while the keypad still points at this one must not show both.
        compose.runOnIdle { rows().focus.reveal("other-row") }
        settle()
        assertEquals("other-row", rows().focus.touchedKey)
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }

    @Test
    fun aTouchRevealBeforeTheFocusSettlesStillLeavesOneGutter() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        // Swiped before the 250 ms settle fires, so the pending settle would open this row.
        compose.runOnIdle { rows().focus.reveal("other-row") }
        settle()
        assertEquals(false, rows().focus.isOpen(key))
        assertEquals(true, rows().focus.isOpen("other-row"))
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }

    @Test
    fun aSwipeTicksThePhone() {
        showRow()
        swipeRow()
        // The swipe is the only feedback a finger gets that the gutter registered.
        assertEquals(listOf(HapticFeedbackConstants.CONTEXT_CLICK), RecordingHapticShadow.performed.toList())
    }

    @Test
    fun theKeypadRevealIsSilent() {
        val rows = showRow()
        compose.runOnIdle { rows().focus.rowFocused(key) }
        settle()
        compose.onNodeWithContentDescription(actions).assertExists()
        // The tick belongs to the swipe, not to the focus landing: a D-pad held through a list
        // must not buzz once per row it passes.
        assertEquals(0, RecordingHapticShadow.performed.size)
    }

    @Test
    fun tappingARevealedRowClosesItInsteadOfPlaying() {
        var plays = 0
        val rows = showRow(onPlay = { plays++ })
        swipeRow()
        compose.onNodeWithText(track.title).performClick()
        settle()
        assertNull(rows().focus.touchedKey)
        assertEquals(0, plays)
        compose.onNodeWithContentDescription(actions).assertDoesNotExist()
    }
}
