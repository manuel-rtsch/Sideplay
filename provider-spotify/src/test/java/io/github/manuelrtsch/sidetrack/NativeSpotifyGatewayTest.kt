package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import io.github.manuelrtsch.sidetrack.data.NativeSpotifyGateway
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSpotifyGatewayTest {
    @Test fun `unavailable library maps to Unavailable`() = runTest {
        val gateway = NativeSpotifyGateway(FakeNative(isAvailable = false)) { "token" }

        val failure = runCatching { gateway.connect("token") }.exceptionOrNull()

        assertTrue(failure is SpotifyGatewayException)
        assertEquals(SpotifyFailure.Unavailable, (failure as SpotifyGatewayException).failure)
    }

    @Test fun `connect creates the player`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "token" }

        gateway.connect("token")

        assertEquals("token", native.token)
        assertTrue(native.playerCreated)
    }

    @Test fun `failed load maps to TrackUnavailable`() = runTest {
        val native = FakeNative().apply { loadOk = false }
        val gateway = NativeSpotifyGateway(native) { "token" }

        val failure = runCatching { gateway.load("spotify:track:a", 0, true) }.exceptionOrNull()

        assertEquals(SpotifyFailure.TrackUnavailable, (failure as SpotifyGatewayException).failure)
    }

    @Test fun `offline connect maps to Network instead of an expired session`() = runTest {
        val native = FakeNative().apply { connectOk = false }
        val gateway = NativeSpotifyGateway(
            native,
            accessToken = { "token" },
            networkAvailable = { false }
        )

        val failure = runCatching { gateway.connect("token") }.exceptionOrNull()

        assertEquals(SpotifyFailure.Network, (failure as SpotifyGatewayException).failure)
    }

    @Test fun `an unreachable access point is a network failure, not an expired session`() = runTest {
        // Regression: this used to be reported as AuthenticationExpired, which sent the user to the
        // sign-in screen even though the stored token was valid and only the access point refused.
        val native = FakeNative().apply { connectOk = false }
        val reasons = mutableListOf<String>()
        val gateway = NativeSpotifyGateway(native, onConnectFailure = { reasons += it }) { "token" }

        val failure = runCatching { gateway.connect("token") }.exceptionOrNull()

        assertEquals(SpotifyFailure.Network, (failure as SpotifyGatewayException).failure)
        assertEquals(listOf("SESSION_CONNECT_FAILED: test"), reasons)
    }

    @Test fun `concurrent catalog reads share one session`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "token" }

        coroutineScope {
            repeat(4) { launch(Dispatchers.IO) { gateway.ensureSession() } }
        }

        // One connect, not four: the racing calls used to rebuild the session under each other.
        assertEquals(1, native.connects)
        assertEquals("token", native.token)
    }

    @Test fun `offline load maps to Network`() = runTest {
        val native = FakeNative().apply { loadOk = false }
        val gateway = NativeSpotifyGateway(
            native,
            accessToken = { "token" },
            networkAvailable = { false }
        )

        val failure = runCatching { gateway.load("spotify:track:a", 0, true) }.exceptionOrNull()

        assertEquals(SpotifyFailure.Network, (failure as SpotifyGatewayException).failure)
    }

    @Test fun `offline ensureConnected fails before touching the native session`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(
            native,
            accessToken = { "token" },
            networkAvailable = { false }
        )

        val failure = runCatching { gateway.ensureConnected() }.exceptionOrNull()

        assertEquals(SpotifyFailure.Network, (failure as SpotifyGatewayException).failure)
        assertEquals(0, native.connects)
    }

    @Test fun `token refresh failure after the connection drops maps to Network`() = runTest {
        val native = FakeNative()
        var online = true
        val gateway = NativeSpotifyGateway(
            native,
            networkAvailable = { online },
            accessToken = {
                online = false
                null
            }
        )

        val failure = runCatching { gateway.ensureConnected() }.exceptionOrNull()

        assertEquals(SpotifyFailure.Network, (failure as SpotifyGatewayException).failure)
    }

    @Test fun `offline ensureSession maps to Network`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(
            native,
            accessToken = { "token" },
            networkAvailable = { false }
        )

        val failure = runCatching { gateway.ensureSession() }.exceptionOrNull()

        assertEquals(SpotifyFailure.Network, (failure as SpotifyGatewayException).failure)
    }

    @Test fun `ensureConnected uses the stored token`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "stored" }

        gateway.ensureConnected()

        assertEquals("stored", native.token)
    }

    @Test fun `player receives persisted bitrate`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(
            native,
            { PlaybackSettings(audioQuality = io.github.manuelrtsch.sidetrack.AudioQuality.Normal) }
        ) { "token" }

        gateway.connect("token")

        assertEquals(96, native.bitrateKbps)
    }

    @Test fun `player receives persisted audio processing settings`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(
            native,
            { PlaybackSettings(normalization = true, gapless = false) }
        ) { "token" }

        gateway.connect("token")

        assertTrue(native.normalization)
        assertEquals(false, native.gapless)
    }

    @Test fun `recreate player applies the current settings`() = runTest {
        val native = FakeNative()
        var settings = PlaybackSettings()
        val gateway = NativeSpotifyGateway(native, { settings }) { "token" }
        gateway.connect("token")
        settings = settings.copy(normalization = true)

        gateway.recreatePlayer()

        assertTrue(native.playerRecreated)
        assertTrue(native.normalization)
    }

    @Test fun `rotated token is pushed into the live session`() = runTest {
        val native = FakeNative()
        var token = "first"
        val gateway = NativeSpotifyGateway(native) { token }
        gateway.connect(token)
        token = "second"

        gateway.ensureConnected()

        assertEquals("second", native.token)
        assertEquals(1, native.connects)
    }

    @Test fun `dead native session reconnects even when the token has not changed`() = runTest {
        val native = FakeNative().apply { setTokenOk = false }
        val gateway = NativeSpotifyGateway(native) { "same" }
        gateway.connect("same")

        gateway.ensureConnected()

        assertEquals("same", native.token)
        assertEquals(2, native.connects)
    }

    @Test fun `dead native session reconnects from scratch`() = runTest {
        val native = FakeNative().apply { setTokenOk = false }
        var token = "first"
        val gateway = NativeSpotifyGateway(native) { token }
        gateway.connect(token)
        token = "second"

        gateway.ensureConnected()

        assertEquals("second", native.token)
        assertEquals(2, native.connects)
    }

    @Test fun `catalog session never rebuilds while a player is attached`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "token" }
        gateway.connect("token")
        assertTrue(native.playerCreated)
        // The session went invalid while the app sat idle. Rebuilding it from a catalog read used
        // to drop the player and leave playback reporting a playing track with no audio; the read
        // now fails instead and leaves the rebuild to the playback path.
        native.setTokenOk = false

        val failure = runCatching { gateway.ensureSession() }.exceptionOrNull()

        assertEquals(SpotifyFailure.NotConnected, (failure as SpotifyGatewayException).failure)
        assertEquals(1, native.connects)
        assertTrue(native.playerCreated)
        // The playback path is allowed to rebuild: it recreates the player afterwards.
        assertTrue(gateway.ensureConnected())
        assertTrue(native.playerCreated)
        assertEquals(2, native.connects)
    }

    @Test fun `ensureConnected adopts a player left by another gateway instance`() = runTest {
        val native = FakeNative()
        NativeSpotifyGateway(native) { "token" }.connect("token")
        val fresh = NativeSpotifyGateway(native) { "token" }

        // The new instance's Kotlin flag starts false, but the native session still owns the
        // player. Reconnecting here would drop the live track for no reason.
        assertEquals(false, fresh.ensureConnected())

        assertEquals(1, native.connects)
        assertTrue(native.playerCreated)
    }

    @Test fun `ensureConnected recreates a player the engine lost`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "token" }
        gateway.connect("token")
        native.dropPlayer()

        assertTrue(gateway.ensureConnected())

        assertTrue(native.playerCreated)
        assertEquals(1, native.connects)
    }

    @Test fun `ensureConnected keeps a live player and only refreshes the token`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "token" }
        gateway.connect("token")

        assertEquals(false, gateway.ensureConnected())

        assertEquals(1, native.connects)
        assertTrue(native.playerCreated)
    }

    private class FakeNative(override val isAvailable: Boolean = true) : SpotifyNative {
        var token: String? = null
        var playerCreated = false
        var playerRecreated = false
        var preloaded: String? = null
        var loadOk = true
        var connectOk = true
        var setTokenOk = true
        var bitrateKbps: Int? = null
        var normalization = false
        var gapless = true
        var connects = 0

        /** Simulates the engine dropping the player without telling the Kotlin side. */
        fun dropPlayer() {
            playerCreated = false
        }

        override fun initialize() = isAvailable
        override fun setTmpDir(path: String) = Unit
        override fun setCacheDir(path: String, sizeLimitBytes: Long) = Unit
        override fun connect(accessToken: String): String? {
            connects++
            token = accessToken
            // Model the engine: a new session cannot keep the player created for the old one.
            playerCreated = false
            return if (connectOk && isAvailable) null else "SESSION_CONNECT_FAILED: test"
        }
        override fun setAccessToken(accessToken: String): Boolean {
            if (setTokenOk) token = accessToken
            return setTokenOk && isAvailable
        }
        override fun disconnect() = Unit
        override fun hasPlayer() = playerCreated
        override fun createPlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean {
            playerCreated = true
            this.bitrateKbps = bitrateKbps
            this.normalization = normalization
            this.gapless = gapless
            return isAvailable
        }
        override fun recreatePlayer(bitrateKbps: Int, normalization: Boolean, gapless: Boolean): Boolean {
            playerRecreated = true
            this.bitrateKbps = bitrateKbps
            this.normalization = normalization
            this.gapless = gapless
            return isAvailable
        }
        override fun preload(uri: String): Boolean {
            preloaded = uri
            return isAvailable
        }
        override fun load(uri: String, positionMs: Long, autoplay: Boolean) = loadOk
        override fun play() = isAvailable
        override fun pause() = isAvailable
        override fun stop() = isAvailable
        override fun seek(positionMs: Long) = isAvailable
        override fun readAudio(timeoutMs: Long): ByteArray? = null
        override fun audioFormat(): String? = null
        override fun pollEvent(): String? = null
        override fun drainAudio() = Unit
        override fun catalogLibrary(filter: String, offset: Long, limit: Long): String? = null
        override fun catalogCollection(kind: String, id: String, offset: Long, limit: Long): String? = null
        override fun catalogArtist(id: String): String? = null
        override fun catalogAlbumsBy(id: String, offset: Long, limit: Long): String? = null
        override fun catalogTopTracks(id: String, offset: Long, limit: Long): String? = null
        override fun catalogSongRadio(id: String, limit: Long): String? = null
        override fun catalogArtistRadio(id: String, limit: Long): String? = null
        override fun catalogSearch(query: String, scopes: String, offset: Long, limit: Long): String? = null
        override fun collectionContains(set: String, uris: String): String? = null
        override fun collectionWrite(set: String, uris: String, removed: Boolean): Boolean = true
        override fun playlistSaved(id: String): String? = null
        override fun playlistSave(id: String, save: Boolean): Boolean = true
        override fun playlistItemsAdd(id: String, uris: String): String? = "0"
        override fun playlistItemsRemove(id: String, uris: String): String? = "ok"
    }
}
