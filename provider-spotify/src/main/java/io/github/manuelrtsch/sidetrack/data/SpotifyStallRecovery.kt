package io.github.manuelrtsch.sidetrack.data

import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Rebuilds playback when the audio pipeline goes silent, and restarts the saved item when a play
 * command arrives with nothing loaded.
 *
 * When the access-point connection dies, librespot stops producing PCM and nothing in the player
 * notices: the app keeps reporting a playing track while delivering silence. Recovery reloads the
 * item where the user left it and gives up visibly after a few attempts instead of looping.
 */
internal class SpotifyStallRecovery(
    private val playback: PlaybackController,
    private val playbackScope: CoroutineScope,
    private val queue: QueueRepository,
    private val play: suspend (MediaKey, String, Long, Boolean) -> Unit
) {
    /** Stall recoveries started inside [STALL_RECOVERY_WINDOW_MS]; main thread only. */
    private val recoveries = ArrayDeque<Long>()

    /**
     * Starts the queue's current item when a play command arrives with nothing loaded.
     *
     * The queue survives process death but the native player does not, so after a restart there is
     * no source to resume. Used when no ViewModel is attached; a ViewModel installs the accurate
     * path via [PlaybackController.onResumeWithoutSource], because only it knows the saved offsets.
     */
    fun startRestoredItem() {
        val item = queue.snapshot.current ?: return
        val uri = item.uri ?: return
        val positionMs = playback.position
        SidetrackDebug.log("startRestoredItem uri=$uri positionMs=$positionMs")
        playbackScope.launch {
            // Nothing is loaded, so this is a cold start or a recovery: a fresh connection is
            // cheaper than discovering a dead one after the first load fails.
            runCatching { play(item.key, uri, positionMs.coerceAtLeast(0L), true) }
                .onFailure {
                    SidetrackDebug.log("startRestoredItem failed", it)
                    playback.pause()
                }
        }
    }

    /**
     * Rebuilds playback after the audio pipeline went silent.
     *
     * When the access-point connection dies, librespot stops producing PCM and nothing in the
     * player notices: the app kept reporting a playing track while delivering silence, and only
     * a manual press of play recovered it. This reloads the item where the user left it, and
     * gives up (visibly) after a few attempts instead of looping through reconnects.
     */
    fun recover(positionMs: Long) {
        val item = playback.current ?: return
        val uri = item.uri ?: return
        val now = SystemClock.elapsedRealtime()
        while (recoveries.isNotEmpty() && now - recoveries.first() > STALL_RECOVERY_WINDOW_MS) {
            recoveries.removeFirst()
        }
        if (recoveries.size >= STALL_RECOVERY_LIMIT) {
            SidetrackDebug.log("recoverPlayback giving up after ${recoveries.size} attempts")
            playback.pause()
            playback.emitNotice("Playback stopped — check the connection")
            return
        }
        recoveries.addLast(now)
        SidetrackDebug.log("recoverPlayback uri=$uri positionMs=$positionMs attempt=${recoveries.size}")
        playbackScope.launch {
            // A stall usually means the access-point connection is dead even when librespot has
            // not marked the session invalid yet, so rebuild it instead of reloading on the same
            // connection and stalling again.
            runCatching { play(item.key, uri, positionMs.coerceAtLeast(0L), true) }
                .onSuccess { playback.emitNotice(null) }
                .onFailure {
                    SidetrackDebug.log("recoverPlayback failed", it)
                    playback.pause()
                    playback.emitNotice("Playback stopped — check the connection")
                }
        }
    }

    private companion object {
        /** Stall recoveries allowed inside the window before playback gives up and says so. */
        const val STALL_RECOVERY_LIMIT = 3

        /** Window for [STALL_RECOVERY_LIMIT]; a one-off hiccup must not lock the user out. */
        const val STALL_RECOVERY_WINDOW_MS = 5 * 60_000L
    }
}
