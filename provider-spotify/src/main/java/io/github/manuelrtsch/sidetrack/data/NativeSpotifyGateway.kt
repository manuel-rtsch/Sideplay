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
    /** Receives the transport's own reason for a failed connect. Developer-facing, never shown. */
    private val onConnectFailure: (String) -> Unit = {},
    private val accessToken: suspend () -> String?
) : SpotifyGateway {
    /**
     * Serialises everything that changes the native session.
     *
     * Catalog reads run in parallel: on sign-in the library tabs each ask for their own page, and
     * every one of them called `connect` when no session existed yet. `engine.connect` starts with
     * `disconnect`, so two of them racing tore down the session the other was still authenticating.
     * A monitor is the right tool rather than a `Mutex`: every call guarded here is blocking JNI on
     * an IO thread and never suspends, so nothing can deadlock on a suspended holder.
     */
    private val sessionLock = Any()

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
        synchronized(sessionLock) {
            // `connected` alone is not enough: a session rebuilt by the catalog path drops the
            // player without touching the flag, and returning here would leave playback without
            // one. Re-checking under the lock also means a second caller that waited here finds
            // the session the first one built instead of rebuilding it.
            if (connected && native.hasPlayer()) return@onIo
            // A session that is invalid, or was opened with a different OAuth token, must be
            // rebuilt before a player is attached to it. Otherwise the player would load tracks
            // through a dead access-point connection and every load would fail.
            if (sessionConnected && !native.setAccessToken(accessToken)) {
                sessionConnected = false
                sessionToken = null
            }
            connectSessionLocked(accessToken)
            createPlayerLocked()
        }
    }

    override suspend fun disconnect() = onIo {
        synchronized(sessionLock) {
            connected = false
            sessionConnected = false
            sessionToken = null
            if (native.isAvailable) native.disconnect()
        }
    }

    override suspend fun recreatePlayer(): Boolean = onIo {
        requireNative()
        synchronized(sessionLock) {
            // No player yet: the next connect creates one with the new settings.
            if (!connected) return@onIo false
            val current = settings()
            if (!native.recreatePlayer(current.bitrateKbps, current.normalization, current.gapless)) {
                connected = false
                fail(SpotifyFailure.Unknown, "Could not update the player")
            }
            true
        }
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
        synchronized(sessionLock) {
            connected = false
            sessionConnected = false
            sessionToken = null
        }
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
        if (!connected) {
            // A player is process-wide while `connected` is this gateway's memory. After a
            // runtime/gateway rebuild the flag says "nothing here" even though the native
            // session still owns a player; adopting it avoids rebuilding the session (and
            // dropping the current track) for nothing.
            if (playerAttached()) {
                SidetrackDebug.log("ensureConnected: adopting the attached player")
                connected = true
            } else {
                SidetrackDebug.log("ensureConnected: no player, connecting")
                connect(token)
                return true
            }
        } else if (!playerAttached()) {
            SidetrackDebug.log("ensureConnected: player lost, connecting")
            connect(token)
            return true
        }
        val tokenAccepted = withContext(Dispatchers.IO) {
            synchronized(sessionLock) { native.setAccessToken(token) }
        }
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
        synchronized(sessionLock) {
            if (!sessionConnected) {
                refuseToDropPlayer("the session is missing")
                connectSessionLocked(token)
                return@synchronized
            }
            // The first call stores the new token. A second call therefore only reports a dead
            // session, not a token change: a refreshed token must not tear down a live player
            // from a catalog read, but an invalid session still has to be rebuilt.
            if (native.setAccessToken(token)) {
                sessionToken = token
                return@synchronized
            }
            if (native.setAccessToken(token)) {
                sessionToken = token
                return@synchronized
            }
            refuseToDropPlayer("the session is invalid")
            sessionConnected = false
            sessionToken = null
            connectSessionLocked(token)
        }
    }

    /**
     * A catalog read must never tear down a session that is playing.
     *
     * Rebuilding the session always drops the player, and the catalog path does not create a new
     * one. When that happens while audio is running, the app keeps reporting a playing track while
     * delivering silence (the 20:45:47 stall). Fail this read instead; the playback path owns the
     * rebuild, recreates the player and reloads the item.
     *
     * [sessionLock] must be held.
     */
    private fun refuseToDropPlayer(reason: String) {
        if (!native.hasPlayer()) return
        SidetrackDebug.log("ensureSession: $reason while a player is attached; not rebuilding")
        fail(SpotifyFailure.NotConnected, "Spotify session lost; playback will reconnect")
    }

    /** [sessionLock] must be held, so two catalog reads cannot rebuild the session at once. */
    private fun connectSessionLocked(token: String) {
        if (sessionConnected) return
        SidetrackDebug.log("connectSession begin")
        // A failure here happens before any request: librespot could not reach an access point.
        // Reporting it as an expired session sent the user to the sign-in screen for a network
        // problem, so the reason is logged and the failure is a network one.
        val reason = native.connect(token)
        if (reason != null) {
            SidetrackDebug.log("connectSession failed reason=$reason")
            onConnectFailure(reason)
            if (!networkAvailable()) fail(SpotifyFailure.Network, "No internet connection")
            fail(SpotifyFailure.Network, "Couldn't reach Spotify")
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

    /** [sessionLock] must be held. */
    private fun createPlayerLocked() {
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
