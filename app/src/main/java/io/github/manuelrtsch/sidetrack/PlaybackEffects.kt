package io.github.manuelrtsch.sidetrack

import android.os.SystemClock
import io.github.manuelrtsch.sidetrack.data.EpisodeMetaRepository
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The writes that follow a queue change: what the screen shows, what the history remembers, and
 * what the podcast resume points record.
 *
 * Every play path ends here, whether it came from a row tap, a transport button, an autoplay fill
 * or a player event. Keeping these effects in one place is what makes them consistent: the
 * episode offset is saved on the same rule no matter which path left the item.
 */
internal class PlaybackEffects(
    private val store: UiStateStore,
    private val queue: QueueRepository,
    private val history: HistoryRepository,
    private val episodes: EpisodeProgressRepository,
    private val episodeMeta: EpisodeMetaRepository,
    private val clock: PlaybackClock,
    private val playback: () -> PlaybackController?,
    private val resumePoint: () -> ResumePoint?,
    private val scope: CoroutineScope
) {

    private var lastEpisodeProgressAt = 0L
    private var nowPlayingNoticeJob: Job? = null

    fun applyQueue(
        snapshot: QueueRepository.Snapshot,
        positionMs: Long? = null,
        playing: Boolean? = null,
        syncNowPlaying: Boolean = true,
        recordPrevious: Boolean = true
    ) {
        // Leaving an episode is the last chance to save where it stopped, before the new item
        // resets the seek bar to its own offset.
        val previous = store.value.current
        // Recently played lists what the user moved on from, not what is playing now. The
        // outgoing item enters the history only when the queue switches to a different one, so
        // the first row cannot mirror the now-playing section.
        val left = previous?.takeIf { it.id != snapshot.current?.id }
        if (recordPrevious) left?.let { history.record(it, System.currentTimeMillis()) }
        // The item playing now belongs to Now Playing, not Recently played. Replaying one from
        // that list takes its old entry out until the user moves on to something else.
        val droppedCurrent = snapshot.current?.let { history.removeEntry(it) != null } ?: false
        val historyChanged = (recordPrevious && left != null) || droppedCurrent
        if (previous is Episode && (snapshot.current?.id != previous.id || positionMs != null)) {
            episodes.record(previous.id, clock.value, previous.durationMs)
        }
        // Notes are not part of the queue file; keep them next to the episode that is playing so a
        // restored session can still offer them without another request.
        (snapshot.current as? Episode)?.let(episodeMeta::remember)
        if (snapshot.current?.id != previous?.id) clock.clearSeek()
        if (positionMs != null) {
            clock.clearSeek()
            clock.anchor(positionMs)
            clock.set(positionMs)
        }
        store.update {
            it.copy(
                current = snapshot.current,
                upcoming = snapshot.upcoming,
                history = snapshot.history,
                shuffle = snapshot.shuffle,
                repeat = snapshot.repeat,
                isPlaying = playing ?: it.isPlaying,
                recent = if (historyChanged) history.items() else it.recent
            )
        }
        if (syncNowPlaying) {
            playback()?.setNowPlaying(snapshot.current, positionMs ?: clock.value)
        }
    }

    /** Cancels the now-playing confirmation, e.g. when the user leaves its screen. */
    fun dismissNowPlayingNotice() {
        nowPlayingNoticeJob?.cancel()
        store.update { it.copy(nowPlayingNotice = null) }
    }

    /**
     * The history rows the screen shows, newest first. The item playing now is not one of them;
     * [excluding] is passed explicitly while restoring, before the store holds the restored
     * item.
     */
    fun recentItems(excluding: MediaItem? = store.value.current): List<MediaItem> {
        if (excluding != null) history.remove(excluding)
        return history.items()
    }

    /** Episode offsets are saved every few seconds while playing, and immediately on pauses/seeks. */
    fun recordEpisodeProgress(force: Boolean = false) {
        val current = store.value.current as? Episode ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastEpisodeProgressAt < EPISODE_PROGRESS_INTERVAL_MS) return
        lastEpisodeProgressAt = now
        episodes.record(current.id, clock.value, current.durationMs)
    }

    /** The offset a restored item was stopped at. Tracks still use the single-session resume point. */
    fun savedPositionFor(item: MediaItem): Long = when (item) {
        is Episode -> episodes.resumePosition(item.id)
        else -> resumePoint()?.takeIf { it.key == item.key }?.positionMs ?: 0L
    }

    fun markEpisodeFinished(item: MediaItem) {
        if (item !is Episode) return
        episodes.record(item.id, item.durationMs, item.durationMs, completed = true)
    }

    /**
     * Shows the now-playing confirmation for a few seconds. The timer lives here rather than in
     * the action bar so the state is cleared even if the user navigates to a screen that does not
     * render the confirmation.
     */
    fun showNowPlayingNotice(item: MediaItem) {
        nowPlayingNoticeJob?.cancel()
        store.update { it.copy(nowPlayingNotice = item) }
        nowPlayingNoticeJob = scope.launch {
            delay(NOW_PLAYING_NOTICE_MS)
            store.update {
                if (it.nowPlayingNotice?.id == item.id) it.copy(nowPlayingNotice = null) else it
            }
        }
    }

    fun stopAtQueueEnd(notice: String? = null) {
        // The native player is parked at the end of the item. Left playing, the drain keeps
        // polling an empty source until the stall watchdog fires and reloads the finished track;
        // left at its duration, the next play ends again immediately. Pause it and rewind.
        playback()?.let { controller ->
            controller.updatePosition(0L)
            controller.pause()
            controller.requestSeek(0L)
        }
        clock.set(0L)
        store.update {
            val next = it.copy(isPlaying = false)
            if (notice == null) next else next.say(notice)
        }
    }

    /** The offset this item should start at when it becomes current. */
    fun itemStartPosition(item: MediaItem): Long = (item as? Episode)?.let { episodes.resumePosition(it.id) } ?: 0L
}
