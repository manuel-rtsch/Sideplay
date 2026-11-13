package io.github.manuelrtsch.sideplay.data

/**
 * Playback side of the Spotify connection. Catalog reads stay on [SpotifyCatalog]; this interface
 * is deliberately transport-free so a fake can drive UI/playback tests and
 * `NativeSpotifyGateway` can wrap JNI behind the same contract.
 */
interface SpotifyGateway {
    suspend fun connect(accessToken: String)
    suspend fun disconnect()

    /** Rebuilds the native player from the current settings; false when no player exists yet. */
    suspend fun recreatePlayer(): Boolean

    /** Best-effort warm-up of the next track so a gapless transition has audio ready. */
    suspend fun preload(uri: String)
    suspend fun load(uri: String, positionMs: Long, autoplay: Boolean)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionMs: Long)
}

/** Stable gateway failures. Raw librespot strings must not reach the UI. */
enum class SpotifyFailure {
    Unavailable,
    NotConnected,
    AuthenticationExpired,
    Network,
    RateLimited,
    TrackUnavailable,
    Unknown
}

class SpotifyGatewayException(
    val failure: SpotifyFailure,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
