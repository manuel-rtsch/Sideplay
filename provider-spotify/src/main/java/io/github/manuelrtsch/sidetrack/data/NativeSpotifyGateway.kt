package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.SidetrackDebug
import io.github.manuelrtsch.sidetrack.bridge.SpotifyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SpotifyGateway] backed by librespot. Every JNI call is blocking and therefore runs on IO.
 * Native code returns only success/failure; stable failure mapping happens here.
 */
class NativeSpotifyGateway(
    private val native: SpotifyNative,
    private val settings: () -> PlaybackSettings = { PlaybackSettings() },
    private val networkAvailable: () -> Boolean = { true },
    private val accessToken: suspend () -> String?
) : SpotifyGateway {
    /** True when a player is attached to the live session; see [ensureConnected] for why that is
     * not the same as "the session is connected". */
    @Volatile
    private var connected = false

    @Volatile
    private var sessionConnected = false

    @Volatile
    private var sessionToken: String? = null

    override suspend fun connect(accessToken: String) = onIo {
        requireNative()
        // `connected` alone is not enough: a session rebuilt by the catalog path drops the player
        // without touching the flag, and returning here would leave playback without one.
        if (connected && native.hasPlayer()) return@onIo
        // A session that is invalid, or was opened with a different OAuth token, must be rebuilt
        // before a player is attached to it. Otherwise the player would load tracks through a
        // dead access-point connection and every load would fail.
        if (sessionConnected && !native.setAccessToken(accessToken)) {
            sessionConnected = false
            sessionToken = null
        }
        connectSession(accessToken)
        createPlayer()
    }

    override suspend fun disconnect() = onIo {
        connected = false
        sessionConnected = false
        sessionToken = null
        if (native.isAvailable) native.disconnect()
    }

    override suspend fun recreatePlayer(): Boolean = onIo {
        requireNative()
        // No player yet: the next connect creates one with the new settings.
        if (!connected) return@onIo false
        val current = settings()
        if (!native.recreatePlayer(current.bitrateKbps, current.normalization, current.gapless)) {
            connected = false
            fail(SpotifyFailure.Unknown, "Could not update the player")
        }
        true
    }

    override suspend fun preload(uri: String) = onIo {
        if (connected && native.isAvailable) native.preload(uri)
    }

    override suspend fun load(uri: String, positionMs: Long, autoplay: Boolean) = onIo {
        requireNative()
        SidetrackDebug.log("gateway.load begin uri=$uri positionMs=$positionMs autoplay=$autoplay")
        val started = System.nanoTime()
        val ok = native.load(uri, positionMs, autoplay)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        SidetrackDebug.log("gateway.load native returned ok=$ok elapsedMs=$elapsedMs")
        if (!ok) {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.TrackUnavailable, "Track unavailable")
        }
    }

    override suspend fun play() = onIo {
        requireNative()
        if (!native.play()) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    override suspend fun pause() = onIo {
        requireNative()
        if (!native.pause()) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    override suspend fun stop() = onIo {
        requireNative()
        if (!native.stop()) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    override suspend fun seek(positionMs: Long) = onIo {
        requireNative()
        if (!native.seek(positionMs)) fail(SpotifyFailure.NotConnected, "Playback is not ready")
    }

    /** Forgets the native session after it has been torn down outside this gateway. */
    fun reset() {
        connected = false
        sessionConnected = false
        sessionToken = null
    }

    /**
     * Connects with the current stored token, refreshing it first when needed.
     *
     * `setAccessToken` is called on every attempt, not only when the token changed: librespot can
     * lose its access-point connection while the OAuth token is still valid, and a session that
     * has gone invalid must be rebuilt or playback fails for every track. The native call is a
     * cheap liveness check, so the extra round trip on each play is worth it. It also reports a
     * changed token as a rejection: the live session holds the access point's reusable stored
     * credential, which login5 needs, so a refreshed OAuth token must rebuild the session rather
     * than overwrite that credential.
     *
     * The player is checked against the engine rather than trusted from [connected]: the catalog
     * path ([ensureSession]) rebuilds the session without creating a player, and rebuilding a
     * session always drops the existing one. Without that check every track would fail with
     * PLAYER_NOT_CREATED until the user restarted the app.
     */
    suspend fun ensureConnected(forceReconnect: Boolean = false): Boolean {
        if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
        val token = accessToken() ?: run {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.AuthenticationExpired, "Sign in again")
        }
        SidetrackDebug.log("ensureConnected connected=$connected sessionConnected=$sessionConnected tokenLength=${token.length} forceReconnect=$forceReconnect")
        if (forceReconnect) {
            // A stall can leave librespot's access-point connection dead without marking the
            // session invalid yet. Rebuilding is the only reliable way to get a fresh connection
            // before the keep-alive timeout would notice.
            SidetrackDebug.log("ensureConnected: forced reconnect")
            connected = false
            sessionConnected = false
            sessionToken = null
            connect(token)
            return true
        }
        if (!connected || !playerAttached()) {
            SidetrackDebug.log("ensureConnected: no player, connecting")
            connect(token)
            return true
        }
        val tokenAccepted = withContext(Dispatchers.IO) { native.setAccessToken(token) }
        SidetrackDebug.log("ensureConnected: setAccessToken accepted=$tokenAccepted")
        if (tokenAccepted) {
            sessionToken = token
            return false
        }
        SidetrackDebug.log("ensureConnected: session rejected token, rebuilding")
        connected = false
        sessionConnected = false
        sessionToken = null
        connect(token)
        return true
    }

    /** Connects the librespot session without creating an audio player. Used by catalog reads. */
    suspend fun ensureSession() = onIo {
        requireNative()
        if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
        val token = accessToken() ?: run {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.AuthenticationExpired, "Sign in again")
        }
        if (!sessionConnected) {
            connectSession(token)
        } else if (!native.setAccessToken(token)) {
            // The first call stores the new token. A second call therefore only reports a dead
            // session, not a token change: a refreshed token must not tear down a live player
            // from a catalog read, but an invalid session still has to be rebuilt.
            if (!native.setAccessToken(token)) {
                sessionConnected = false
                sessionToken = null
                connectSession(token)
            } else {
                sessionToken = token
            }
        } else {
            sessionToken = token
        }
    }

    private fun connectSession(token: String) {
        if (sessionConnected) return
        SidetrackDebug.log("connectSession begin")
        if (!native.connect(token)) {
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.AuthenticationExpired, "Could not connect")
        }
        SidetrackDebug.log("connectSession connected")
        sessionConnected = true
        sessionToken = token
        // The player belongs to the session it was created with, so the native connect above
        // dropped it. Clearing the flag matters for the catalog path ([ensureSession]), which
        // connects without creating a player: leaving it set would make [ensureConnected] skip
        // the player and every load would fail with PLAYER_NOT_CREATED.
        if (connected) SidetrackDebug.log("connectSession: previous player dropped")
        connected = false
    }

    /** Native truth, so a session rebuilt outside the playback path cannot leave [connected] stale. */
    private suspend fun playerAttached(): Boolean = withContext(Dispatchers.IO) { native.hasPlayer() }

    private fun createPlayer() {
        val current = settings()
        SidetrackDebug.log("createPlayer bitrateKbps=${current.bitrateKbps} normalization=${current.normalization} gapless=${current.gapless}")
        if (!native.createPlayer(current.bitrateKbps, current.normalization, current.gapless)) {
            SidetrackDebug.log("createPlayer failed")
            native.disconnect()
            sessionConnected = false
            sessionToken = null
            fail(SpotifyFailure.Unknown, "Could not create the player")
        }
        SidetrackDebug.log("createPlayer created")
        connected = true
    }

    private fun requireNative() {
        if (!native.isAvailable) fail(SpotifyFailure.Unavailable, "Spotify playback is unavailable in this build")
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun fail(failure: SpotifyFailure, message: String): Nothing = throw SpotifyGatewayException(failure, message)
}
