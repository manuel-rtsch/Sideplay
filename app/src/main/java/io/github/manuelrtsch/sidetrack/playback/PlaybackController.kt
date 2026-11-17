package io.github.manuelrtsch.sidetrack.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState as SessionState
import android.os.SystemClock
import hrln.interactive.sidekit.sdk.media.RemoteArtwork
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.SidetrackDebug
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-scoped playback owner. The activity and screens may come and go; this object holds the
 * platform media session, the foreground notification, audio focus and the sleep timer.
 *
 * The audio pipeline and player commands live behind [PlaybackEngine]. This controller is
 * engine-agnostic: librespot and the future Media3 engine both plug in here.
 *
 * Main-thread only, like [MediaSessionOwner]. Long-running engine work is moved onto [scope].
 */
class PlaybackController(
    val engine: PlaybackEngine,
    private val scope: CoroutineScope,
    private val registry: MediaSessionRegistry = MediaSessions,
    private val context: Context? = null,
    /** Monotonic clock seam: the sleep timer's deadline math is the only consumer. */
    private val elapsedTime: () -> Long = { SystemClock.elapsedRealtime() }
) : MediaSessionOwner {
    override val sessionId = "sidetrack"
    override val notificationChannelId = PlaybackService.CHANNEL_ID
    override val notificationChannelName = PlaybackService.CHANNEL_NAME
    override val notificationId = PlaybackService.NOTIFICATION_ID

    @Volatile
    private var playing = false

    @Volatile
    private var positionMs = 0L

    @Volatile
    var current: MediaItem? = null
        private set

    /**
     * The resolved source for [current]. HTTP services keep their stream URL and auth headers
     * here; a service's source is just its URI. [reconfigure] reloads this, not the item's URI.
     */
    @Volatile
    var currentSource: PlaybackSource? = null
        private set

    val position: Long get() = positionMs

    private val _notice = MutableStateFlow<String?>(null)

    /**
     * Transient message the UI should surface about playback upkeep ("Reconnecting…" after a
     * stall). Latest wins; there is nothing to queue.
     */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _buffering = MutableStateFlow(false)

    /** True while the engine has no audio for the current position. */
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private val _sleepTimer = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)

    /**
     * The sleep timer lives on the process-wide player, not the ViewModel, so backing out of
     * Sidetrack cannot silently cancel it.
     */
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private var sleepTimerJob: Job? = null

    @Volatile
    var notificationArt: Bitmap? = null
        private set

    override val isActive: Boolean get() = playing

    /**
     * Queue ops normally live in the ViewModel so its screen state stays in sync. When the
     * ViewModel is gone, [fallbackNext]/[fallbackPrevious] keep the notification and lock-screen
     * transport working by advancing the process-wide queue instead.
     *
     * librespot 0.8 has no shuffle/repeat setters. Sidetrack's queue repository owns those, and
     * native shuffle/repeat events are adopted by the ViewModel when another client changes them.
     */
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    /**
     * A play command arrived with nothing loaded, so there is no source to resume. The owner has
     * to start the current item instead; the player cannot invent one.
     */
    var onResumeWithoutSource: (() -> Unit)? = null
    var fallbackNext: (() -> Unit)? = null
    var fallbackPrevious: (() -> Unit)? = null

    /** [onResumeWithoutSource] for a process with no ViewModel attached. */
    var fallbackResume: (() -> Unit)? = null

    /**
     * Recovery for an audio pipeline that went silent, installed by the session that owns the
     * native player. The engine cannot restart a dead librespot session; only the gateway owner
     * can reconnect and reload.
     */
    var onStalled: ((Long) -> Unit)? = null

    /** Lets the process owner clear queue state whenever playback is stopped from any control. */
    var onStopped: (() -> Unit)? = null

    private var mediaSession: MediaSession? = null
    private var artJob: Job? = null

    private var lastSessionUpdate = 0L
    private var noisyRegistered = false

    /**
     * The last play/pause intent. librespot events can arrive out of order after rapid taps;
     * this lets the UI ignore an event that belongs to a superseded command.
     */
    @Volatile
    private var lastCommandWasPause = false

    @Volatile
    private var lastCommandAt = 0L

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onAudioBecomingNoisy()
    }

    /** Headphones unplugged: pause rather than play out loud. */
    fun onAudioBecomingNoisy() = pause()

    private val focus = context?.let {
        AudioFocus(
            it,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
            onPause = { pauseInternal(abandonFocus = false) },
            onResume = { scope.launch { runCatching { resume() } } }
        )
    }

    val sessionToken: MediaSession.Token? get() = session()?.sessionToken

    init {
        engine.onFailure = ::onAudioStalled
        scope.launch {
            engine.state.collect { state -> _buffering.value = state.isBuffering }
        }
        // A minute tick only needs the notification text rebuilt, not the media session
        // republished. The service refresh path deliberately does not call publish().
        if (context != null) {
            scope.launch {
                _sleepTimer.collect { PlaybackService.refresh(context, this@PlaybackController) }
            }
        }
    }

    /** Registers once so starting local Music can pause this owner even before the first track. */
    fun register() = registry.register(this)

    fun emitNotice(text: String?) {
        _notice.value = text
    }

    /**
     * Called by the engine on its drain thread when no PCM arrived for the stall budget.
     * Hops to the main thread: the player's state and the platform media session are
     * main-thread only.
     */
    internal fun onAudioStalled(error: Throwable) {
        SidetrackDebug.log("audio stalled", error)
        scope.launch { handleAudioStalled() }
    }

    /**
     * A drain that produced no audio is indistinguishable from playback in the media session, so
     * the session has to be marked paused and handed back for a reconnect. Without this the app
     * shows a running player and delivers silence until the user presses play again.
     */
    private fun handleAudioStalled() {
        if (!playing) return
        playing = false
        publish()
        emitNotice("Reconnecting…")
        val recover = onStalled
        if (recover != null) {
            recover(positionMs)
        } else {
            scope.launch { runCatching { engine.stop() } }
        }
    }

    /** Replaces any running timer with a fresh countdown. */
    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            clearSleepTimer()
            return
        }
        val now = elapsedTime()
        val deadline = now + minutes * SLEEP_TIMER_MINUTE_MS
        installSleepTimer(
            SleepTimerState.Countdown(
                minutes = minutes,
                deadlineElapsedMs = deadline,
                remainingMinutes = sleepTimerRemainingMinutes(deadline, now)
            )
        )
    }

    /** Stops when the current track ends instead of advancing the queue. */
    fun setSleepTimerEndOfTrack() = installSleepTimer(SleepTimerState.EndOfTrack)

    fun clearSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimerState.Off
    }

    /**
     * Called by the ViewModel when librespot reports the end of a track. Returns true once when
     * the end-of-track mode consumed the event, so normal repeat/advance must not run.
     */
    fun consumeEndOfTrackTimer(): Boolean {
        if (_sleepTimer.value != SleepTimerState.EndOfTrack) return false
        clearSleepTimer()
        return true
    }

    private fun installSleepTimer(state: SleepTimerState) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = state
        if (state !is SleepTimerState.Countdown) return
        sleepTimerJob = scope.launch {
            while (true) {
                val current = _sleepTimer.value as? SleepTimerState.Countdown ?: return@launch
                val now = elapsedTime()
                if (now >= current.deadlineElapsedMs) {
                    expireSleepTimer()
                    return@launch
                }
                val remaining = sleepTimerRemainingMinutes(current.deadlineElapsedMs, now)
                if (remaining != current.remainingMinutes) {
                    _sleepTimer.value = current.copy(remainingMinutes = remaining)
                }
                // Wake exactly when the whole-minute value changes; no per-second ticking.
                val nextChange = current.deadlineElapsedMs - (remaining - 1) * SLEEP_TIMER_MINUTE_MS
                delay((nextChange - now).coerceAtLeast(SLEEP_TIMER_MIN_DELAY_MS))
            }
        }
    }

    private fun expireSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimerState.Off
        // Pause never touches system volume, and an already-paused player needs no pause call.
        if (playing) pause()
    }

    fun setNowPlaying(
        item: MediaItem?,
        position: Long = 0L,
        source: PlaybackSource? = null
    ) {
        current = item
        currentSource = source ?: item?.uri?.let { PlaybackSource(item.key.encode(), it) }
        positionMs = position
        engine.updatePosition(position)
        artJob?.cancel()
        notificationArt = null
        if (context != null) updateMetadata()
        publish()
        if (item?.artUrl != null && context != null) {
            artJob = scope.launch {
                val bitmap = RemoteArtwork.load(context, item.artUrl!!, ART_PX, ART_PX)
                if (current?.id == item.id) {
                    notificationArt = bitmap
                    lastSessionUpdate = 0L
                    updateMetadata()
                    publish()
                }
            }
        }
    }

    /** Mirrors native player events into the media session and notification. */
    fun updateState(isPlaying: Boolean, position: Long) {
        playing = isPlaying
        positionMs = position
        engine.updateState(isPlaying, position)
        publish()
    }

    /** Keeps the playback owner's position in step with the UI clock without publishing. */
    fun updatePosition(position: Long) {
        positionMs = position
        engine.updatePosition(position)
    }

    /**
     * Whether a native playing/paused event still matches the user's latest intent. Events that
     * arrive within the command window but contradict the last command are stale. After the
     * window, events from other clients are accepted again.
     */
    fun shouldApplyEvent(isPlaying: Boolean): Boolean {
        val age = SystemClock.elapsedRealtime() - lastCommandAt
        if (age > COMMAND_EVENT_WINDOW_MS) return true
        return if (isPlaying) !lastCommandWasPause else lastCommandWasPause
    }

    /**
     * Starts a new item. [keepAudioSink] is set for a gapless automatic transition: librespot has
     * already pre-buffered the track and its sink is still open, so tearing down the AudioTrack
     * here would introduce the very gap the setting is meant to remove.
     */
    suspend fun start(
        key: MediaKey,
        uri: String,
        positionMs: Long,
        keepAudioSink: Boolean = false,
        headers: Map<String, String> = emptyMap()
    ) {
        SidetrackDebug.log("start key=${key.encode()} uri=$uri positionMs=$positionMs keepAudioSink=$keepAudioSink")
        val source = PlaybackSource(id = key.encode(), uri = uri, headers = headers)
        currentSource = source
        lastCommandWasPause = false
        lastCommandAt = SystemClock.elapsedRealtime()
        register()
        if (focus?.request() == false) {
            SidetrackDebug.log("start aborted: audio focus denied")
            return
        }
        registry.activate(this)
        playing = true
        this.positionMs = positionMs
        publish()
        try {
            engine.start(source, positionMs, keepAudioSink)
        } catch (t: Throwable) {
            SidetrackDebug.log("start failed", t)
            playing = false
            focus?.abandon()
            registry.deactivate(this)
            publish()
            throw t
        }
    }

    suspend fun resume(): Boolean {
        SidetrackDebug.log("resume")
        if (current == null) {
            // A cold process restores the queue but not the native player, so a play command from
            // a headset or the notification would resume a player that has no source at all and
            // deliver silence until the sink's stall watchdog noticed. Starting the item is the
            // queue owner's job; this only reports whether anyone could do it.
            val start = onResumeWithoutSource ?: fallbackResume
            SidetrackDebug.log("resume without source restarted=${start != null}")
            start?.invoke()
            return start != null
        }
        lastCommandWasPause = false
        lastCommandAt = SystemClock.elapsedRealtime()
        register()
        if (focus?.request() == false) {
            SidetrackDebug.log("resume aborted: audio focus denied")
            return false
        }
        registry.activate(this)
        playing = true
        publish()
        val started = engine.resume(positionMs)
        if (!started) {
            playing = false
            publish()
            return false
        }
        return true
    }

    fun togglePlayPause() {
        if (playing) pause() else scope.launch { runCatching { resume() } }
    }

    override fun pause() = pauseInternal(abandonFocus = true)

    /**
     * Transient audio-focus loss keeps the focus request held so [AudioFocus] can resume us when
     * the call or notification ends. An explicit pause gives it back.
     */
    private fun pauseInternal(abandonFocus: Boolean) {
        SidetrackDebug.log("pauseInternal abandonFocus=$abandonFocus")
        // The UI clock owns the position; the decoder's `paused` event is ahead by the PCM
        // buffer and must not be used as the resume point.
        playing = false
        _buffering.value = false
        lastCommandWasPause = true
        lastCommandAt = SystemClock.elapsedRealtime()
        if (abandonFocus) focus?.abandon()
        registry.deactivate(this)
        publish()
        engine.invalidateCommands()
        scope.launch { runCatching { engine.pause() } }
    }

    suspend fun seek(positionMs: Long) {
        this.positionMs = positionMs
        publish()
        engine.seek(positionMs)
    }

    fun requestSeek(positionMs: Long) {
        val callback = onSeek
        if (callback != null) callback(positionMs) else scope.launch { runCatching { seek(positionMs) } }
    }

    fun requestNext() = (onNext ?: fallbackNext)?.invoke()

    fun requestPrevious() = (onPrevious ?: fallbackPrevious)?.invoke()

    /**
     * Rebuilds the player after an audio-setting change. The current item is reloaded at the
     * same position and the pause/play state is preserved.
     */
    suspend fun reconfigure() {
        val item = current
        val source = currentSource
            ?: item?.uri?.let { PlaybackSource(id = item.key.encode(), uri = it) }
        val position = positionMs
        val wasPlaying = playing
        SidetrackDebug.log("reconfigure uri=${source?.uri} positionMs=$position wasPlaying=$wasPlaying")
        val rebuilt = engine.reconfigure(source, position, wasPlaying)
        if (!rebuilt) return
        playing = wasPlaying
        publish()
    }

    /**
     * Ends playback. [keepQueue] leaves the queue, the history and the selected track alone: the
     * app closing has to silence the audio, but the next launch should still find what was playing.
     */
    suspend fun stop(keepQueue: Boolean = false) {
        clearSleepTimer()
        playing = false
        _buffering.value = false
        lastCommandWasPause = true
        lastCommandAt = SystemClock.elapsedRealtime()
        focus?.abandon()
        registry.deactivate(this)
        engine.invalidateCommands()
        engine.stop()
        mediaSession?.let {
            it.setPlaybackState(
                SessionState.Builder()
                    .setState(SessionState.STATE_STOPPED, 0L, 0f, SystemClock.elapsedRealtime())
                    .build()
            )
        }
        current = null
        currentSource = null
        notificationArt = null
        artJob?.cancel()
        if (!keepQueue) onStopped?.invoke()
        publish()
    }

    suspend fun disconnect() {
        stop()
        engine.disconnect()
        mediaSession?.release()
        mediaSession = null
    }

    /** One engine event, or null when nothing has happened since the last poll. */
    suspend fun pollEvent(): PlaybackEvent? = engine.pollEvent()

    private fun publish() {
        SidetrackDebug.log { "publish playing=$playing positionMs=$positionMs current=${current?.uri}" }
        session()?.let { session ->
            val now = SystemClock.elapsedRealtime()
            if (current != null) updateMetadata(session, now)
            session.setPlaybackState(
                SessionState.Builder()
                    .setActions(
                        SessionState.ACTION_PLAY or SessionState.ACTION_PAUSE or
                            SessionState.ACTION_PLAY_PAUSE or SessionState.ACTION_SKIP_TO_NEXT or
                            SessionState.ACTION_SKIP_TO_PREVIOUS or SessionState.ACTION_SEEK_TO or
                            SessionState.ACTION_STOP
                    )
                    .setState(
                        when {
                            current == null -> SessionState.STATE_STOPPED
                            playing -> SessionState.STATE_PLAYING
                            else -> SessionState.STATE_PAUSED
                        },
                        positionMs,
                        if (playing) 1f else 0f,
                        now
                    )
                    .build()
            )
        }
        updateNoisy()
        context?.let { PlaybackService.sync(it, this) }
    }

    private fun updateNoisy() {
        val context = context ?: return
        if (playing == noisyRegistered) return
        noisyRegistered = playing
        if (playing) {
            context.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        } else {
            runCatching { context.unregisterReceiver(noisy) }
        }
    }

    private fun updateMetadata(session: MediaSession? = session(), now: Long = SystemClock.elapsedRealtime()) {
        val item = current ?: return
        session ?: return
        if (lastSessionUpdate != 0L && now - lastSessionUpdate < 250L) return
        lastSessionUpdate = now
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, item.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, item.creator)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, item.creator)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, item.durationMs)
                .apply { notificationArt?.let { putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
    }

    private fun session(): MediaSession? {
        val context = context ?: return null
        return mediaSession ?: MediaSession(context, "SideKit Sidetrack").also { session ->
            session.setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        scope.launch { runCatching { resume() } }
                    }

                    override fun onPause() {
                        pause()
                    }

                    override fun onSkipToNext() {
                        requestNext()
                    }

                    override fun onSkipToPrevious() {
                        requestPrevious()
                    }

                    override fun onSeekTo(pos: Long) {
                        requestSeek(pos)
                    }

                    override fun onStop() {
                        scope.launch { runCatching { stop() } }
                    }
                }
            )
            session.isActive = true
            mediaSession = session
        }
    }

    private companion object {
        const val ART_PX = 256

        /** How long after a command a contradicting native event is treated as stale. */
        const val COMMAND_EVENT_WINDOW_MS = 2_000L

        const val SLEEP_TIMER_MINUTE_MS = 60_000L

        /** Guards against a zero-delay spin when the clock lands exactly on a minute edge. */
        const val SLEEP_TIMER_MIN_DELAY_MS = 1_000L
    }
}
