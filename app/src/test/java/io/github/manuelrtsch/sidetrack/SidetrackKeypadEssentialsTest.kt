package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.input.KeypadModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SidetrackKeypadEssentialsTest {
    @Test
    fun theSundialTeachesNavigationAndPlayback() {
        val essentials = keypadEssentials(KeypadModule.Sundial)

        assertEquals(listOf("Centre", "Edges", "Tab", "Enter", "•••"), essentials.map { it.key })
        assertTrue(essentials.any { it.detail == "Tap to play or pause. Hold to confirm." })
        assertTrue(essentials.any { it.detail.contains("left edge") && it.detail.contains("next") })
    }

    @Test
    fun everyModuleKeepsItsBackOptionsAndRowMenu() {
        KeypadModule.entries.forEach { module ->
            val essentials = keypadEssentials(module)

            assertTrue("$module should teach Back", essentials.any { it.detail.contains("back a screen") })
            assertTrue("$module should teach Options", essentials.any { it.detail.contains("actions") })
            assertTrue("$module should teach the row menu", essentials.any { it.key == "•••" })
        }
    }
}
