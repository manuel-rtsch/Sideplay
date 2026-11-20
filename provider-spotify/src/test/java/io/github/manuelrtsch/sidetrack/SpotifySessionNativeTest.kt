package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.auth.SpotifyCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SpotifyCredentials
import io.github.manuelrtsch.sidetrack.auth.SpotifyTokenClient
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifySession
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The native playback paths of [SpotifySession]: connect, load, preload, resume, halt and stop.
 *
 * These were unreachable from a JVM test before: the session built its gateway on the static
 * `NativeBridge` and ran playback on `Dispatchers.Main.immediate`, which is the sandbox's own
 * main looper under Robolectric — a `runBlocking` test then blocks the very looper the session's
 * continuations are queued on and hangs. Both are injected now: the bridge records the calls, and
 * playback runs on a single-threaded dispatcher, so the real gateway, engine and audio sink run
 * exactly as they do on a device, minus librespot.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifySessionNativeTest {
    private val playbackThread = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "spotify-playback")
    }
    private val playbackDispatcher = playbackThread.asCoroutineDispatcher()

    private lateinit var store: FakeStore
    private lateinit var native: FakeNative

    @Before fun freshState() {
        store = FakeStore().apply { credentials = credentials() }
        native = FakeNative()
    }

    @After fun stopPlaybackThread() {
        playbackDispatcher.close()
    }

    private fun session(): SpotifySession = SpotifySession(
        auth = SpotifyAuth(store, FakeTokens(), nowMs = { NOW }),
        context = null,
        native = native,
        playbackContext = playbackDispatcher
    )

    // --- connect, load and preload -------------------------------------------------------------

    @Test fun `play connects creates the player loads the uri and warms the next item`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)

        session.play(track("a").key, "spotify:track:a", positionMs = 1_000L)
        awaitCalls { native.calls.contains("preload:spotify:track:b") }

        assertEquals("connect:access", native.calls.first { it.startsWith("connect") })
        assertEquals(1, native.calls.count { it == "createPlayer" })
        assertTrue(native.calls.contains("load:spotify:track:a@1000:true"))
        assertEquals("a", session.queue.snapshot.current?.id)
        assertTrue(session.playback.isActive)
        session.playback.stop()
    }

    @Test fun `a second play reuses the live session and only re-checks the token`() = runBlocking {
        val session = session()
        session.play(track("a").key, "spotify:track:a")
        native.calls.clear()

        session.play(track("b").key, "spotify:track:b")

        assertEquals(listOf("setAccessToken:access", "load:spotify:track:b@0:true"), gatewayCalls())
        session.playback.stop()
    }

    @Test fun `a forced reconnect rebuilds the session before loading`() = runBlocking {
        val session = session()
        session.play(track("a").key, "spotify:track:a")
        native.calls.clear()

        session.play(track("a").key, "spotify:track:a", forceReconnect = true)

        assertEquals(listOf("connect:access", "createPlayer", "load:spotify:track:a@0:true"), gatewayCalls())
        session.playback.stop()
    }

    @Test fun `a track the player rejects surfaces as unavailable and leaves playback stopped`() = runBlocking {
        val session = session()
        native.loadSucceeds = false

        val error = runCatching { session.play(track("a").key, "spotify:track:a") }
            .exceptionOrNull() as SpotifyGatewayException

        assertEquals(SpotifyFailure.TrackUnavailable, error.failure)
        assertFalse(session.playback.isActive)
    }

    @Test fun `a preload failure does not disturb the playing item`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)
        native.preloadFails = true

        session.play(track("a").key, "spotify:track:a")
        awaitCalls { native.calls.contains("preload:spotify:track:b") }

        assertTrue(session.playback.isActive)
        assertEquals("a", session.queue.snapshot.current?.id)
        session.playback.stop()
    }

    // --- resume --------------------------------------------------------------------------------

    @Test fun `resume on a live session asks the player to play`() = runBlocking {
        val session = session()
        start(session, "a")
        session.playback.pause()
        awaitIdle(session)
        native.calls.clear()

        assertTrue(session.resume())

        assertTrue(native.calls.contains("play"))
        assertFalse(native.calls.any { it.startsWith("load:") })
        session.playback.stop()
    }

    @Test fun `resume with no attached player rebuilds it and reloads the current item`() = runBlocking {
        val session = session()
        start(session, "a", positionMs = 4_000L)
        // A rebuilt session drops the native player without the gateway flag knowing.
        native.playerAttached = false
        native.calls.clear()

        assertTrue(session.resume())

        // The token is still accepted, so only the player is rebuilt; the audio is reloaded
        // because a new player has no source, and it resumes at the UI position, not zero.
        assertTrue(native.calls.contains("createPlayer"))
        assertTrue(native.calls.contains("load:spotify:track:a@4000:true"))
        assertFalse(native.calls.contains("play"))
        session.playback.stop()
    }

    // --- transport fallbacks -------------------------------------------------------------------

    @Test fun `skip next starts the upcoming item`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)
        session.play(track("a").key, "spotify:track:a")

        session.skipNext()

        awaitCalls { native.calls.contains("load:spotify:track:b@0:true") }
        assertEquals("b", session.queue.snapshot.current?.id)
        session.playback.stop()
    }

    @Test fun `skip previous past the threshold seeks to the start`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)
        session.queue.next(auto = false)
        start(session, "b")
        session.playback.updateState(isPlaying = true, position = 5_000L)
        native.calls.clear()

        session.skipPrevious()

        awaitCalls { native.calls.contains("seek:0") }
        assertEquals("b", session.queue.snapshot.current?.id)
        session.playback.stop()
    }

    @Test fun `skip previous inside the threshold rewinds to queue history`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)
        session.queue.next(auto = false)
        start(session, "b")
        session.playback.updateState(isPlaying = true, position = 1_000L)

        session.skipPrevious()

        awaitCalls { native.calls.contains("load:spotify:track:a@0:true") }
        assertEquals("a", session.queue.snapshot.current?.id)
        session.playback.stop()
    }

    // --- halt, stop and sign out ---------------------------------------------------------------

    @Test fun `halt keeps the queue and consumes the stopped event it caused`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)
        start(session, "a", positionMs = 2_000L)
        session.notePlayRequest(7L, "spotify:track:a")
        native.calls.clear()

        session.haltPlayback()

        awaitCalls { native.calls.contains("stop") }
        assertTrue(session.halting)
        assertEquals("a", session.queue.snapshot.current?.id)
        // The event librespot emits for this stop belongs to the player the halt closed.
        assertTrue(session.consumeHaltStop("spotify:track:a", 7L))
    }

    @Test fun `stopping playback clears the queue and the resume point`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a"), track("b")), 0)
        session.play(track("a").key, "spotify:track:a", positionMs = 2_000L)

        session.stopPlayback()

        awaitCalls { native.calls.contains("stop") }
        assertEquals(null, session.queue.snapshot.current)
        assertEquals(null, session.resumePoint)
        assertFalse(session.halting)
    }

    @Test fun `sign out stops the decoder and clears the identity`() = runBlocking {
        val session = session()
        session.queue.play(listOf(track("a")), 0)
        session.play(track("a").key, "spotify:track:a")
        native.calls.clear()

        session.signOut()

        awaitCalls { native.calls.contains("stop") }
        assertFalse(session.signedIn.value)
        assertEquals(null, store.credentials)
        assertEquals(null, session.queue.snapshot.current)
    }

    // --- fixture -------------------------------------------------------------------------------

    /** Waits for work the session started on its own scope, e.g. a preload or a transport skip. */
    private fun awaitCalls(timeoutMs: Long = 5_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!predicate() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue("native calls: ${native.calls}", predicate())
    }

    /** Waits for a controller command that [PlaybackController] runs on its own scope. */
    private fun awaitIdle(session: SpotifySession, timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (session.playback.isActive && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    /** The calls the gateway itself makes, without the audio sink's drain and track plumbing. */
    private fun gatewayCalls(): List<String> = native.calls.filter {
        it.startsWith("connect:") || it.startsWith("setAccessToken:") || it == "createPlayer" ||
            it.startsWith("load:") || it.startsWith("preload:")
    }

    /**
     * Plays an item the way the ViewModel does: the controller's resume/halt paths read
     * `playback.current`, which only `setNowPlaying` installs.
     */
    private fun start(
        session: SpotifySession,
        id: String,
        positionMs: Long = 0L
    ): Unit = runBlocking {
        session.play(track(id).key, "spotify:track:$id", positionMs)
        session.playback.setNowPlaying(track(id), positionMs)
    }

    private fun track(id: String): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Aurora",
        artistId = "ar1",
        albumId = "al1",
        artRes = 0,
        durationMs = 180_000L,
        uri = "spotify:track:$id",
        service = ServiceId.Spotify
    )

    private fun credentials() = SpotifyCredentials("access", "refresh", NOW + 600_000L, "streaming")

    /** A player whose calls are recorded in order; [calls] is what the assertions read. */
    private class FakeNative : SpotifyNative {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var playerAttached = false
        var loadSucceeds = true
        var preloadFails = false

        override val isAvailable: Boolean get() = true
        override fun initialize() = true
        override fun setTmpDir(path: String) = Unit
        override fun setCacheDir(path: String, sizeLimitBytes: Long) = Unit

        override fun connect(accessToken: String): String? {
            calls += "connect:$accessToken"
            // A successful connect drops the previous player, exactly like librespot.
            playerAttached = false
            return null
        }

        override fun setAccessToken(accessToken: String): Boolean {
            calls += "setAccessToken:$accessToken"
            return true
        }

        override fun disconnect() {
            calls += "disconnect"
        }
        override fun hasPlayer(): Boolean = playerAttached

        override fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean {
            calls += "createPlayer"
            playerAttached = true
            return true
        }

        override fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean {
            calls += "recreatePlayer"
            return true
        }

        override fun preload(uri: String): Boolean {
            calls += "preload:$uri"
            if (preloadFails) throw IllegalStateException("preload rejected")
            return true
        }

        override fun load(uri: String, positionMs: Long, autoplay: Boolean): Boolean {
            calls += "load:$uri@$positionMs:$autoplay"
            return loadSucceeds
        }

        override fun play() = true.also { calls += "play" }
        override fun pause() = true.also { calls += "pause" }
        override fun stop() = true.also { calls += "stop" }
        override fun seek(positionMs: Long) = true.also { calls += "seek:$positionMs" }

        /** One short buffer per read: enough to keep the drain alive without spinning the CPU. */
        override fun readAudio(timeoutMs: Long): ByteArray? {
            Thread.sleep(2)
            return ByteArray(1_024)
        }

        override fun drainAudio() = Unit
        override fun audioFormat(): String? = """{"sampleRate":44100,"channels":2}"""
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
        override fun playlistItemsAdd(id: String, uris: String): String? = null
        override fun playlistItemsRemove(id: String, uris: String): String? = "ok"
    }

    private class FakeStore(
        var credentials: SpotifyCredentials? = null
    ) : SpotifyCredentialStore {
        override fun read(): SpotifyCredentials? = credentials
        override fun save(credentials: SpotifyCredentials) {
            this.credentials = credentials
        }
        override fun clear() {
            credentials = null
        }
    }

    private class FakeTokens : SpotifyTokenClient {
        override suspend fun exchange(
            clientId: String,
            code: String,
            verifier: String,
            redirectUri: String
        ): SpotifyCredentials = error("not used")

        override suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials = error("not used")
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
