package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.consumeHaltStop
import io.github.manuelrtsch.sidetrack.data.halting
import io.github.manuelrtsch.sidetrack.data.notePlayRequest
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import io.github.manuelrtsch.sidetrack.playback.debugName
import kotlin.math.abs

/**
 * Applies one engine event to the clock, the queue and the store. The event `when` is kept apart
 * from the driver so the seek/resume rules can be read without paging through the queue lifecycle.
 */
internal class PlaybackEvents(
    private val clock: PlaybackClock,
    private val session: ServiceRuntime?,
    private val queue: QueueRepository,
    private val effects: PlaybackEffects,
    private val store: UiStateStore,
    private val matchesCurrent: (String?) -> Boolean,
    private val onEnded: () -> Unit
) {
    fun handle(event: PlaybackEvent) {
        val position = when (event) {
            is PlaybackEvent.Loading -> event.positionMs
            is PlaybackEvent.Playing -> event.positionMs
            is PlaybackEvent.Paused -> event.positionMs
            is PlaybackEvent.Position -> event.positionMs
            is PlaybackEvent.Seeked -> event.positionMs
            is PlaybackEvent.PositionCorrection -> event.positionMs
            else -> null
        } ?: clock.value
        session?.notePlayRequest(event.playRequestId, event.uri)
        SidetrackDebug.log(
            "event type=${event.debugName} uri=${event.uri.orEmpty()} positionMs=$position"
        )
        when (event) {
            is PlaybackEvent.Loading -> if (matchesCurrent(event.uri) && !clock.staleResumeEvent(position)) {
                // The player has accepted the new item: the hold can end and the clock may run
                // from the position the player will actually start at.
                clock.endStart()
                if (clock.activeSeekTarget() == null) {
                    clock.anchor(position)
                    session?.playback?.updatePosition(position)
                    clock.set(position)
                }
                // A load event is not a seek confirmation. While a local seek is pending it may
                // still report the old offset, so keep the bar at the target.
            }

            is PlaybackEvent.Playing -> if (matchesCurrent(event.uri)) {
                val playback = session?.playback
                // Ignore a playing event that belongs to a command the user already superseded
                // with a pause. Otherwise rapid taps leave the UI showing play with no sound.
                if (playback?.shouldApplyEvent(true) != false) {
                    clock.endStart()
                    // Keep the UI clock where it is. librespot's event position is the decoder
                    // position, which is ahead of the speaker by the PCM buffer; adopting it
                    // would make the bar jump forward on resume.
                    val current = clock.value
                    clock.anchor(current)
                    playback?.updateState(true, current)
                    store.update { it.copy(isPlaying = true) }
                }
            }

            is PlaybackEvent.Paused -> if (matchesCurrent(event.uri)) {
                val playback = session?.playback
                // Ignore a paused event that belongs to a command the user already superseded
                // with a resume. Otherwise the UI flickers back to paused after a resume.
                if (playback?.shouldApplyEvent(false) != false) {
                    clock.endStart()
                    val current = clock.value
                    clock.anchor(current)
                    playback?.updateState(false, current)
                    store.update { it.copy(isPlaying = false) }
                    effects.recordEpisodeProgress(force = true)
                }
            }

            is PlaybackEvent.Seeked, is PlaybackEvent.PositionCorrection -> {
                // The player itself confirms where it landed. This is the only event that may
                // end a local seek hold; a regular position event can still describe the old
                // clock while the seek is queued behind a slow fetch.
                if (clock.activeSeekTarget() != null) {
                    clock.adoptSeek(position)
                } else if (!clock.awaitingStartConfirmation && !clock.staleResumeEvent(position)) {
                    clock.anchor(position)
                    session?.playback?.updatePosition(position)
                    clock.set(position)
                }
                effects.recordEpisodeProgress(force = true)
            }

            is PlaybackEvent.Position -> {
                // Regular decoder updates must not end a pending seek: they can still be from
                // the generation before the seek, especially on a slow connection.
                if (clock.activeSeekTarget() != null) return
                // Position events carry no item identity. Ignore them while a newly started item
                // is still being loaded, otherwise the previous item's clock can rewind the bar.
                if (clock.awaitingStartConfirmation) return
                // The ticker owns the seek bar. Only adopt an event position when it is far
                // enough away to be a real seek from another client, not the decoder's normal
                // lead over the speaker.
                val current = clock.value
                if (!clock.staleResumeEvent(position) && abs(position - current) > POSITION_EVENT_JUMP_MS) {
                    clock.anchor(position)
                    session?.playback?.updatePosition(position)
                    clock.set(position)
                    effects.recordEpisodeProgress(force = true)
                }
            }

            // librespot 0.8 has no shuffle/repeat setters, but it emits these when another
            // client changes the player. Adopt them so the UI does not drift from the service.
            is PlaybackEvent.Shuffle ->
                effects.applyQueue(queue.setShuffle(event.enabled), syncNowPlaying = false)

            is PlaybackEvent.Repeat -> {
                val mode = when {
                    event.track -> RepeatMode.Track
                    event.context -> RepeatMode.Context
                    else -> RepeatMode.Off
                }
                effects.applyQueue(queue.setRepeat(mode), syncNowPlaying = false)
            }

            is PlaybackEvent.Ended -> if (matchesCurrent(event.uri)) {
                // A previous generation can report its end while the replacement is still
                // loading. The queue has already moved on, so this must not advance it again.
                if (!clock.awaitingStartConfirmation) onEnded()
            }

            is PlaybackEvent.Stopped -> if (matchesCurrent(event.uri)) {
                // Closing the app halts the audio and saves its offset; the player's "stopped"
                // echo can arrive later, after a recreated ViewModel has restored that offset.
                // Ignoring it keeps the restored position and leaves the queue untouched.
                if (session?.consumeHaltStop(event.uri, event.playRequestId) == true) return
                // The old generation's stop can arrive after the user already started the next
                // item (or the same item again). When the shared queue still has a current item,
                // the player is being replaced, not stopped: clearing here made Now Playing lose
                // the track while the new audio kept playing. A real notification stop clears the
                // queue first, so it still falls through.
                if (clock.awaitingStartConfirmation && queue.snapshot.current != null) return
                // Stop from the notification or lock screen: clear both the UI and the queue so
                // reopening Sidetrack cannot restore the track that was stopped.
                clock.endStart()
                clock.clearSeek()
                session?.playback?.updateState(false, 0L)
                val halting = session?.halting == true
                // Stopping is not a switch to another item: the track that was playing must not
                // slide into recently played just because playback ended.
                effects.applyQueue(
                    if (halting) queue.snapshot else queue.stop(),
                    positionMs = 0L,
                    playing = false,
                    recordPrevious = false
                )
            }

            is PlaybackEvent.Unavailable -> if (matchesCurrent(event.uri)) {
                clock.endStart()
                clock.clearSeek()
                store.update { it.copy(isPlaying = false).say("This track isn't available") }
            }

            is PlaybackEvent.Unknown -> Unit
        }
    }
}
