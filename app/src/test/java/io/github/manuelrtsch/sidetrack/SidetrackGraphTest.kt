package io.github.manuelrtsch.sidetrack

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The keypad's Back key has to do exactly what the screen's left action does. A route in the set
 * that has no way back would pop to a screen the user did not come from; a route missing from it
 * would strand the user on a screen whose only exit is a button the D-pad cannot reach.
 */
@RunWith(RobolectricTestRunner::class)
class SidetrackGraphTest {
    @Test fun `home leaves Back to the system`() {
        assertFalse(keypadBackIsLeftAction(SIDE_PLAY_START, canPop = true))
    }

    @Test fun `welcome leaves Back to the system`() {
        assertFalse(keypadBackIsLeftAction(SIDE_PLAY_ONBOARDING, canPop = true))
    }

    @Test fun `sign in takes Back because the picker is a step behind it`() {
        assertTrue(keypadBackIsLeftAction(SIDE_PLAY_SIGN_IN, canPop = true))
    }

    @Test fun `library screens take Back`() {
        assertTrue(keypadBackIsLeftAction("sidetrack/library", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/history", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/collection/{key}", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/artist/{key}", canPop = true))
    }

    @Test fun `now playing and the queue take Back`() {
        assertTrue(keypadBackIsLeftAction("sidetrack/now-playing", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/queue", canPop = true))
    }

    @Test fun `radio screens take Back`() {
        assertTrue(keypadBackIsLeftAction("sidetrack/song-radio", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/artist-radio/{key}", canPop = true))
    }

    @Test fun `search, its results and its scope sheet take Back`() {
        assertTrue(keypadBackIsLeftAction(SIDE_PLAY_SEARCH, canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/search/results", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/search/scopes", canPop = true))
    }

    @Test fun `settings screens take Back`() {
        assertTrue(keypadBackIsLeftAction("sidetrack/settings", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/audio-quality", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/appearance", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/keyboard", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/sleep-timer", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/about", canPop = true))
    }

    @Test fun `the service picker takes Back because the picker is a step behind it`() {
        assertTrue(keypadBackIsLeftAction(SIDE_PLAY_SERVICE_PICKER, canPop = true))
    }

    @Test fun `the artwork and notes viewers take Back`() {
        assertTrue(keypadBackIsLeftAction("sidetrack/artwork/{artRes}?url={url}&label={label}&ratio={ratio}", canPop = true))
        assertTrue(keypadBackIsLeftAction("sidetrack/notes/{key}", canPop = true))
    }

    @Test fun `a JSON null artwork url does not become a null route argument`() {
        // Navigation's StringType turns the literal "null" into a real null, which fails a
        // non-nullable argument before the artwork screen is even built.
        val route = artworkRoute(0, "null", "null")
        assertTrue(route.contains("url=&"))
        assertFalse(route.contains("url=null"))
        assertTrue(route.contains("label=Artwork"))
        assertFalse(route.contains("label=null"))
    }

    @Test fun `an unknown route leaves Back to the system`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/not-a-screen", canPop = true))
    }

    @Test fun `a missing route leaves Back to the system`() {
        assertFalse(keypadBackIsLeftAction(null, canPop = true))
    }

    @Test fun `a screen with nothing behind it leaves Back to the system`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/settings", canPop = false))
    }

    @Test fun `the notification action asks for now playing`() {
        assertTrue(SidetrackLinks.handle(Intent(SidetrackLinks.ACTION_OPEN_NOW_PLAYING)))
    }

    @Test fun `any other action is ignored`() {
        assertFalse(SidetrackLinks.handle(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun `a missing intent is ignored`() {
        assertFalse(SidetrackLinks.handle(null))
    }

    @After
    fun tearDown() {
        // The pending request is process state; leaving it set would navigate the next test.
        SidetrackLinks.reset()
    }
}
