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
 * Keeps the Jellyfin server's session in step with Sidetrack's shared player.
 *
 * The server needs `reportPlaybackStart`, periodic `reportPlaybackProgress` and one
 * `reportPlaybackStopped` per item, and D12 says a track is marked played only when the player
 * reports that it actually ended — not when the user skips it.
 *
 * Nothing here is on the playback path: every request is fire-and-forget, failures are swallowed,
 * and a slow server can never stall audio. Two signals drive it:
 *
 * - the engine's [PlaybackEngineState], which tells us which source is loaded, whether it is
 *   playing and how far it has got;
 * - the engine's end-of-track flow, which is the only reliable "finished" signal.
 *
 * The reporter only claims tracks (`MediaType.Track`): Jellyfin's music model has no episodes.
 */
internal class JellyfinPlaybackReporter(
    private val api: () -> JellyfinApi?,
    private val playbackInfo: (String) -> JellyfinPlaybackInfo?,
    private val state: StateFlow<PlaybackEngineState>,
    private val ended: Flow<String?>,
    private val currentItem: () -> MediaItem?,
    private val position: () -> Long,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var job: Job? = null

    /** Only touched by the single collector coroutine, so no locking is needed. */
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
            ?.takeIf { it.service == ServiceId.Jellyfin && it.type == MediaType.Track }
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
                reportProgress(current, paused = true)
            }
            return
        }
        current.wasPlaying = true
        val now = clock()
        if (now - current.lastReportAt >= PROGRESS_INTERVAL_MS) {
            current.lastReportAt = now
            reportProgress(current, paused = false)
        }
    }

    private suspend fun onEnded(uri: String?) {
        val current = active ?: return
        // The ViewModel may already have advanced to the next item, so match the player's own uri
        // rather than whatever is current now; a null uri carries no identity but is still ours.
        if (uri != null && current.sourceUri.isNotEmpty() && !uri.equals(current.sourceUri, ignoreCase = true)) return
        val atEnd = current.durationMs.takeIf { it > 0L } ?: position()
        reportStopped(current, position = atEnd, failed = false)
        current.ended = true
        // The engine also emits a paused state for the same source; do not report that as a pause.
        current.wasPlaying = false
        // D12: only a real end counts as a play; a skip reports where playback stopped above.
        runCatching { api()?.markPlayed(current.itemId) }
        current.lastPositionMs = atEnd
    }

    private suspend fun start(itemId: String, sourceUri: String, startPositionMs: Long): Active {
        val info = playbackInfo(itemId)
        val duration = currentItem()?.takeIf { it.id == itemId }?.durationMs ?: 0L
        val active = Active(itemId, sourceUri, duration, info)
        active.lastPositionMs = startPositionMs.coerceAtLeast(0L)
        active.lastReportAt = clock()
        active.wasPlaying = state.value.isPlaying
        reportStart(active)
        return active
    }

    private suspend fun finish(active: Active, position: Long) {
        if (!active.ended) reportStopped(active, position.coerceAtLeast(0L), failed = false)
        this.active = null
    }

    private suspend fun reportStart(active: Active) {
        val report = active.report(active.lastPositionMs, paused = !active.wasPlaying)
        runCatching { api()?.reportStart(report) }
    }

    private suspend fun reportProgress(active: Active, paused: Boolean) {
        val report = active.report(active.lastPositionMs, paused)
        runCatching { api()?.reportProgress(report) }
    }

    private suspend fun reportStopped(active: Active, position: Long, failed: Boolean) {
        val report = active.report(position, paused = false)
        runCatching { api()?.reportStopped(report, failed) }
    }

    private class Active(
        val itemId: String,
        val sourceUri: String,
        val durationMs: Long,
        val info: JellyfinPlaybackInfo?
    ) {
        var lastPositionMs = 0L
        var wasPlaying = false
        var lastReportAt = 0L
        var ended = false

        fun report(positionMs: Long, paused: Boolean) = JellyfinPlaybackReport(
            itemId = itemId,
            positionMs = positionMs.coerceAtLeast(0L),
            mediaSourceId = info?.mediaSourceId,
            playSessionId = info?.playSessionId,
            paused = paused,
            directPlay = info?.directPlay ?: true
        )
    }

    private sealed interface Signal {
        data class State(val value: PlaybackEngineState) : Signal
        data class Ended(val uri: String?) : Signal
    }

    private companion object {
        /** Jellyfin clients report roughly every 10 seconds; more only adds requests. */
        const val PROGRESS_INTERVAL_MS = 10_000L
    }
}
