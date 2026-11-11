package io.github.manuelrtsch.sideplay.playback

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
import io.github.manuelrtsch.sideplay.SideplayDebug
import io.github.manuelrtsch.sideplay.SpotifyMediaItem
import io.github.manuelrtsch.sideplay.data.SpotifyGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Process-scoped playback owner. The activity and screens may come and go; this object holds the
 * native player lifetime, the platform media session and the foreground notification.
 *
 * Main-thread only, like [MediaSessionOwner]. Long-running gateway work is moved onto [scope].
 */
class SpotifyPlayback(
    private val gateway: SpotifyGateway,
    private val scope: CoroutineScope,
    private val registry: MediaSessionRegistry = MediaSessions,
    private val audio: SpotifyAudioSink? = null,
    private val context: Context? = null
) : MediaSessionOwner {
    override val sessionId = "sideplay"
    override val notificationChannelId = "sideplay"
    override val notificationChannelName = "Sideplay playback"
    override val notificationId = 0x6D76

    @Volatile
    private var playing = false

    @Volatile
    private var positionMs = 0L

    @Volatile
    var current: SpotifyMediaItem? = null
        private set

    val position: Long get() = positionMs

    @Volatile
    var notificationArt: Bitmap? = null
        private set

    override val isActive: Boolean get() = playing

    /**
     * Queue ops normally live in the ViewModel so its screen state stays in sync. When the
     * ViewModel is gone, [fallbackNext]/[fallbackPrevious] keep the notification and lock-screen
     * transport working by advancing the process-wide queue instead.
     *
     * librespot 0.8 has no shuffle/repeat setters. Sideplay's queue repository owns those, and
     * native shuffle/repeat events are adopted by the ViewModel when another client changes them.
     */
    var onNext: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var fallbackNext: (() -> Unit)? = null
    var fallbackPrevious: (() -> Unit)? = null

    /** Lets the process owner clear queue state whenever playback is stopped from any control. */
    var onStopped: (() -> Unit)? = null

    private var mediaSession: MediaSession? = null
    private var artJob: Job? = null
    private var lastSessionUpdate = 0L
    private var noisyRegistered = false

    /**
     * Invalidates a pending command that is still waiting for the player to acknowledge it. A
     * newer play/pause/start/stop bumps this so an older coroutine cannot apply its result.
     */
    private var pauseGeneration = 0

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

    /** Registers once so starting local Music can pause this owner even before the first track. */
    fun register() = registry.register(this)

    fun setNowPlaying(item: SpotifyMediaItem?, position: Long = 0L) {
        current = item
        positionMs = position
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
        publish()
    }

    /** Keeps the playback owner's position in step with the UI clock without publishing. */
    fun updatePosition(position: Long) {
        positionMs = position
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

    suspend fun start(uri: String, positionMs: Long) {
        SideplayDebug.log("start uri=$uri positionMs=$positionMs audio=${audio != null}")
        val generation = ++pauseGeneration
        lastCommandWasPause = false
        lastCommandAt = SystemClock.elapsedRealtime()
        register()
        if (focus?.request() == false) {
            SideplayDebug.log("start aborted: audio focus denied")
            return
        }
        if (generation != pauseGeneration) return
        registry.activate(this)
        playing = true
        this.positionMs = positionMs
        // Release the previous AudioTrack and drain the native channel before loading the next
        // item. Draining also unblocks a player thread that may still be waiting in
        // PcmSink::write, so the load command can be processed.
        audio?.stop()
        if (generation != pauseGeneration) return
        publish()
        try {
            SideplayDebug.log("start calling gateway.load")
            gateway.load(uri, positionMs, autoplay = true)
            if (generation != pauseGeneration) return
            SideplayDebug.log("start gateway.load returned; starting audio sink")
            audio?.start(positionMs)
            SideplayDebug.log("start audio sink started")
        } catch (t: Throwable) {
            SideplayDebug.log("start failed", t)
            playing = false
            audio?.stop()
            focus?.abandon()
            registry.deactivate(this)
            publish()
            throw t
        }
    }

    suspend fun resume(): Boolean {
        SideplayDebug.log("resume audio=${audio != null}")
        val generation = ++pauseGeneration
        lastCommandWasPause = false
        lastCommandAt = SystemClock.elapsedRealtime()
        register()
        if (focus?.request() == false) {
            SideplayDebug.log("resume aborted: audio focus denied")
            return false
        }
        if (generation != pauseGeneration) return false
        registry.activate(this)
        playing = true
        val reused = audio?.resume(positionMs) ?: false
        if (generation != pauseGeneration) return false
        publish()
        if (!reused) {
            // The AudioTrack buffer was lost (for example after a long pause). Realign the
            // player with the UI position before playing, otherwise it resumes from its decoder
            // position and skips the buffered audio.
            SideplayDebug.log("resume audio buffer lost; seeking to $positionMs")
            runCatching { gateway.seek(positionMs) }
        }
        if (generation != pauseGeneration) return false
        SideplayDebug.log("resume calling gateway.play")
        gateway.play()
        SideplayDebug.log("resume gateway.play returned")
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
        SideplayDebug.log("pauseInternal abandonFocus=$abandonFocus")
        // The UI clock owns the position; the decoder's `paused` event is ahead by the PCM
        // buffer and must not be used as the resume point.
        playing = false
        lastCommandWasPause = true
        lastCommandAt = SystemClock.elapsedRealtime()
        if (abandonFocus) focus?.abandon()
        registry.deactivate(this)
        publish()
        val generation = ++pauseGeneration
        scope.launch {
            // Queue the native pause while the AudioTrack drain is still running. The drain is
            // the real-time clock and also unblocks a player thread waiting in PcmSink::write,
            // so the player can process the pause command. Stopping the drain first would wedge
            // that thread and leave the UI showing a stale playing state.
            if (generation != pauseGeneration) return@launch
            runCatching { gateway.pause() }
            delay(PAUSE_DRAIN_GRACE_MS)
            if (generation == pauseGeneration && !playing) audio?.pause()
        }
    }

    suspend fun seek(positionMs: Long) {
        this.positionMs = positionMs
        publish()
        gateway.seek(positionMs)
        audio?.seek(positionMs)
    }

    fun requestSeek(positionMs: Long) {
        val callback = onSeek
        if (callback != null) callback(positionMs) else scope.launch { runCatching { seek(positionMs) } }
    }

    fun requestNext() = (onNext ?: fallbackNext)?.invoke()

    fun requestPrevious() = (onPrevious ?: fallbackPrevious)?.invoke()

    suspend fun stop() {
        playing = false
        lastCommandWasPause = true
        lastCommandAt = SystemClock.elapsedRealtime()
        val generation = ++pauseGeneration
        focus?.abandon()
        registry.deactivate(this)
        // Same ordering as pause: let the player process stop while the drain is still alive.
        if (generation != pauseGeneration) return
        runCatching { gateway.stop() }
        delay(PAUSE_DRAIN_GRACE_MS)
        if (generation != pauseGeneration) return
        audio?.stop()
        mediaSession?.let {
            it.setPlaybackState(
                SessionState.Builder()
                    .setState(SessionState.STATE_STOPPED, 0L, 0f, SystemClock.elapsedRealtime())
                    .build()
            )
        }
        current = null
        notificationArt = null
        artJob?.cancel()
        onStopped?.invoke()
        publish()
    }

    suspend fun disconnect() {
        stop()
        gateway.disconnect()
        mediaSession?.release()
        mediaSession = null
    }

    private fun publish() {
        SideplayDebug.log("publish playing=$playing positionMs=$positionMs current=${current?.uri}")
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
        context?.let { SpotifyPlaybackService.sync(it, this) }
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
        return mediaSession ?: MediaSession(context, "SideKit Sideplay").also { session ->
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

        /**
         * How long the AudioTrack drain keeps running after a pause/stop is queued. One PCM
         * buffer is ~23 ms, so this is enough for a player thread blocked in PcmSink::write to
         * wake, process the command, and stop producing audio.
         */
        const val PAUSE_DRAIN_GRACE_MS = 60L

        /** How long after a command a contradicting native event is treated as stale. */
        const val COMMAND_EVENT_WINDOW_MS = 2_000L
    }
}
