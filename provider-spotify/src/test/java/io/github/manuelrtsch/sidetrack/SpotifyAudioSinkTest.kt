package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import io.github.manuelrtsch.sidetrack.playback.SpotifyAudioSink
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The sink owns the `AudioTrack` that librespot's PCM is written to. Its failure modes are the ones
 * users report as "playback stopped but the app looks fine": a drain that dies without telling
 * anyone, a stop that hangs on the audio HAL, or a track that is never created because the native
 * format was unreadable. Robolectric's shadow track accepts writes, so the real lifecycle code runs.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifyAudioSinkTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stopScope() {
        scope.cancel()
    }

    private fun sink(native: FakeNative) = SpotifyAudioSink(native = native, scope = scope)

    @Test fun `a sink with no track has no position and tolerates stop and seek`() = runBlocking {
        val sink = sink(FakeNative())

        assertNull(sink.currentPositionMs())
        assertFalse(sink.isRunning)
        sink.seek(5_000L)
        sink.pause()
        sink.stop()
        sink.stop()

        assertNull(sink.currentPositionMs())
        assertFalse(sink.isRunning)
    }

    @Test fun `starting a track drains PCM and reports when audio flows`() = runBlocking {
        val native = FakeNative().apply { pcm = listOf(ByteArray(4096)) }
        val sink = sink(native)
        val flowing = CompletableDeferred<Boolean>()
        sink.onBuffering = { if (!it) flowing.complete(true) }

        sink.start(positionMs = 1_000L)

        assertTrue(sink.isRunning)
        assertNotNull(sink.currentPositionMs())
        assertTrue(withTimeout(5_000L) { flowing.await() })
        sink.stop()
        assertFalse(sink.isRunning)
    }

    @Test fun `the native format is used when it is valid and defaults when it is not`() = runBlocking {
        val valid = FakeNative().apply { format = """{"sampleRate":22050,"channels":1}""" }
        val invalid = FakeNative().apply { format = "not json" }
        val missing = FakeNative().apply { format = null }

        listOf(valid, invalid, missing).forEach { native ->
            val sink = sink(native)
            sink.start()
            assertNotNull("a track is created for format ${native.format}", sink.currentPositionMs())
            sink.stop()
        }
    }

    @Test fun `a drain that fails reports the error instead of stopping silently`() = runBlocking {
        val native = FakeNative().apply { readThrows = true }
        val sink = sink(native)
        val failed = CompletableDeferred<Throwable>()
        sink.onFailure = { failed.complete(it) }

        sink.start()

        val error = withTimeout(5_000L) { failed.await() }
        assertEquals("synthetic native failure", error.message)
        sink.stop()
    }

    @Test fun `resuming without a paused track restarts from the given position`() = runBlocking {
        val native = FakeNative().apply { pcm = listOf(ByteArray(4096)) }
        val sink = sink(native)

        val reused = sink.resume(3_000L)

        assertFalse(reused)
        assertTrue(sink.isRunning)
        assertNotNull(sink.currentPositionMs())
        sink.stop()
    }

    @Test fun `pause keeps the track alive and resume reuses it`() = runBlocking {
        val native = FakeNative().apply { pcm = listOf(ByteArray(4096)) }
        val sink = sink(native)
        sink.start()
        assertNotNull(sink.currentPositionMs())

        sink.pause()
        assertFalse(sink.isRunning)

        assertTrue(sink.resume(0L))
        assertTrue(sink.isRunning)
        sink.stop()
    }

    @Test fun `reset drops the item's audio but keeps the track for the next one`() = runBlocking {
        val native = FakeNative().apply { pcm = listOf(ByteArray(4096)) }
        val sink = sink(native)
        sink.start()
        val position = sink.currentPositionMs()

        sink.reset()

        assertFalse(sink.isRunning)
        assertNotNull(position)
        assertNotNull(sink.currentPositionMs())
        sink.stop()
    }

    /** A native player whose PCM and format the test controls. */
    private class FakeNative : SpotifyNative {
        var pcm: List<ByteArray> = emptyList()
        var format: String? = """{"sampleRate":44100,"channels":2}"""
        var readThrows = false
        private val reads = AtomicInteger()

        override val isAvailable: Boolean get() = true
        override fun initialize() = true
        override fun setTmpDir(path: String) = Unit
        override fun setCacheDir(path: String, sizeLimitBytes: Long) = Unit
        override fun connect(accessToken: String) = true
        override fun setAccessToken(accessToken: String) = true
        override fun disconnect() = Unit
        override fun hasPlayer() = true
        override fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean) = true
        override fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean) = true
        override fun preload(uri: String) = true
        override fun load(uri: String, positionMs: Long, autoplay: Boolean) = true
        override fun play() = true
        override fun pause() = true
        override fun stop() = true
        override fun seek(positionMs: Long) = true
        override fun readAudio(timeoutMs: Long): ByteArray? {
            if (readThrows) throw IllegalStateException("synthetic native failure")
            // Throttle like the real blocking read; otherwise a fake that returns null instantly
            // would spin the drain loop.
            if (timeoutMs > 0) Thread.sleep(1)
            val index = reads.getAndIncrement()
            return pcm.getOrNull(index)
        }

        override fun drainAudio() = Unit
        override fun audioFormat(): String? = format
        override fun pollEvent(): String? = null
        override fun catalogLibrary(filter: String, offset: Long, limit: Long): String? = null
        override fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String? = null
        override fun catalogArtist(id: String): String? = null
        override fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String? = null
        override fun catalogTopTracks(id: String, offset: Long, limit: Long): String? = null
        override fun catalogSongRadio(id: String, limit: Long): String? = null
        override fun catalogArtistRadio(id: String, limit: Long): String? = null
        override fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String? = null
        override fun collectionContains(set: String, uris: String): String? = null
        override fun collectionWrite(set: String, uris: String, removed: Boolean) = true
        override fun playlistSaved(id: String): String? = null
        override fun playlistSave(id: String, save: Boolean) = true
        override fun playlistItemsAdd(id: String, uris: String): String? = "0"
        override fun playlistItemsRemove(id: String, uris: String) = true
    }
}
