package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import hrln.interactive.sidekit.design.input.KeypadModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun everyRowCarriesTheHabitItTeaches() {
        assertEquals(
            listOf(KeypadHabit.Back, KeypadHabit.Options, KeypadHabit.RowMenu),
            keypadEssentials(KeypadModule.T9).map { it.habit }
        )
        assertEquals(
            listOf(KeypadHabit.Centre, KeypadHabit.Edges, KeypadHabit.Back, KeypadHabit.Options, KeypadHabit.RowMenu),
            keypadEssentials(KeypadModule.Sundial).map { it.habit }
        )
        assertEquals(
            listOf(KeypadHabit.Back, KeypadHabit.Options, KeypadHabit.RowMenu),
            keypadEssentials(KeypadModule.GamePad).map { it.habit }
        )
    }

    @Test
    fun aKeyPressNamesTheRowItDemonstrates() {
        assertEquals(KeypadHabit.Options, keypadHabitForKey(KeypadModule.T9, KeyEvent.KEYCODE_ENDCALL))
        assertEquals(KeypadHabit.Options, keypadHabitForKey(KeypadModule.Qwerty, KeyEvent.KEYCODE_MENU))
        assertEquals(KeypadHabit.Back, keypadHabitForKey(KeypadModule.T9, KeyEvent.KEYCODE_CALL))
        // The game controller's B is rewritten to Back before a screen sees it.
        assertEquals(KeypadHabit.Back, keypadHabitForKey(KeypadModule.GamePad, KeyEvent.KEYCODE_BACK))
        assertEquals(KeypadHabit.Centre, keypadHabitForKey(KeypadModule.Sundial, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(KeypadHabit.Edges, keypadHabitForKey(KeypadModule.Sundial, KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(KeypadHabit.Edges, keypadHabitForKey(KeypadModule.Sundial, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
    }

    @Test
    fun keysThatTeachNothingStayUnclaimed() {
        // A headset sends play/pause too; only the Sundial's centre is that row.
        assertNull(keypadHabitForKey(KeypadModule.GamePad, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        // A letter is a letter on the mini QWERTY; its navigation cross is rewritten by Sidekit.
        assertNull(keypadHabitForKey(KeypadModule.Qwerty, KeyEvent.KEYCODE_T))
        assertNull(keypadHabitForKey(KeypadModule.GamePad, KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test
    fun cyclingVisitsEveryModuleAndWrapsAround() {
        var module = KeypadModule.T9
        val visited = buildList {
            repeat(KeypadModule.entries.size) {
                add(module)
                module = nextKeypadModule(module)
            }
        }

        assertEquals(KeypadModule.entries.toSet(), visited.toSet())
        assertEquals(visited.first(), module)
    }
}
