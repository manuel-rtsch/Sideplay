package io.github.manuelrtsch.sidetrack

import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.consumeHaltStop
import io.github.manuelrtsch.sidetrack.data.halting
import io.github.manuelrtsch.sidetrack.data.notePlayRequest
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * What plays next, and when the screen believes the player.
 *
 * This is the queue's lifecycle: starting an item on the service, reacting to the player's
 * events, ending a track (repeat, autoplay, end of queue) and the transport commands that drive
 * all of it. It owns no UI state of its own — writes go through [effects], and the position
 * claims go through [clock] — which is why the seek/resume rules and the event `when` can be read
 * without scrolling past library paging.
 */
internal class PlaybackDriver(
    private val store: UiStateStore,
    private val clock: PlaybackClock,
    private val effects: PlaybackEffects,
    private val notifier: Notifier,
    private val queue: QueueRepository,
    private val session: ServiceRuntime?,
    private val catalog: MediaCatalog,
    private val mediaKeys: MediaKeys,
    private val ensureTrackSaved: (String) -> Unit,
    private val scope: CoroutineScope
) {

    private val service: ServiceId get() = session?.service ?: ServiceId.Local

    private var autoplayJob: Job? = null

    private fun playbackNotice(error: Exception): String = serviceMessage(error, session?.isOnline != false)

    fun play(items: List<MediaItem>, index: Int) {
        if (index !in items.indices) return
        val item = items[index]
        if (!readyToPlay(item)) return
        if (store.value.current?.id == item.id) {
            if (!store.value.isPlaying) togglePlayPause()
            store.update { it.copy(notice = null) }
            return
        }
        beginPlayback(queue.play(items, index), item)
    }

    fun readyToPlay(item: MediaItem): Boolean {
        if (!item.playable) {
            notifier.say("Not available on this account")
            return false
        }
        return true
    }

    fun beginPlayback(snapshot: QueueRepository.Snapshot, item: MediaItem) {
        if (item is Track) ensureTrackSaved(item.id)
        effects.recorded(item)
        val resume = effects.itemStartPosition(item)
        effects.applyQueue(snapshot, positionMs = resume, playing = true)
        store.update { it.copy(notice = null) }
        effects.showNowPlayingNotice(item)
        startRemote(item, resume, resuming = resume > 0L)
    }

    fun togglePlayPause() {
        val s = store.value
        val item = s.current ?: return
        val play = !s.isPlaying
        // A paused seek has been delivered by now; let the next event realign the bar again.
        clock.clearSeek()
        effects.recordEpisodeProgress(force = true)
        clock.anchor(clock.value)
        store.update { it.copy(isPlaying = play) }
        if (session == null || item.service == ServiceId.Local) return
        scope.launch {
            try {
                if (play && session.playback.current == null) {
                    // A restored queue outlives the native player. Start the current item instead
                    // of resuming a source that no longer exists, from the offset it stopped at.
                    val resume = effects.savedPositionFor(item)
                    startRemote(item, resume, resuming = resume > 0L)
                } else if (play) {
                    if (!session.resume()) {
                        store.update { it.copy(isPlaying = false) }
                        return@launch
                    }
                } else {
                    session.pause()
                }
                session.playback.updateState(play, clock.value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                store.update { it.copy(isPlaying = false).say(playbackNotice(e)) }
            }
        }
    }

    fun next() {
        val before = store.value.current
        val snapshot = queue.next(auto = false)
        if (snapshot.current !== before) {
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            next?.let(effects::recorded)
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, resuming = resume > 0L) }
        }
    }

    fun previous() {
        val s = store.value
        if (s.current == null) return
        // A podcast's Previous means the previous episode. Rewinding belongs to the ±30 s
        // controls, so the track rule (restart after three seconds) does not apply here.
        if (s.current is Episode) {
            val snapshot = queue.previous(0L)
            if (snapshot.current !== s.current) {
                val previous = snapshot.current
                val resume = previous?.let(effects::itemStartPosition) ?: 0L
                previous?.let(effects::recorded)
                effects.applyQueue(snapshot, positionMs = resume, playing = previous?.playable == true)
                previous?.let { startRemote(it, resume, resuming = resume > 0L) }
            } else {
                seekTo(0L)
            }
            return
        }
        if (clock.value > 3_000L) {
            clock.set(0L)
            if (s.current?.service != ServiceId.Local) {
                session?.let { session ->
                    scope.launch { runCatching { session.seek(0L) } }
                }
            }
            return
        }
        val snapshot = queue.previous(clock.value)
        if (snapshot.current !== s.current) {
            val previous = snapshot.current
            val resume = previous?.let(effects::itemStartPosition) ?: 0L
            previous?.let(effects::recorded)
            effects.applyQueue(snapshot, positionMs = resume, playing = previous?.playable == true)
            previous?.let { startRemote(it, resume, resuming = resume > 0L) }
        } else {
            clock.set(0L)
            if (s.current?.service != ServiceId.Local) {
                session?.let { session ->
                    scope.launch { runCatching { session.seek(0L) } }
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val current = store.value.current ?: return
        val target = positionMs.coerceIn(0L, current.durationMs)
        val now = SystemClock.elapsedRealtime()
        SidetrackDebug.log("viewModel.seekTo target=$target current=${clock.value}")
        clock.beginSeek(target)
        effects.recordEpisodeProgress(force = true)
        if (session != null && current.service != ServiceId.Local) {
            scope.launch { runCatching { session.seek(target) } }
        }
    }

    fun jumpTo(index: Int) {
        val s = store.value
        val snapshot = queue.jumpTo(index)
        if (snapshot.current !== s.current) {
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            next?.let(effects::recorded)
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.takeIf { it.playable }?.let(effects::showNowPlayingNotice)
            next?.let { startRemote(it, resume, resuming = resume > 0L) }
        }
    }

    fun matchesCurrent(uri: String?): Boolean {
        if (uri.isNullOrEmpty()) return true
        // HTTP services have no stable MediaItem.uri; their event URI is the resolved stream URL.
        val current = store.value.current?.uri ?: session?.playback?.currentSource?.uri
        return current?.equals(uri, ignoreCase = true) ?: true
    }

    fun onPlaybackEnded() {
        val snapshot = store.value
        val current = snapshot.current ?: return
        effects.markEpisodeFinished(current)
        // The sleep timer wins over repeat and autoplay: this is where the queue must stop.
        if (session?.playback?.consumeEndOfTrackTimer() == true) {
            store.update { it.copy(isPlaying = false) }
            return
        }
        if (endOfEpisode(current)) return
        if (snapshot.repeat == RepeatMode.Track) {
            clock.set(0L)
            store.update { it.copy(isPlaying = true) }
            startRemote(current, 0L)
        } else {
            advance(auto = true, forceContextLoop = snapshot.repeat == RepeatMode.Context)
        }
    }

    /**
     * Ends a finished episode. Episodes carry no shuffle or repeat control, so a mode left over
     * from a song must not rewind the show or loop the episode forever: with nothing queued behind
     * it or added to the queue, the queue stops.
     */
    fun endOfEpisode(current: MediaItem): Boolean {
        if (current !is Episode) return false
        advance(auto = true, forceContextLoop = false)
        return true
    }

    fun tick() {
        val s = store.value
        val current = s.current ?: return
        if (!s.isPlaying) return
        val nextPosition = clock.value + 1_000L
        if (nextPosition < current.durationMs) {
            clock.set(nextPosition)
            effects.recordEpisodeProgress()
        } else if (!endOfEpisode(current)) {
            when (s.repeat) {
                RepeatMode.Track -> clock.set(0L)
                RepeatMode.Context -> advance(auto = true, forceContextLoop = true)
                RepeatMode.Off -> advance(auto = true, forceContextLoop = false)
            }
        }
    }

    fun advance(auto: Boolean, forceContextLoop: Boolean) {
        val s = store.value
        val current = s.current ?: return
        if (auto) effects.markEpisodeFinished(current)
        val hasNext = s.upcoming.isNotEmpty()
        if (!hasNext && forceContextLoop) {
            val snapshot = queue.next(auto = true)
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            next?.let(effects::recorded)
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, gapless = auto && store.value.gapless, resuming = resume > 0L) }
            return
        }
        if (!hasNext && !auto) return
        if (!hasNext) {
            if (store.value.autoplay) autoplaySimilar(current) else effects.stopAtQueueEnd()
            return
        }
        val snapshot = queue.next(auto = true)
        if (snapshot.current !== current) {
            val next = snapshot.current
            val resume = next?.let(effects::itemStartPosition) ?: 0L
            next?.let(effects::recorded)
            effects.applyQueue(snapshot, positionMs = resume, playing = next?.playable == true)
            next?.let { startRemote(it, resume, gapless = auto && store.value.gapless, resuming = resume > 0L) }
        } else {
            clock.set(0L)
            store.update { it.copy(isPlaying = false) }
        }
    }

    /**
     * The queue ran out with Autoplay on. Ask the catalog for the current artist's top tracks,
     * drop anything already in the queue or history, and let the normal advance path start the
     * first one. The guard keeps a slow request from hijacking a track the user started meanwhile.
     */
    fun autoplaySimilar(current: MediaItem) {
        if (autoplayJob?.isActive == true) return
        if (session?.isOnline == false) {
            effects.stopAtQueueEnd("No internet connection — autoplay stopped")
            return
        }
        val track = current as? Track
        val artistId = track?.artistId?.mediaIdOrNull()
        if (track == null || artistId == null) {
            effects.stopAtQueueEnd()
            return
        }
        autoplayJob = scope.launch {
            try {
                val artist = Artist(artistId, track.artist, 0, emptyList(), service = service)
                val page = catalog.topTracks(artist.key, 0, CATALOG_PAGE_SIZE)
                if (store.value.current?.id != track.id || !store.value.autoplay) return@launch
                val known = buildSet {
                    add(track.id)
                    addAll(store.value.history.map { it.id })
                    addAll(store.value.upcoming.map { it.id })
                }
                val similar = page.items.filter { it.id !in known && it.playable }
                if (similar.isEmpty()) {
                    effects.stopAtQueueEnd()
                    return@launch
                }
                val tail = if (store.value.shuffle) similar.shuffled() else similar
                effects.applyQueue(queue.addToQueue(tail), syncNowPlaying = false)
                advance(auto = true, forceContextLoop = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (store.value.current?.id == track.id) {
                    val reason = when {
                        session?.isOnline == false -> "No internet connection"
                        (e as? ServiceException)?.failure == ServiceFailure.Network -> "Couldn't reach the service"
                        else -> playbackNotice(e)
                    }
                    effects.stopAtQueueEnd("$reason — autoplay stopped")
                }
            }
        }
    }

    /**
     * The audio is running at [positionMs] once the start call returns, so let the seek bar run
     * from that offset instead of waiting for a player event. A reconnecting player can replay the
     * previous generation's state — typically position 0 — which left the bar stuck, then watched
     * it snap back to the start while the transport said it was playing.
     */
    fun releaseResumeHold(item: MediaItem, positionMs: Long) {
        clock.confirmStart(positionMs)
        store.update { state ->
            if (state.current?.id == item.id) state.copy(isPlaying = true) else state
        }
    }

    fun playItem(item: MediaItem) = play(listOf(item), 0)

    /**
     * Plays one track picked from a list row (Library, Search) without discarding the queue.
     *
     * A row tap is a request to hear that track now, not a request to throw away the tail the
     * user built with Add to queue, so unlike [play] this keeps Up next exactly as it was and
     * only replaces the current item.
     */
    fun playNow(item: MediaItem) {
        if (!readyToPlay(item)) return
        if (store.value.current?.id == item.id) {
            if (!store.value.isPlaying) togglePlayPause()
            store.update { it.copy(notice = null) }
            return
        }
        beginPlayback(queue.playNow(item), item)
    }

    fun playEpisode(episode: Episode) = playItem(episode)

    /** Play a recent item in context, so the rest of the history follows it in the queue. */
    fun playRecent(item: MediaItem) {
        val recent = store.value.recent
        val at = recent.indexOfFirst { it.id == item.id }
        if (at >= 0) play(recent, at) else playItem(item)
    }

    /** Podcast rewind. [seekTo] clamps to the item's duration and never goes below zero. */
    fun skipBack(milliseconds: Long = PODCAST_SKIP_MS) = seekTo(clock.value - milliseconds)

    /** Podcast fast-forward. [seekTo] clamps to the item's duration. */
    fun skipForward(milliseconds: Long = PODCAST_SKIP_MS) = seekTo(clock.value + milliseconds)

    /** Adopts the process-wide player and queue after the Sidetrack screen was recreated. */
    fun restore() {
        val session = session ?: return
        val playback = session.playback
        val snapshot = queue.snapshot
        val current = playback.current ?: snapshot.current
        if (current != null && snapshot.current?.id != current.id) {
            queue.restore(snapshot.copy(current = current))
        }
        val restored = queue.snapshot
        // A closed app leaves the item at the offset it reached; a live player owns its position.
        val resume = current?.let(effects::savedPositionFor) ?: 0L
        val position = if (playback.current != null) playback.position else resume
        clock.anchor(position)
        clock.set(position)
        store.update {
            it.copy(
                current = current,
                upcoming = restored.upcoming,
                history = restored.history,
                shuffle = restored.shuffle,
                repeat = restored.repeat,
                isPlaying = playback.isActive,
                recent = effects.recentItems()
            )
        }
    }

    /**
     * librespot is the source of truth while it is playing: its position events keep the seek bar
     * honest through buffering and seeks, and EndOfTrack advances the Sidekit queue exactly once.
     */
    fun handlePlaybackEvent(event: PlaybackEvent) {
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
            "event type=${event::class.simpleName} uri=${event.uri.orEmpty()} positionMs=$position"
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
                if (!clock.awaitingStartConfirmation) onPlaybackEnded()
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
                effects.applyQueue(if (halting) queue.snapshot else queue.stop(), positionMs = 0L, playing = false)
            }

            is PlaybackEvent.Unavailable -> if (matchesCurrent(event.uri)) {
                clock.endStart()
                clock.clearSeek()
                store.update { it.copy(isPlaying = false).say("This track isn't available") }
            }

            is PlaybackEvent.Unknown -> Unit
        }
    }

    /** Starts a real service item; local test fixtures have no URI and keep the local simulation. */
    fun startRemote(
        item: MediaItem,
        positionMs: Long,
        gapless: Boolean = false,
        resuming: Boolean = false
    ) {
        val session = session ?: return
        if (item.service == ServiceId.Local) return
        SidetrackDebug.log("viewModel.startRemote key=${item.key.encode()} positionMs=$positionMs gapless=$gapless resuming=$resuming")
        clock.beginStart(positionMs, resumeFor = item.id.takeIf { resuming })
        session.playback.setNowPlaying(item, positionMs)
        scope.launch {
            try {
                session.play(item, positionMs, gapless)
                SidetrackDebug.log("viewModel.startRemote returned")
                if (resuming) releaseResumeHold(item, positionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SidetrackDebug.log("viewModel.startRemote failed", e)
                clock.endStart()
                store.update {
                    it.copy(isPlaying = false, nowPlayingNotice = null).say(playbackNotice(e))
                }
            }
        }
    }
}
