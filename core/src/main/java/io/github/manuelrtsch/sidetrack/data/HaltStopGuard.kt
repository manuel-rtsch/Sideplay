package io.github.manuelrtsch.sidetrack.data

/**
 * Recognises the "stopped" event produced by the app-close path so a late echo cannot reset the
 * restored position or clear the queue.
 *
 * librespot assigns every start a new play request id. Remembering the id that was playing when
 * the app halted makes the echo distinguishable from a genuine stop after the user reopened and
 * resumed: the new playback carries a different id, the old echo carries the remembered one.
 */
class HaltStopGuard(private val graceMs: Long) {
    private var uri: String? = null
    private var playRequestId = -1L
    private var armedAt = 0L

    @Synchronized
    fun arm(uri: String?, playRequestId: Long, now: Long) {
        this.uri = uri
        this.playRequestId = playRequestId
        armedAt = now
    }

    /**
     * Returns true when this stopped event belongs to the halted player. When both sides know a
     * play request id, the id decides at any age; the grace window is only a fallback for an event
     * that carries no id.
     */
    @Synchronized
    fun consume(uri: String?, playRequestId: Long, now: Long): Boolean {
        val expected = this.uri ?: return false
        if (uri != null && !uri.equals(expected, ignoreCase = true)) return false
        if (this.playRequestId >= 0 && playRequestId >= 0) {
            if (playRequestId != this.playRequestId) return false
            clear()
            return true
        }
        if (now - armedAt > graceMs) {
            clear()
            return false
        }
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        uri = null
        playRequestId = -1L
        armedAt = 0L
    }
}
