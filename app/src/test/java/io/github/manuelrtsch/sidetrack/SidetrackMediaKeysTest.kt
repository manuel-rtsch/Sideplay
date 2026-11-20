package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidetrackMediaKeysTest {
    private val commands = mutableListOf<MediaKeyCommand>()
    private val player = MediaKeyTransport { commands += it }
    private val keys = SidetrackMediaKeys { player }

    @Test fun `play pause asks for a toggle`() {
        assertTrue(keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertEquals(listOf(MediaKeyCommand.TogglePlayback), commands)
    }

    @Test fun `a headset hook is a toggle too`() {
        assertTrue(keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
        assertEquals(listOf(MediaKeyCommand.TogglePlayback), commands)
    }

    @Test fun `the sundial edges skip tracks`() {
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))

        assertEquals(listOf(MediaKeyCommand.Next, MediaKeyCommand.Previous), commands)
    }

    @Test fun `a repeat is consumed without firing again`() {
        keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT, repeat = 1))

        assertTrue(commands.isEmpty())
    }

    @Test fun `the release is consumed without a second command`() {
        assertTrue(keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT)))
        assertTrue(keys.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT)))

        assertEquals(listOf(MediaKeyCommand.Next), commands)
    }

    @Test fun `a key that is not ours is left alone`() {
        assertFalse(keys.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        assertTrue(commands.isEmpty())
    }

    @Test fun `with no player the key stays available to the platform`() {
        val orphan = SidetrackMediaKeys { null }

        assertFalse(orphan.handle(event(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
        assertFalse(orphan.handle(event(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)))
    }

    private fun event(action: Int, keyCode: Int, repeat: Int = 0) = KeyEvent(0L, 0L, action, keyCode, repeat)
}
