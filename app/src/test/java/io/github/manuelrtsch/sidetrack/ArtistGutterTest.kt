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
 * The library's artist rows carry the same grey meatball gutter as every other row. Pressing it
 * publishes Follow/Unfollow and Artist radio to the contextual bar instead of growing two squares
 * of its own.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(shadows = [RecordingHapticShadow::class])
class ArtistGutterTest {
    @get:Rule val compose = createComposeRule()

    private val artist = MockSpotifyDataSource.artists.first()
    private val key = artist.key.encode()
    private val actions = "Actions for ${artist.name}"

    private fun showRow(
        followed: Boolean = false,
        canFollow: Boolean = true,
        canRadio: Boolean = true,
        onFollow: () -> Unit = {},
        onRadio: () -> Unit = {},
        onOpen: () -> Unit = {}
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
                        ArtistGutterRow(
                            key,
                            artist,
                            gutterState,
                            barState,
                            followed,
                            canFollow = canFollow,
                            canRadio = canRadio,
                            onFollow = onFollow,
                            onRadio = onRadio,
                            onClick = onOpen
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
    fun gutterCarriesOneMeatballSquare() {
        val (state, _) = showRow()
        openGutter(state)
        compose.onNodeWithContentDescription(actions).assertExists()
        compose.onNodeWithContentDescription("Follow ${artist.name}").assertDoesNotExist()
        compose.onNodeWithContentDescription("Artist radio for ${artist.name}").assertDoesNotExist()
    }

    @Test
    fun optionsOfferFollowAndRadio() {
        val (state, bar) = showRow()
        openGutter(state)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Follow ${artist.name}", "Artist radio for ${artist.name}"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun followedArtistOffersUnfollow() {
        val (state, bar) = showRow(followed = true)
        openGutter(state)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Unfollow ${artist.name}", "Artist radio for ${artist.name}"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }

    @Test
    fun actionsFollowAndStartRadioWithoutOpeningTheArtist() {
        var follows = 0
        var radios = 0
        var opens = 0
        val (state, bar) = showRow(
            onFollow = { follows++ },
            onRadio = { radios++ },
            onOpen = { opens++ }
        )
        openGutter(state)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            val actions = bar().selection?.actions.orEmpty()
            actions.first { it.key == "follow" }.onClick()
            actions.first { it.key == "artistRadio" }.onClick()
        }
        assertEquals(1, follows)
        assertEquals(1, radios)
        assertEquals(0, opens)
    }

    @Test
    fun radioOptionIsHiddenWhenTheServiceHasNoRadio() {
        val (state, bar) = showRow(canRadio = false)
        openGutter(state)
        compose.onNodeWithContentDescription(actions).performClick()
        compose.runOnIdle {
            assertEquals(
                listOf("Follow ${artist.name}"),
                bar().selection?.actions?.map { it.contentDescription }
            )
        }
    }
}
