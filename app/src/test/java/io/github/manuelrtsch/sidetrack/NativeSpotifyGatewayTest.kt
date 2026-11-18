package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import io.github.manuelrtsch.sidetrack.data.NativeSpotifyGateway
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifySettings
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
            { SpotifySettings(audioQuality = io.github.manuelrtsch.sidetrack.AudioQuality.Normal) }
        ) { "token" }

        gateway.connect("token")

        assertEquals(96, native.bitrateKbps)
    }

    @Test fun `player receives persisted audio processing settings`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(
            native,
            { SpotifySettings(normalization = true, gapless = false) }
        ) { "token" }

        gateway.connect("token")

        assertTrue(native.normalization)
        assertEquals(false, native.gapless)
    }

    @Test fun `recreate player applies the current settings`() = runTest {
        val native = FakeNative()
        var settings = SpotifySettings()
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

    @Test fun `catalog session rebuild leaves playback without a player until the next play`() = runTest {
        val native = FakeNative()
        val gateway = NativeSpotifyGateway(native) { "token" }
        gateway.connect("token")
        assertTrue(native.playerCreated)
        // The session went invalid while the app sat idle, so a catalog read rebuilds it. The
        // engine drops the player with the old session and the catalog path does not create one.
        native.setTokenOk = false

        gateway.ensureSession()

        assertEquals(false, native.playerCreated)
        // The next play has to notice and create a player, otherwise every load fails with
        // PLAYER_NOT_CREATED ("Track unavailable") until the app is restarted.
        assertTrue(gateway.ensureConnected())
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
        override fun connect(accessToken: String): Boolean {
            connects++
            token = accessToken
            // Model the engine: a new session cannot keep the player created for the old one.
            playerCreated = false
            return connectOk && isAvailable
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
        override fun playlistItemsRemove(id: String, uris: String): Boolean = true
    }
}
