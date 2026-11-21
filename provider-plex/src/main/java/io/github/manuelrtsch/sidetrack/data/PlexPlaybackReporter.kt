package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackEngineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Keeps Plex's dashboard and play history in step with the shared player.
 *
 * Plex wants a `/:/timeline` report when playback changes and periodically while playing, plus one
 * `/:/scrobble` when a track actually ends. Nothing here is on the audio path: every request is
 * fire-and-forget and a slow server cannot stall playback.
 */
internal class PlexPlaybackReporter(
    private val api: () -> PlexApi?,
    private val state: StateFlow<PlaybackEngineState>,
    private val ended: Flow<String?>,
    private val currentItem: () -> MediaItem?,
    private val position: () -> Long,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var job: Job? = null
    private var active: Active? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            merge(
                state.map { Signal.State(it) },
                ended.map { Signal.Ended(it) }
            ).collect { signal ->
                when (signal) {
                    is Signal.State -> onState(signal.value)
                    is Signal.Ended -> onEnded(signal.uri)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        active = null
    }

    private suspend fun onState(state: PlaybackEngineState) {
        val key = state.source?.id?.let(MediaKey::decode)
            ?.takeIf { it.service == ServiceId.Plex && it.type == MediaType.Track }
        val current = active
        if (key?.id != current?.itemId) {
            current?.let { finish(it, position = it.lastPositionMs) }
            active = key?.let { start(it.id, state.source?.uri.orEmpty(), state.positionMs) }
            return
        }
        if (current == null) return

        current.lastPositionMs = position().coerceAtLeast(0L)
        if (!state.isPlaying) {
            if (current.wasPlaying) {
                current.wasPlaying = false
                reportTimeline(current, "paused")
            }
            return
        }
        val now = clock()
        if (!current.wasPlaying) {
            // A resume is a state change and goes out immediately; the interval only throttles
            // the progress reports that follow.
            current.wasPlaying = true
            current.lastReportAt = now
            reportTimeline(current, "playing")
            return
        }
        if (now - current.lastReportAt >= PROGRESS_INTERVAL_MS) {
            current.lastReportAt = now
            reportTimeline(current, "playing")
        }
    }

    private suspend fun onEnded(uri: String?) {
        val current = active ?: return
        if (uri != null && current.sourceUri.isNotEmpty() && !uri.equals(current.sourceUri, ignoreCase = true)) return
        val atEnd = current.durationMs.takeIf { it > 0L } ?: position()
        reportTimeline(current, "stopped", positionMs = atEnd)
        current.ended = true
        current.wasPlaying = false
        runCatching { api()?.reportScrobble(current.itemId) }
        current.lastPositionMs = atEnd
    }

    private suspend fun start(itemId: String, sourceUri: String, startPositionMs: Long): Active {
        val duration = currentItem()?.takeIf { it.id == itemId }?.durationMs ?: 0L
        val active = Active(itemId, sourceUri, duration)
        active.lastPositionMs = startPositionMs.coerceAtLeast(0L)
        active.lastReportAt = clock()
        active.wasPlaying = state.value.isPlaying
        reportTimeline(active, if (active.wasPlaying) "playing" else "paused")
        return active
    }

    private suspend fun finish(active: Active, position: Long) {
        if (!active.ended) reportTimeline(active, "stopped", position.coerceAtLeast(0L))
        this.active = null
    }

    private suspend fun reportTimeline(active: Active, state: String, positionMs: Long = active.lastPositionMs) {
        runCatching {
            api()?.reportTimeline(active.itemId, state, positionMs.coerceAtLeast(0L), active.durationMs)
        }
    }

    private class Active(
        val itemId: String,
        val sourceUri: String,
        val durationMs: Long
    ) {
        var lastPositionMs = 0L
        var wasPlaying = false
        var lastReportAt = 0L
        var ended = false
    }

    private sealed interface Signal {
        data class State(val value: PlaybackEngineState) : Signal
        data class Ended(val uri: String?) : Signal
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
