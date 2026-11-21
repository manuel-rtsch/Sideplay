package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeypadBackNavigationTest {
    @Test
    fun `screens with a left back action handle the keypad Back key`() {
        listOf(
            "sidetrack/history",
            "sidetrack/library",
            "sidetrack/collection/{key}",
            "sidetrack/artist/{key}",
            "sidetrack/search",
            "sidetrack/search/results",
            "sidetrack/search/scopes",
            "sidetrack/now-playing",
            "sidetrack/queue",
            "sidetrack/settings",
            "sidetrack/about",
            "sidetrack/sign-in"
        ).forEach { route ->
            assertTrue("$route should Back", keypadBackIsLeftAction(route, canPop = true))
        }
    }

    @Test
    fun `Home and Welcome leave Back to the system`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/home", canPop = true))
        assertFalse(keypadBackIsLeftAction("sidetrack/welcome", canPop = true))
        // Sign in carries a Back action of its own now, so the keypad key follows it and only
        // falls back to the system when nothing is behind (a signed-out launch starts here).
        assertFalse(keypadBackIsLeftAction("sidetrack/sign-in", canPop = false))
    }

    @Test
    fun `a root destination cannot pop even if its route usually has a back action`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/library", canPop = false))
    }
}
