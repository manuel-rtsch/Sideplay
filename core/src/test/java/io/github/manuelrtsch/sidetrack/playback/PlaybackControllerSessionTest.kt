package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.MediaType
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf

/**
 * The controller's own contract, with a fake engine: audio focus, the platform media session, the
 * notification hooks, the sleep timer and the recovery/stop paths. The librespot engine is exercised
 * next to its code in `:provider-spotify`; here the engine is only a recording stand-in, so each
 * assertion is about the controller's decision rather than the native player's state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    @Test fun `start denied by audio focus leaves the engine untouched`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val engine = FakeEngine()

        newController(engine, context = context)
            .start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        assertFalse("a denied focus must not mark the session playing", controller.isActive)
        assertEquals(0, engine.starts)
    }

    @Test fun `start claims audio focus and publishes to the notification`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        var notifications = 0
        controller = newController(engine, context = context, notifySync = { _, _ -> notifications++ })

        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 12_000L)

        assertTrue(controller.isActive)
        assertSame(controller, registry.active)
        assertEquals(1, engine.starts)
        assertEquals("spotify:track:a", engine.lastSource?.uri)
        assertEquals(12_000L, engine.lastPosition)
        assertEquals(1, notifications)
        assertEquals("spotify:track:a", controller.currentSource?.uri)
    }

    @Test fun `a failed start releases focus and the registry`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine().apply { failStart = IllegalStateException("decode failed") }

        val error = runCatching {
            newController(engine, context = context)
                .start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(controller.isActive)
        assertNull(registry.active)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `pause gives audio focus back and invalidates in-flight commands`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        controller.pause()
        runCurrent()

        assertFalse(controller.isActive)
        assertEquals(1, engine.invalidations)
        assertEquals(1, engine.pauses)
        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `resume denied by audio focus reports false without playing`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 0L, PlaybackSource("a", "spotify:track:a"))

        assertFalse(controller.resume())

        assertEquals(0, engine.resumes)
        assertFalse(controller.isActive)
    }

    @Test fun `resume reports false when the engine cannot start audio`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine().apply { resumeResult = false }
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 0L, PlaybackSource("a", "spotify:track:a"))

        assertFalse(controller.resume())

        assertEquals(1, engine.resumes)
        assertFalse(controller.isActive)
    }

    @Test fun `resume claims the session and hands the position to the engine`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(), 42_000L, PlaybackSource("a", "spotify:track:a"))

        assertTrue(controller.resume())

        assertEquals(1, engine.resumes)
        assertEquals(42_000L, engine.lastPosition)
        assertTrue(controller.isActive)
        assertSame(controller, registry.active)
    }

    @Test fun `resume without a loaded source asks the owner instead of the engine`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        var restarts = 0

        controller.onResumeWithoutSource = { restarts++ }
        assertTrue(controller.resume())
        assertEquals(1, restarts)

        controller.onResumeWithoutSource = null
        controller.fallbackResume = { restarts++ }
        assertTrue(controller.resume())
        assertEquals(2, restarts)

        // A cold process with nobody left to start the item must report failure, not silence.
        controller.fallbackResume = null
        assertFalse(controller.resume())
        assertEquals(0, engine.resumes)
    }

    @Test fun `the engine's buffering state is mirrored for the seek row`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        runCurrent()

        engine.state.value = PlaybackEngineState(isBuffering = true)
        runCurrent()
        assertTrue(controller.buffering.value)

        engine.state.value = PlaybackEngineState(isBuffering = false)
        runCurrent()
        assertFalse(controller.buffering.value)
    }

    @Test fun `pollEvent and the ended flow come from the engine`() = runTest {
        val engine = FakeEngine().apply {
            event = PlaybackEvent.Position(1_234L)
            ended.value = "spotify:track:a"
        }
        controller = newController(engine)

        assertEquals(PlaybackEvent.Position(1_234L), controller.pollEvent())
        assertEquals("spotify:track:a", controller.ended.first())
    }

    @Test fun `toggle pauses a playing session and resumes a paused one`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        runCurrent()

        controller.togglePlayPause()
        runCurrent()
        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)

        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller.togglePlayPause()
        runCurrent()

        assertEquals(1, engine.resumes)
        assertTrue(controller.isActive)
    }

    @Test fun `seek is synchronous while requestSeek prefers the attached UI`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)

        controller.seek(1_000L)
        controller.requestSeek(2_000L)
        runCurrent()

        assertEquals(2_000L, engine.seekedTo)
        var requested: Long? = null
        controller.onSeek = { requested = it }
        controller.requestSeek(3_000L)

        assertEquals(3_000L, requested)
        assertEquals(2_000L, engine.seekedTo)
    }

    @Test fun `next and previous prefer the UI and fall back when it is gone`() = runTest {
        controller = newController(FakeEngine())
        var uiNext = 0
        var fallbackNext = 0
        var uiPrevious = 0
        var fallbackPrevious = 0
        controller.onNext = { uiNext++ }
        controller.fallbackNext = { fallbackNext++ }
        controller.onPrevious = { uiPrevious++ }
        controller.fallbackPrevious = { fallbackPrevious++ }

        controller.requestNext()
        controller.requestPrevious()

        assertEquals(1, uiNext)
        assertEquals(1, uiPrevious)
        assertEquals(0, fallbackNext)
        assertEquals(0, fallbackPrevious)

        controller.onNext = null
        controller.onPrevious = null
        controller.requestNext()
        controller.requestPrevious()

        assertEquals(1, fallbackNext)
        assertEquals(1, fallbackPrevious)
    }

    @Test fun `reconfigure reloads the source derived from the current item`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 12_000L)

        controller.reconfigure()

        assertEquals("spotify:track:a", engine.reconfiguredSource?.uri)
        assertEquals(12_000L, engine.reconfiguredPosition)
        assertFalse(engine.reconfiguredWhilePlaying)
        assertTrue(engine.reconfigureResult)
        assertFalse(controller.isActive)
    }

    @Test fun `a reconfigure that was not rebuilt leaves the session state alone`() = runTest {
        val engine = FakeEngine().apply { reconfigureResult = false }
        controller = newController(engine)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)

        controller.reconfigure()

        assertFalse(controller.isActive)
        assertFalse(engine.reconfiguredWhilePlaying)
    }

    @Test fun `stop clears the slot and reports it to the queue owner`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L, PlaybackSource("a", "spotify:track:a"))
        var stopped = 0
        controller.onStopped = { stopped++ }

        controller.stop()

        assertEquals(1, stopped)
        assertEquals(1, engine.stops)
        assertNull(controller.current)
        assertNull(controller.currentSource)
        assertFalse(controller.isActive)
    }

    @Test fun `stop can keep the queue for an app restart`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.setNowPlaying(track(), 0L)
        var stopped = 0
        controller.onStopped = { stopped++ }

        controller.stop(keepQueue = true)

        assertEquals(0, stopped)
        assertEquals(1, engine.stops)
    }

    @Test fun `disconnect stops playback and drops the engine session`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        assertNotNull(controller.sessionToken)

        controller.disconnect()

        assertEquals(1, engine.stops)
        assertEquals(1, engine.disconnects)
        assertFalse(controller.isActive)
    }

    @Test fun `a native event that contradicts the last command is ignored`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        controller = newController(FakeEngine(), context = context)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)

        assertTrue(controller.shouldApplyEvent(isPlaying = true))
        assertFalse(controller.shouldApplyEvent(isPlaying = false))

        controller.pause()

        assertTrue(controller.shouldApplyEvent(isPlaying = false))
        assertFalse(controller.shouldApplyEvent(isPlaying = true))
    }

    @Test fun `a stall asks the owner to recover and tells the UI`() = runTest {
        controller = newController(FakeEngine())
        controller.updateState(isPlaying = true, position = 5_000L)
        var recoveredAt: Long? = null
        controller.onStalled = { recoveredAt = it }

        controller.onAudioStalled(IllegalStateException("drain died"))
        runCurrent()

        assertEquals(5_000L, recoveredAt)
        assertFalse(controller.isActive)
        assertEquals("Reconnecting…", controller.notice.value)
    }

    @Test fun `a stall with no recovery path stops the engine`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        controller.updateState(isPlaying = true, position = 5_000L)

        controller.onAudioStalled(IllegalStateException("drain died"))
        runCurrent()

        assertEquals(1, engine.stops)
        assertEquals("Reconnecting…", controller.notice.value)
    }

    @Test fun `a stall while already paused changes nothing`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)
        var stopped = 0
        controller.onStalled = { stopped++ }

        controller.onAudioStalled(IllegalStateException("late failure"))
        runCurrent()

        assertEquals(0, stopped)
        assertEquals(0, engine.stops)
        assertNull(controller.notice.value)
    }

    @Test fun `the noisy broadcast pauses playback`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        runCurrent()

        context.sendBroadcast(android.content.Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)
    }

    @Test fun `a transient focus loss pauses and returning focus resumes`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context)
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        runCurrent()
        val listener = focusListener()

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        runCurrent()
        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)

        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        runCurrent()

        assertTrue(controller.isActive)
        assertEquals(1, engine.resumes)
    }

    @Test fun `a running sleep timer pauses at the deadline`() = runTest {
        focusResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val engine = FakeEngine()
        controller = newController(engine, context = context, elapsedTime = { testScheduler.currentTime })
        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L)
        controller.start(MediaKey(ServiceId.Spotify, MediaType.Track, "a"), "spotify:track:a", 0L)
        controller.setSleepTimer(1)

        advanceTimeBy(61_000L)
        runCurrent()

        assertEquals(SleepTimerState.Off, controller.sleepTimer.value)
        assertFalse(controller.isActive)
        assertEquals(1, engine.pauses)
    }

    @Test fun `a zero-minute sleep timer clears instead of installing`() = runTest {
        controller = newController(FakeEngine(), elapsedTime = { testScheduler.currentTime })
        controller.setSleepTimer(5)

        controller.setSleepTimer(0)

        assertEquals(SleepTimerState.Off, controller.sleepTimer.value)
    }

    @Test fun `the sleep timer does not pause an idle session at the deadline`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine, elapsedTime = { testScheduler.currentTime })
        controller.setSleepTimer(1)

        advanceTimeBy(61_000L)
        runCurrent()

        assertEquals(SleepTimerState.Off, controller.sleepTimer.value)
        assertEquals(0, engine.pauses)
    }

    @Test fun `sleep-timer ticks refresh the notification`() = runTest {
        var refreshes = 0
        controller = newController(
            FakeEngine(),
            context = context,
            elapsedTime = { testScheduler.currentTime },
            notifyRefresh = { _, _ -> refreshes++ }
        )
        runCurrent()
        val initial = refreshes

        controller.setSleepTimer(2)
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()

        assertTrue("a countdown tick must rebuild the notification", refreshes > initial + 1)
    }

    @Test fun `an end-of-track timer is consumed once`() = runTest {
        controller = newController(FakeEngine())
        controller.setSleepTimerEndOfTrack()

        assertTrue(controller.consumeEndOfTrackTimer())
        assertEquals(SleepTimerState.Off, controller.sleepTimer.value)
        assertFalse(controller.consumeEndOfTrackTimer())
    }

    @Test fun `setNowPlaying keeps the source and the engine position in step`() = runTest {
        val engine = FakeEngine()
        controller = newController(engine)

        controller.setNowPlaying(track(uri = "spotify:track:a"), 12_000L)

        assertEquals(12_000L, engine.updatedPosition)
        assertEquals("spotify:track:a", controller.currentSource?.uri)
        controller.updatePosition(13_000L)
        controller.updateState(isPlaying = true, position = 14_000L)
        assertEquals(14_000L, engine.updatedPosition)
        assertTrue(engine.updatedIsPlaying)
        assertEquals(14_000L, controller.position)
    }

    @Test fun `an explicit source is kept instead of deriving it from the item`() = runTest {
        controller = newController(FakeEngine())

        controller.setNowPlaying(track(uri = "spotify:track:a"), 0L, PlaybackSource("stream", "https://example/a"))

        assertEquals("https://example/a", controller.currentSource?.uri)
        assertEquals("stream", controller.currentSource?.id)
    }

    @Test fun `a notice can be set and cleared`() = runTest {
        controller = newController(FakeEngine())

        controller.emitNotice("Reconnecting…")
        assertEquals("Reconnecting…", controller.notice.value)

        controller.emitNotice(null)
        assertNull(controller.notice.value)
    }

    private lateinit var controller: PlaybackController
    private var registry: MediaSessionRegistry = MediaSessionRegistry()

    private fun TestScope.newController(
        engine: PlaybackEngine,
        context: Context? = null,
        elapsedTime: () -> Long = { 0L },
        notifyRefresh: (Context, PlaybackController) -> Unit = { _, _ -> },
        notifySync: (Context, PlaybackController) -> Unit = { _, _ -> }
    ): PlaybackController {
        registry = MediaSessionRegistry()
        return PlaybackController(
            engine = engine,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            registry = registry,
            context = context,
            elapsedTime = elapsedTime,
            notifyRefresh = notifyRefresh,
            notifySync = notifySync
        ).also { controller = it }
    }

    private fun focusResponse(response: Int) {
        shadowOf(audio).setNextFocusRequestResponse(response)
    }

    /**
     * Robolectric keeps the listener on its shadow request; the field name is a shadow detail, so
     * it is resolved by type rather than by name (same seam as AudioFocusTest).
     */
    private fun focusListener(): AudioManager.OnAudioFocusChangeListener {
        val request = shadowOf(audio).lastAudioFocusRequest
        assertNotNull("no focus request was made", request)
        val field = request!!.javaClass.declaredFields.first {
            AudioManager.OnAudioFocusChangeListener::class.java.isAssignableFrom(it.type)
        }
        field.isAccessible = true
        return field.get(request) as AudioManager.OnAudioFocusChangeListener
    }

    private fun track(uri: String? = "spotify:track:a") = Track(
        id = "a",
        title = "A",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L,
        uri = uri
    )

    private class FakeEngine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackEngineState())
        override val ended = MutableStateFlow<String?>(null)
        override var onFailure: (Throwable) -> Unit = {}
        var event: PlaybackEvent? = null

        var starts = 0
        var resumes = 0
        var pauses = 0
        var stops = 0
        var seeks = 0
        var disconnects = 0
        var invalidations = 0
        var lastSource: PlaybackSource? = null
        var lastPosition = 0L
        var seekedTo: Long? = null
        var updatedPosition = 0L
        var updatedIsPlaying = false
        var resumeResult = true
        var reconfigureResult = true
        var reconfiguredSource: PlaybackSource? = null
        var reconfiguredPosition = 0L
        var reconfiguredWhilePlaying = false
        var failStart: Throwable? = null

        override suspend fun start(source: PlaybackSource, positionMs: Long, keepAudioSink: Boolean) {
            starts++
            lastSource = source
            lastPosition = positionMs
            failStart?.let { throw it }
        }

        override suspend fun resume(positionMs: Long): Boolean {
            resumes++
            lastPosition = positionMs
            return resumeResult
        }

        override suspend fun pause() {
            pauses++
        }

        override suspend fun stop() {
            stops++
        }

        override suspend fun seek(positionMs: Long) {
            seeks++
            seekedTo = positionMs
        }

        override suspend fun preload(source: PlaybackSource) = Unit

        override suspend fun reconfigure(
            source: PlaybackSource?,
            positionMs: Long,
            wasPlaying: Boolean
        ): Boolean {
            reconfiguredSource = source
            reconfiguredPosition = positionMs
            reconfiguredWhilePlaying = wasPlaying
            return reconfigureResult
        }

        override suspend fun disconnect() {
            disconnects++
        }

        override suspend fun pollEvent(): PlaybackEvent? = event

        override fun invalidateCommands() {
            invalidations++
            state.value = state.value.copy(isPlaying = false)
        }

        override fun updatePosition(positionMs: Long) {
            updatedPosition = positionMs
        }

        override fun updateState(isPlaying: Boolean, positionMs: Long) {
            updatedPosition = positionMs
            updatedIsPlaying = isPlaying
        }
    }
}
