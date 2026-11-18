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
            "sidetrack/collection/{type}/{id}",
            "sidetrack/artist/{id}",
            "sidetrack/search",
            "sidetrack/search/scopes",
            "sidetrack/now-playing",
            "sidetrack/queue",
            "sidetrack/settings",
            "sidetrack/about"
        ).forEach { route ->
            assertTrue("$route should Back", keypadBackIsLeftAction(route, canPop = true))
        }
    }

    @Test
    fun `Home Sign in and Welcome leave Back to the system`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/home", canPop = true))
        assertFalse(keypadBackIsLeftAction("sidetrack/sign-in", canPop = true))
        assertFalse(keypadBackIsLeftAction("sidetrack/welcome", canPop = true))
    }

    @Test
    fun `a root destination cannot pop even if its route usually has a back action`() {
        assertFalse(keypadBackIsLeftAction("sidetrack/library", canPop = false))
    }
}
