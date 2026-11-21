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

    private val events = PlaybackEvents(
        clock = clock,
        session = session,
        queue = queue,
        effects = effects,
        store = store,
        matchesCurrent = ::matchesCurrent,
        onEnded = ::onPlaybackEnded
    )

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
            // Same stop as the queue end: the native player is already at the end, so leaving it
            // there makes the drain mistake the finished track for a stalled connection.
            effects.stopAtQueueEnd()
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

    /**
     * Adds the pages behind a collection that is already playing. The page on screen starts the
     * queue immediately and this only grows its tail, so a long playlist no longer spins before
     * its first note and one failing page cannot stop playback. A load that lands after the user
     * started something else is dropped by [QueueRepository.extendContext].
     */
    fun extendCollection(page: List<MediaItem>, complete: List<MediaItem>) {
        val before = queue.snapshot
        val after = queue.extendContext(page, complete)
        if (after != before) effects.applyQueue(after, syncNowPlaying = false)
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
                recent = effects.recentItems(excluding = current)
            )
        }
    }

    /**
     * librespot is the source of truth while it is playing: its position events keep the seek bar
     * honest through buffering and seeks, and EndOfTrack advances the Sidekit queue exactly once.
     */
    fun handlePlaybackEvent(event: PlaybackEvent) = events.handle(event)

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
