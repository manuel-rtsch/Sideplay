package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.Media3Player
import io.github.manuelrtsch.sidetrack.playback.Media3PlayerListener
import io.github.manuelrtsch.sidetrack.playback.Media3PlayerStatus
import io.github.manuelrtsch.sidetrack.playback.MediaSessionRegistry
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.playback.PlaybackEvent
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3PlaybackEngineTest {
    @Test fun `start prepares the HTTP source with its headers`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        val source = PlaybackSource("navidrome:track:1", "http://host/stream?id=1", mapOf("X-Token" to "abc"))

        engine.start(source, 12_000L)

        assertEquals(source, player.prepared)
        assertEquals(12_000L, player.preparedPosition)
        assertTrue(player.preparedPlayWhenReady)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(12_000L, engine.state.value.positionMs)
        assertEquals(source, engine.state.value.source)
    }

    @Test fun `buffering and ready drive the buffering state and a loading event`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        player.emitStatus(Media3PlayerStatus.Buffering)

        assertTrue(engine.state.value.isBuffering)
        assertEquals(PlaybackEvent.Loading("http://host/a", 0L), engine.pollEvent())

        player.emitStatus(Media3PlayerStatus.Ready)

        assertFalse(engine.state.value.isBuffering)
        assertNull(engine.pollEvent())
    }

    @Test fun `playing and paused become typed events`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        player.emitIsPlaying(true)
        assertTrue(engine.state.value.isPlaying)
        assertEquals(PlaybackEvent.Playing("http://host/a", 0L), engine.pollEvent())

        player.emitIsPlaying(false)
        assertFalse(engine.state.value.isPlaying)
        assertEquals(PlaybackEvent.Paused("http://host/a", 0L), engine.pollEvent())
    }

    @Test fun `seek updates the position and reports a seeked event`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        engine.seek(42_000L)

        assertEquals(42_000L, player.seekedTo)
        assertEquals(42_000L, engine.state.value.positionMs)
        assertEquals(PlaybackEvent.Seeked(42_000L), engine.pollEvent())
    }

    @Test fun `ended stops the engine and reports the end`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        player.emitStatus(Media3PlayerStatus.Ended)

        assertFalse(engine.state.value.isPlaying)
        assertEquals(PlaybackEvent.Ended("http://host/a"), engine.pollEvent())
    }

    @Test fun `a player error reports unavailable and notifies the controller`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        var failure: Throwable? = null
        engine.onFailure = { failure = it }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        val error = IllegalStateException("decoder")
        player.emitError(error)

        assertSame(error, failure)
        assertFalse(engine.state.value.isPlaying)
        assertEquals(PlaybackEvent.Unavailable("http://host/a"), engine.pollEvent())
    }

    @Test fun `stop does not report a stopped event`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        engine.stop()

        assertTrue(player.stopped)
        assertFalse(engine.state.value.isPlaying)
        assertNull(engine.pollEvent())
    }

    @Test fun `resume without a source reports failure`() = runTest {
        val engine = Media3PlaybackEngine { FakeMedia3Player() }

        assertFalse(engine.resume(0L))
    }

    @Test fun `reconfigure releases the old player and reloads the source`() = runTest {
        val players = mutableListOf<FakeMedia3Player>()
        val engine = Media3PlaybackEngine {
            FakeMedia3Player().also { players += it }
        }
        val source = PlaybackSource("a", "http://host/a")
        engine.start(source, 0L)

        val rebuilt = engine.reconfigure(source, 30_000L, wasPlaying = true)

        assertTrue(rebuilt)
        assertEquals(2, players.size)
        assertTrue(players[0].released)
        assertEquals(source, players[1].prepared)
        assertEquals(30_000L, players[1].preparedPosition)
        assertTrue(players[1].preparedPlayWhenReady)
        assertTrue(engine.state.value.isPlaying)
    }

    @Test fun `disconnect releases the player and clears the state`() = runTest {
        val player = FakeMedia3Player()
        val engine = Media3PlaybackEngine { player }
        engine.start(PlaybackSource("a", "http://host/a"), 0L)

        engine.disconnect()

        assertTrue(player.released)
        assertNull(engine.state.value.source)
        assertFalse(engine.state.value.isPlaying)
    }

    @Test fun `a fake HTTP source serves the stream with range requests and headers`() {
        FakeHttpStream(requireToken = "secret").use { stream ->
            val connection = (URL(stream.authUrl).openConnection() as HttpURLConnection).apply {
                setRequestProperty(FakeHttpStream.TOKEN_HEADER, "secret")
                setRequestProperty("Range", "bytes=100-199")
            }

            assertEquals(206, connection.responseCode)
            assertEquals("bytes 100-199/${FakeHttpStream.WAV.size}", connection.getHeaderField("Content-Range"))
            assertEquals(100, connection.inputStream.use { it.readBytes() }.size)
            assertEquals("bytes=100-199", stream.requests.single().range)
            assertEquals("secret", stream.requests.single().token)
        }
    }

    @Test fun `the shared controller plays an HTTP source through the Media3 engine`() = runTest {
        FakeHttpStream(requireToken = "secret").use { stream ->
            val player = FakeMedia3Player()
            val engine = Media3PlaybackEngine { player }
            val registry = MediaSessionRegistry()
            val controller = PlaybackController(
                engine,
                CoroutineScope(StandardTestDispatcher(testScheduler)),
                registry
            )
            val key = MediaKey(ServiceId.Navidrome, MediaType.Track, "1")

            controller.start(
                key,
                stream.authUrl,
                0L,
                headers = mapOf(FakeHttpStream.TOKEN_HEADER to "secret")
            )

            assertSame(controller, registry.active)
            assertTrue(controller.isActive)
            assertEquals(stream.authUrl, player.prepared?.uri)
            assertEquals("secret", player.prepared?.headers?.get(FakeHttpStream.TOKEN_HEADER))
            assertEquals(stream.authUrl, controller.currentSource?.uri)
        }
    }

    private class FakeMedia3Player : Media3Player {
        override var listener: Media3PlayerListener? = null

        var prepared: PlaybackSource? = null
        var preparedPosition: Long? = null
        var preparedPlayWhenReady = false
        var seekedTo: Long? = null
        var stopped = false
        var released = false

        override fun prepare(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean) {
            prepared = source
            preparedPosition = startPositionMs
            preparedPlayWhenReady = playWhenReady
        }

        override fun play() = Unit
        override fun pause() = Unit
        override fun stop() {
            stopped = true
        }

        override fun seekTo(positionMs: Long) {
            seekedTo = positionMs
            // ExoPlayer reports a seek as a position discontinuity; mirror that here.
            listener?.onPositionDiscontinuity(positionMs)
        }

        override fun release() {
            released = true
        }

        override val isPlaying: Boolean = false
        override val currentPositionMs: Long = 0L
        override val status: Media3PlayerStatus = Media3PlayerStatus.Idle

        fun emitStatus(status: Media3PlayerStatus) = listener?.onStatusChanged(status)
        fun emitIsPlaying(isPlaying: Boolean) = listener?.onIsPlayingChanged(isPlaying)
        fun emitError(error: Throwable) = listener?.onPlayerError(error)
    }
}
