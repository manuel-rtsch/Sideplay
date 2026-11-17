package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGateway
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.playback.LibrespotPlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibrespotPlaybackEngineTest {
    @Test fun `start loads the source and marks the engine playing`() = runTest {
        val gateway = FakeGateway()
        val engine = engine(gateway)

        engine.start(PlaybackSource("a", "spotify:track:a"), 12_000L)

        assertEquals("spotify:track:a", gateway.loadedUri)
        assertEquals(12_000L, gateway.loadedPosition)
        assertTrue(gateway.autoplay)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(12_000L, engine.state.value.positionMs)
        assertEquals("spotify:track:a", engine.state.value.source?.uri)
    }

    @Test fun `a failed load clears the playing state and rethrows`() = runTest {
        val gateway = FakeGateway(failLoad = true)
        val engine = engine(gateway)

        val error = runCatching {
            engine.start(PlaybackSource("a", "spotify:track:a"), 0L)
        }.exceptionOrNull()

        assertTrue(error is SpotifyGatewayException)
        assertFalse(engine.state.value.isPlaying)
    }

    @Test fun `resume realigns the player when the audio buffer was lost`() = runTest {
        val gateway = FakeGateway()
        val engine = engine(gateway)

        val started = engine.resume(42_000L)

        assertTrue(started)
        assertEquals(42_000L, gateway.seekedTo)
        assertEquals(1, gateway.plays)
        assertTrue(engine.state.value.isPlaying)
    }

    @Test fun `pause and stop reach the gateway`() = runTest {
        val gateway = FakeGateway()
        val engine = engine(gateway)

        engine.pause()
        assertTrue(gateway.paused)
        assertFalse(engine.state.value.isPlaying)

        engine.stop()
        assertTrue(gateway.stopped)
        assertFalse(engine.state.value.isPlaying)
    }

    @Test fun `seek updates the engine position and the gateway`() = runTest {
        val gateway = FakeGateway()
        val engine = engine(gateway)

        engine.seek(90_000L)

        assertEquals(90_000L, gateway.seekedTo)
        assertEquals(90_000L, engine.state.value.positionMs)
    }

    @Test fun `reconfigure reloads the current source at the same position`() = runTest {
        val gateway = FakeGateway()
        val engine = engine(gateway)
        val source = PlaybackSource("a", "spotify:track:a")

        val rebuilt = engine.reconfigure(source, 30_000L, wasPlaying = true)

        assertTrue(rebuilt)
        assertTrue(gateway.recreated)
        assertEquals("spotify:track:a", gateway.loadedUri)
        assertEquals(30_000L, gateway.loadedPosition)
        assertTrue(gateway.autoplay)
        assertTrue(engine.state.value.isPlaying)
    }

    @Test fun `reconfigure without a player reports that nothing was rebuilt`() = runTest {
        val gateway = FakeGateway(recreate = false)
        val engine = engine(gateway)

        val rebuilt = engine.reconfigure(PlaybackSource("a", "spotify:track:a"), 0L, false)

        assertFalse(rebuilt)
        assertNull(gateway.loadedUri)
    }

    @Test fun `pollEvent parses the native protocol into typed events`() = runTest {
        val engine = engine(FakeGateway(), event = """{"type":"position","positionMs":1234}""")

        assertEquals(PlaybackEvent.Position(1_234L), engine.pollEvent())
    }

    @Test fun `pollEvent returns null when the native queue is empty`() = runTest {
        val engine = engine(FakeGateway(), event = null)

        assertNull(engine.pollEvent())
    }

    @Test fun `updateState and updatePosition keep the engine state in step`() {
        val engine = engine(FakeGateway())

        engine.updateState(true, 5_000L)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(5_000L, engine.state.value.positionMs)

        engine.updatePosition(6_000L)
        assertEquals(6_000L, engine.state.value.positionMs)
    }

    @Test fun `event parsing covers every librespot event type`() {
        assertEquals(
            PlaybackEvent.Loading("spotify:track:a", 10L, 7L),
            PlaybackEvent.fromJson("""{"type":"loading","uri":"spotify:track:a","positionMs":10,"playRequestId":7}""")
        )
        assertEquals(
            PlaybackEvent.Playing("spotify:track:a", 20L),
            PlaybackEvent.fromJson("""{"type":"playing","uri":"spotify:track:a","positionMs":20}""")
        )
        assertEquals(
            PlaybackEvent.Paused("spotify:track:a", null),
            PlaybackEvent.fromJson("""{"type":"paused","uri":"spotify:track:a"}""")
        )
        assertEquals(
            PlaybackEvent.Seeked(30L),
            PlaybackEvent.fromJson("""{"type":"seeked","positionMs":30}""")
        )
        assertEquals(
            PlaybackEvent.PositionCorrection(40L),
            PlaybackEvent.fromJson("""{"type":"positionCorrection","positionMs":40}""")
        )
        assertEquals(
            PlaybackEvent.Shuffle(true),
            PlaybackEvent.fromJson("""{"type":"shuffle","shuffle":true}""")
        )
        assertEquals(
            PlaybackEvent.Repeat(track = false, context = true),
            PlaybackEvent.fromJson("""{"type":"repeat","context":true}""")
        )
        assertEquals(
            PlaybackEvent.Ended("spotify:track:a"),
            PlaybackEvent.fromJson("""{"type":"ended","uri":"spotify:track:a"}""")
        )
        assertEquals(
            PlaybackEvent.Stopped("spotify:track:a", 9L),
            PlaybackEvent.fromJson("""{"type":"stopped","uri":"spotify:track:a","playRequestId":9}""")
        )
        assertEquals(
            PlaybackEvent.Unavailable("spotify:track:a"),
            PlaybackEvent.fromJson("""{"type":"unavailable","uri":"spotify:track:a"}""")
        )
        assertEquals(
            PlaybackEvent.Unknown("something-new"),
            PlaybackEvent.fromJson("""{"type":"something-new"}""")
        )
        assertEquals(
            PlaybackEvent.Unknown("malformed"),
            PlaybackEvent.fromJson("not json")
        )
    }

    private fun engine(gateway: SpotifyGateway, event: String? = null) = LibrespotPlaybackEngine(
        gateway = gateway,
        audio = null,
        eventSource = { event }
    )

    private class FakeGateway(
        private val failLoad: Boolean = false,
        private val recreate: Boolean = true
    ) : SpotifyGateway {
        var loadedUri: String? = null
        var loadedPosition: Long? = null
        var autoplay = false
        var paused = false
        var stopped = false
        var recreated = false
        var plays = 0
        var seekedTo: Long? = null

        override suspend fun connect(accessToken: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun recreatePlayer(): Boolean {
            recreated = true
            return recreate
        }
        override suspend fun preload(uri: String) = Unit

        override suspend fun load(uri: String, positionMs: Long, autoplay: Boolean) {
            if (failLoad) throw SpotifyGatewayException(SpotifyFailure.TrackUnavailable, "missing")
            loadedUri = uri
            loadedPosition = positionMs
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
        override suspend fun seek(positionMs: Long) {
            seekedTo = positionMs
        }
    }
}
