package io.github.manuelrtsch.sidetrack.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Writes small state files off the caller's thread, keeping only the newest snapshot.
 *
 * The podcast stores update while playback is running, and the callers are on the main thread.
 * An [androidx.core.util.AtomicFile] replacement is a full serialise plus an `fsync`, so doing it
 * inline would stall a frame every few seconds. Conflating means a burst of updates produces one
 * write of the final state instead of one write per update, and the loop stays sequential so two
 * writers never touch the same file at once.
 *
 * Cancellation is deliberately not used: a half-finished `AtomicFile` write must run its own
 * `finishWrite`/`failWrite`, so the loop drains what it has and drops the rest.
 */
internal class ConflatedSaver<T>(
    scope: CoroutineScope,
    private val write: (T) -> Unit
) {
    private val pending = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (value in pending) {
                runCatching { write(value) }
            }
        }
    }

    fun submit(value: T) {
        pending.trySend(value)
    }
}
