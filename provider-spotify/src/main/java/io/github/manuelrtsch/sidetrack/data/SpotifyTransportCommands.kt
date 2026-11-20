package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The transport commands the notification and lock screen can issue with no ViewModel attached.
 * The ViewModel follows the same queue semantics, so the UI adopts this state on return.
 */
internal class SpotifyTransportCommands(
    private val playback: PlaybackController,
    private val playbackScope: CoroutineScope,
    private val queue: QueueRepository,
    private val history: HistoryRepository,
    private val play: suspend (MediaKey, String, Long) -> Unit,
    private val onStart: () -> Unit
) {
    /**
     * Advances the queue when the notification/lock screen has no ViewModel to call back into.
     * The ViewModel follows the same queue semantics, so the UI adopts this state on return.
     */
    fun skipNext() {
        val before = queue.snapshot.current ?: return
        val snapshot = queue.next(auto = false)
        if (snapshot.current !== before) snapshot.current?.let { startFromTransport(before, it) }
    }

    /** Restarts the current item past the 3 s threshold, otherwise returns to queue history. */
    fun skipPrevious() {
        val current = queue.snapshot.current ?: return
        if (playback.position > 3_000L) {
            playbackScope.launch { runCatching { playback.seek(0L) } }
            return
        }
        val snapshot = queue.previous(playback.position)
        if (snapshot.current !== current) {
            snapshot.current?.let { startFromTransport(current, it) }
        } else {
            playbackScope.launch { runCatching { playback.seek(0L) } }
        }
    }

    private fun startFromTransport(previous: MediaItem, item: MediaItem) {
        onStart()
        // As in the app, recently played records the item the user left behind, not the one
        // that is starting now. An old entry for the starting item is dropped until the user
        // moves on from it again.
        history.record(previous, System.currentTimeMillis())
        history.remove(item)
        playback.setNowPlaying(item, 0L)
        val uri = item.uri ?: return
        playbackScope.launch {
            runCatching { play(item.key, uri, 0L) }.onFailure {
                playback.pause()
                playback.updateState(false, 0L)
            }
        }
    }
}
