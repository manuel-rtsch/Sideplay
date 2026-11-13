package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.data.SpotifyFailure
import io.github.manuelrtsch.sideplay.data.SpotifyGateway
import io.github.manuelrtsch.sideplay.data.SpotifyGatewayException
import io.github.manuelrtsch.sideplay.playback.MediaSessionOwner
import io.github.manuelrtsch.sideplay.playback.MediaSessionRegistry
import io.github.manuelrtsch.sideplay.playback.SpotifyPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPlaybackTest {
    @Test fun `start claims the session and loads the track`() = runTest {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = SpotifyPlayback(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)

        playback.start("spotify:track:a", 0L)

        assertSame(playback, registry.active)
        assertTrue(playback.isActive)
        assertEquals("spotify:track:a", gateway.loadedUri)
        assertTrue(gateway.autoplay)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `starting another owner pauses Sideplay`() = runTest {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = SpotifyPlayback(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)
        val other = FakeOwner()
        registry.register(other)
        playback.start("spotify:track:a", 0L)

        registry.activate(other)
        runCurrent()

        assertFalse(playback.isActive)
        assertTrue(gateway.paused)
        assertSame(other, registry.active)
    }

    @Test fun `failed load releases the session`() = runTest {
        val gateway = FakeGateway(failLoad = true)
        val registry = MediaSessionRegistry()
        val playback = SpotifyPlayback(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)

        runCatching { playback.start("spotify:track:a", 0L) }

        assertFalse(playback.isActive)
        assertNull(registry.active)
    }

    @Test fun `stop deactivates and tells the gateway`() = runTest {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = SpotifyPlayback(gateway, CoroutineScope(StandardTestDispatcher(testScheduler)), registry)
        var stopped = false
        playback.onStopped = { stopped = true }
        playback.start("spotify:track:a", 0L)

        playback.stop()

        assertFalse(playback.isActive)
        assertNull(registry.active)
        assertTrue(gateway.stopped)
        assertTrue(stopped)
    }

    @Test fun `reconfigure rebuilds the player and reloads the current item`() = runTest {
        val gateway = FakeGateway()
        val playback = SpotifyPlayback(
            gateway,
            CoroutineScope(StandardTestDispatcher(testScheduler)),
            MediaSessionRegistry()
        )
        playback.setNowPlaying(
            SpotifyTrack(
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
        playback.start("spotify:track:a", 12_000L)

        playback.reconfigure()

        assertTrue(gateway.recreated)
        assertEquals("spotify:track:a", gateway.loadedUri)
        assertEquals(2, gateway.loads)
        assertTrue(playback.isActive)
    }

    @Test fun `requestNext prefers the attached UI owner`() {
        val playback = SpotifyPlayback(
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
        val playback = SpotifyPlayback(
            FakeGateway(),
            CoroutineScope(StandardTestDispatcher()),
            MediaSessionRegistry()
        )
        var fallbackCalls = 0
        playback.fallbackNext = { fallbackCalls++ }

        playback.requestNext()

        assertEquals(1, fallbackCalls)
    }

    private class FakeGateway(private val failLoad: Boolean = false) : SpotifyGateway {
        var loadedUri: String? = null
        var autoplay = false
        var paused = false
        var stopped = false
        var recreated = false
        var loads = 0

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

        override suspend fun play() = Unit
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
