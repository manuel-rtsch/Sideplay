package io.github.manuelrtsch.sidetrack

import hrln.interactive.sidekit.design.input.KeypadModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The input devices the Sidephone's swappable keypads appear as, measured with `getevent -lp`:
 * one device per module, plus `gxa535_phone` for the phone's own keys.
 */
class SidetrackKeypadDevicesTest {
    @Test
    fun everySwappableModuleIsNamedByItsInputDevice() {
        assertEquals(KeypadModule.T9, keypadModuleForDevice("gxa535_t9"))
        assertEquals(KeypadModule.Qwerty, keypadModuleForDevice("gxa535_qwerty"))
        assertEquals(KeypadModule.Sundial, keypadModuleForDevice("gxa535_media"))
        assertEquals(KeypadModule.GamePad, keypadModuleForDevice("gxa535_gamepad"))
    }

    @Test
    fun devicesThatAreNotAModuleStayUndecided() {
        // Older firmware exposed every module under this one name, so it cannot decide.
        assertNull(keypadModuleForDevice("gxa535_keyboard"))
        // The phone's own key device is not a swappable module.
        assertNull(keypadModuleForDevice("gxa535_phone"))
        assertNull(keypadModuleForDevice("mtk-kpd"))
        assertNull(keypadModuleForDevice("mt63xx-accdet Headset"))
        assertNull(keypadModuleForDevice(null))
    }
}
