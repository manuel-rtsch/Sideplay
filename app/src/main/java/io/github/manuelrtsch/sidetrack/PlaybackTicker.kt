package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.nativeAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the playback clock. With a native player it polls events and advances the seek bar only
 * while the UI is on screen and playing; without one it simulates progress so local fixtures still
 * move.
 */
internal class PlaybackTicker(
    private val scope: CoroutineScope,
    private val session: ServiceRuntime?,
    private val store: UiStateStore,
    private val clock: PlaybackClock,
    private val driver: PlaybackDriver,
    private val uiVisible: StateFlow<Boolean>,
    private val simulatePlayback: Boolean
) {
    fun start() {
        val runtime = session
        if (runtime == null || !runtime.nativeAvailable) {
            if (simulatePlayback) {
                // Local fixtures and builds without the native library still need a moving progress bar.
                scope.launch {
                    // Suspend on the playing flag instead of polling: a paused player costs no wakeups.
                    while (true) {
                        store.state.map { it.isPlaying }.first { it }
                        while (store.value.isPlaying) {
                            delay(1_000L)
                            driver.tick()
                        }
                    }
                }
            }
        } else {
            scope.launch {
                // Native events only matter while something is playing. Poll tight when active, back
                // off to a slow heartbeat when idle so this does not run forever on the main path.
                while (true) {
                    delay(if (store.value.isPlaying) 300L else 3_000L)
                    val event = withContext(Dispatchers.IO) { runtime.playback.pollEvent() } ?: continue
                    driver.handlePlaybackEvent(event)
                }
            }
            scope.launch {
                // The seek bar only needs to advance while it is on screen and playing. When the
                // app is backgrounded or paused this suspends on the flows instead of waking up.
                while (true) {
                    uiVisible.combine(store.state) { visible, s ->
                        visible && s.isPlaying && !s.isBuffering
                    }.first { it }
                    while (uiVisible.value && store.value.isPlaying && !store.value.isBuffering) {
                        delay(POSITION_TICK_MS)
                        clock.advanceTick()
                    }
                }
            }
        }
    }

    private companion object {
        /** Seek-bar step while the now-playing screen is visible. */
        const val POSITION_TICK_MS = 1_000L
    }
}
