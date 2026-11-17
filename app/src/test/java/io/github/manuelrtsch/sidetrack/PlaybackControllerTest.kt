package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGateway
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.playback.MediaSessionOwner
import io.github.manuelrtsch.sidetrack.playback.MediaSessionRegistry
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import io.github.manuelrtsch.sidetrack.playback.librespotPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControllerTest {
    @Test fun `start claims the session and loads the track`() = runTest {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)

        playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 0L)

        assertSame(playback, registry.active)
        assertTrue(playback.isActive)
        assertEquals("spotify:track:a", gateway.loadedUri)
        assertTrue(gateway.autoplay)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `starting another owner pauses Sidetrack`() = runTest {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)
        val other = FakeOwner()
        registry.register(other)
        playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 0L)

        registry.activate(other)
        runCurrent()

        assertFalse(playback.isActive)
        assertTrue(gateway.paused)
        assertSame(other, registry.active)
    }

    @Test fun `play with nothing loaded asks the owner to start the item`() = runTest {
        val gateway = FakeGateway()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), MediaSessionRegistry())
        var restarts = 0
        playback.onResumeWithoutSource = { restarts++ }

        assertTrue(playback.resume())

        // A cold process has no source: asking the player to play would deliver silence.
        assertEquals(1, restarts)
        assertEquals(0, gateway.plays)
    }

    @Test fun `play with nothing loaded falls back when no ViewModel is attached`() = runTest {
        val gateway = FakeGateway()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), MediaSessionRegistry())
        var restarts = 0
        playback.fallbackResume = { restarts++ }

        assertTrue(playback.resume())

        assertEquals(1, restarts)
    }

    @Test fun `play with nothing loaded and nobody to start it reports failure`() = runTest {
        val gateway = FakeGateway()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), MediaSessionRegistry())

        assertFalse(playback.resume())
        assertEquals(0, gateway.plays)
    }

    @Test fun `failed load releases the session`() = runTest {
        val gateway = FakeGateway(failLoad = true)
        val registry = MediaSessionRegistry()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)

        runCatching { playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 0L) }

        assertFalse(playback.isActive)
        assertNull(registry.active)
    }

    @Test fun `stop deactivates and tells the gateway`() = runTest {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = librespotPlaybackController(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)
        var stopped = false
        playback.onStopped = { stopped = true }
        playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 0L)

        playback.stop()

        assertFalse(playback.isActive)
        assertNull(registry.active)
        assertTrue(gateway.stopped)
        assertTrue(stopped)
    }

    @Test fun `reconfigure rebuilds the player and reloads the current item`() = runTest {
        val gateway = FakeGateway()
        val playback = librespotPlaybackController(
            gateway,
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            MediaSessionRegistry()
        )
        playback.setNowPlaying(
            Track(
                id = "a",
                title = "A",
                artist = "Artist",
                artistId = "artist",
                albumId = "album",
                artRes = 0,
                durationMs = 180_000L,
                uri = "spotify:track:a"
            ),
            12_000L
        )
        playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 12_000L)

        playback.reconfigure()

        assertTrue(gateway.recreated)
        assertEquals("spotify:track:a", gateway.loadedUri)
        assertEquals(2, gateway.loads)
        assertTrue(playback.isActive)
    }

    @Test fun `requestNext prefers the attached UI owner`() {
        val playback = librespotPlaybackController(
            FakeGateway(),
            CoroutineScope(StandardTestDispatcher()),
            MediaSessionRegistry()
        )
        var uiCalls = 0
        var fallbackCalls = 0
        playback.onNext = { uiCalls++ }
        playback.fallbackNext = { fallbackCalls++ }

        playback.requestNext()

        assertEquals(1, uiCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test fun `requestNext falls back when the UI owner is gone`() {
        val playback = librespotPlaybackController(
            FakeGateway(),
            CoroutineScope(StandardTestDispatcher()),
            MediaSessionRegistry()
        )
        var fallbackCalls = 0
        playback.fallbackNext = { fallbackCalls++ }

        playback.requestNext()

        assertEquals(1, fallbackCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sleep timer counts down once per minute`() = runTest {
        val playback = librespotPlaybackController(
            FakeGateway(),
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            MediaSessionRegistry(),
            elapsedTime = { testScheduler.currentTime }
        )

        playback.setSleepTimer(5)
        assertEquals(SleepTimerState.Countdown(5, 300_000L, 5), playback.sleepTimer.value)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(4, (playback.sleepTimer.value as SleepTimerState.Countdown).remainingMinutes)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sleep timer pauses at the deadline`() = runTest {
        val gateway = FakeGateway()
        val playback = librespotPlaybackController(
            gateway,
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            MediaSessionRegistry(),
            elapsedTime = { testScheduler.currentTime }
        )
        playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 0L)

        playback.setSleepTimer(5)
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()

        assertEquals(SleepTimerState.Off, playback.sleepTimer.value)
        assertFalse(playback.isActive)
        assertTrue(gateway.paused)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a new duration replaces the old deadline`() = runTest {
        val playback = librespotPlaybackController(
            FakeGateway(),
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            MediaSessionRegistry(),
            elapsedTime = { testScheduler.currentTime }
        )

        playback.setSleepTimer(5)
        playback.setSleepTimer(30)
        advanceTimeBy(5 * 60_000L + 1)
        runCurrent()

        val timer = playback.sleepTimer.value as SleepTimerState.Countdown
        assertEquals(30, timer.minutes)
        assertTrue(timer.remainingMinutes > 0)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `clearing the timer cancels expiry`() = runTest {
        val gateway = FakeGateway()
        val playback = librespotPlaybackController(
            gateway,
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            MediaSessionRegistry(),
            elapsedTime = { testScheduler.currentTime }
        )
        playback.start(MediaKey.spotify(MediaType.Track, "a"), "spotify:track:a", 0L)

        playback.setSleepTimer(5)
        playback.clearSleepTimer()
        advanceTimeBy(6 * 60_000L)
        runCurrent()

        assertEquals(SleepTimerState.Off, playback.sleepTimer.value)
        assertTrue(playback.isActive)
        assertFalse(gateway.paused)
    }

    @Test fun `end of track timer is consumed once`() {
        val playback = librespotPlaybackController(
            FakeGateway(),
            CoroutineScope(StandardTestDispatcher()),
            MediaSessionRegistry()
        )
        playback.setSleepTimerEndOfTrack()

        assertTrue(playback.consumeEndOfTrackTimer())
        assertEquals(SleepTimerState.Off, playback.sleepTimer.value)
        assertFalse(playback.consumeEndOfTrackTimer())
    }

    private class FakeGateway(private val failLoad: Boolean = false) : SpotifyGateway {
        var loadedUri: String? = null
        var autoplay = false
        var paused = false
        var stopped = false
        var recreated = false
        var loads = 0
        var plays = 0

        override suspend fun connect(accessToken: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun recreatePlayer(): Boolean {
            recreated = true
            return true
        }
        override suspend fun preload(uri: String) = Unit

        override suspend fun load(uri: String, positionMs: Long, autoplay: Boolean) {
            if (failLoad) throw SpotifyGatewayException(SpotifyFailure.TrackUnavailable, "missing")
            loadedUri = uri
            loads++
            this.autoplay = autoplay
        }

        override suspend fun play() {
            plays++
        }
        override suspend fun pause() {
            paused = true
        }

        override suspend fun stop() {
            stopped = true
        }

        override suspend fun seek(positionMs: Long) = Unit
    }

    private class FakeOwner : MediaSessionOwner {
        override val sessionId = "other"
        override val notificationChannelId = "other"
        override val notificationChannelName = "Other"
        override val notificationId = 42
        override var isActive = true
            private set

        override fun pause() {
            isActive = false
        }
    }
}
