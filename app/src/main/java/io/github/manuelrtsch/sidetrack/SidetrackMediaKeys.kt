package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent

/** The transport action a hardware media key asks for. */
internal enum class MediaKeyCommand { TogglePlayback, Next, Previous }

/** The player behind the hardware media keys; absent when no service can take the command. */
internal fun interface MediaKeyTransport {
    fun perform(command: MediaKeyCommand)
}

/**
 * Turns the media keys the Sundial sends into player commands.
 *
 * The Sundial's taps are not ordinary input events: Sidekit's [hrln.interactive.sidekit.design.input.HoldToAct]
 * consumes the real press to watch for a hold, then re-sends a synthetic press/release through the
 * activity. Those synthetic events bypass Android's input pipeline, so the platform never offers
 * them to the active media session. The activity therefore has to act on them itself.
 *
 * A hold has already been turned into a navigation key by Sidekit, so only short taps get here.
 * [handle] returns false for every key that is not a transport key, and for transport keys when
 * no player exists, leaving those to the platform.
 */
internal class SidetrackMediaKeys(
    private val transport: () -> MediaKeyTransport?
) {
    fun handle(event: KeyEvent): Boolean {
        val command = commandFor(event.keyCode) ?: return false
        val player = transport() ?: return false
        // A held key repeats; one command per press is enough. The release is still swallowed.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) player.perform(command)
        return true
    }

    private fun commandFor(keyCode: Int): MediaKeyCommand? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK -> MediaKeyCommand.TogglePlayback

        KeyEvent.KEYCODE_MEDIA_NEXT -> MediaKeyCommand.Next

        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> MediaKeyCommand.Previous

        else -> null
    }
}
