package io.github.manuelrtsch.sidetrack.playback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject

/**
 * A playable reference handed to a [PlaybackEngine].
 *
 * A service may use its canonical URI as both [id] and [uri]. HTTP services put a stream URL in
 * [uri] and keep their own stable id in [id]; [headers] is reserved for authenticated streams.
 */
data class PlaybackSource(
    val id: String,
    val uri: String,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Engine-owned playback state. The controller mirrors the parts the UI needs, but the engine is
 * the source of truth for whether audio is running and where it is.
 */
data class PlaybackEngineState(
    val source: PlaybackSource? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val isBuffering: Boolean = false
)

/**
 * Transport contract shared by the librespot engine and the future Media3 engine.
 *
 * The engine owns the audio pipeline and the player commands. It does not own the platform media
 * session, the notification, audio focus or the sleep timer; those stay in [PlaybackController].
 */
interface PlaybackEngine {
    val state: StateFlow<PlaybackEngineState>

    /**
     * End-of-track notifications for observers that do not own the [pollEvent] queue.
     *
     * The queue is single-consumer: the ViewModel drains it to advance playback. A provider that
     * has to tell the server an item finished (Jellyfin's "played" flag, decision D12) needs the
     * same signal without taking events away from the queue owner. Engines that have no such
     * observer leave the default, empty flow in place.
     */
    val ended: Flow<String?> get() = emptyFlow()

    /** Installed by the controller; the engine reports a dead audio pipeline here. */
    var onFailure: (Throwable) -> Unit

    /** Starts [source] at [positionMs]. [keepAudioSink] is the gapless transition hint. */
    suspend fun start(source: PlaybackSource, positionMs: Long, keepAudioSink: Boolean = false)

    /** Resumes the current source. Returns false when the engine could not start audio. */
    suspend fun resume(positionMs: Long): Boolean

    suspend fun pause()
    suspend fun stop()
    suspend fun seek(positionMs: Long)

    /** Best-effort warm-up of the next source. */
    suspend fun preload(source: PlaybackSource)

    /** Rebuilds the player after a settings change and reloads [source] when present. */
    suspend fun reconfigure(source: PlaybackSource?, positionMs: Long, wasPlaying: Boolean): Boolean

    suspend fun disconnect()

    /** One engine event, or null when nothing has happened since the last poll. */
    suspend fun pollEvent(): PlaybackEvent?

    /**
     * Synchronously invalidates a start/resume that is still in flight. The controller calls
     * this before launching an asynchronous pause/stop so a pending command cannot start audio
     * after the user already paused.
     */
    fun invalidateCommands()

    /** Keeps the engine's state in step with the UI clock without touching the player. */
    fun updatePosition(positionMs: Long)

    /** Mirrors a player event into the engine state. */
    fun updateState(isPlaying: Boolean, positionMs: Long)
}

/**
 * Typed playback event. The librespot engine parses its private JSON protocol into this contract;
 * the ViewModel never sees raw JSON.
 */
sealed interface PlaybackEvent {
    val uri: String?
    val playRequestId: Long

    data class Loading(
        override val uri: String?,
        val positionMs: Long?,
        override val playRequestId: Long = -1L
    ) : PlaybackEvent

    data class Playing(
        override val uri: String?,
        val positionMs: Long?,
        override val playRequestId: Long = -1L
    ) : PlaybackEvent

    data class Paused(
        override val uri: String?,
        val positionMs: Long?,
        override val playRequestId: Long = -1L
    ) : PlaybackEvent

    data class Position(val positionMs: Long) : PlaybackEvent {
        override val uri: String? = null
        override val playRequestId: Long = -1L
    }

    data class Seeked(val positionMs: Long) : PlaybackEvent {
        override val uri: String? = null
        override val playRequestId: Long = -1L
    }

    data class PositionCorrection(val positionMs: Long) : PlaybackEvent {
        override val uri: String? = null
        override val playRequestId: Long = -1L
    }

    data class Shuffle(val enabled: Boolean) : PlaybackEvent {
        override val uri: String? = null
        override val playRequestId: Long = -1L
    }

    data class Repeat(val track: Boolean, val context: Boolean) : PlaybackEvent {
        override val uri: String? = null
        override val playRequestId: Long = -1L
    }

    data class Ended(override val uri: String?) : PlaybackEvent {
        override val playRequestId: Long = -1L
    }

    data class Stopped(
        override val uri: String?,
        override val playRequestId: Long = -1L
    ) : PlaybackEvent

    data class Unavailable(override val uri: String?) : PlaybackEvent {
        override val playRequestId: Long = -1L
    }

    data class Unknown(val type: String) : PlaybackEvent {
        override val uri: String? = null
        override val playRequestId: Long = -1L
    }

    companion object {
        /** Parses the librespot event protocol. Unknown or malformed payloads become [Unknown]. */
        fun fromJson(raw: String): PlaybackEvent {
            val event = runCatching { JSONObject(raw) }.getOrNull()
                ?: return Unknown("malformed")
            val type = event.opt("type")?.toString().orEmpty()
            val uri = event.opt("uri")?.toString()?.takeIf { it.isNotEmpty() && it != "null" }
            val playRequestId = event.optLong("playRequestId", -1L)
            val position = event.optLongOrNull("positionMs")
            return when (type) {
                "loading" -> Loading(uri, position, playRequestId)

                "playing" -> Playing(uri, position, playRequestId)

                "paused" -> Paused(uri, position, playRequestId)

                "position" -> Position(position ?: 0L)

                "seeked" -> Seeked(position ?: 0L)

                "positionCorrection" -> PositionCorrection(position ?: 0L)

                "shuffle" -> Shuffle(event.optBoolean("shuffle"))

                "repeat" -> Repeat(
                    track = event.optBoolean("track"),
                    context = event.optBoolean("context")
                )

                "ended" -> Ended(uri)

                "stopped" -> Stopped(uri, playRequestId)

                "unavailable" -> Unavailable(uri)

                else -> Unknown(type)
            }
        }

        private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
    }
}

/**
 * Stable name for diagnostics.
 *
 * R8 renames these nested classes in minified builds, so `event::class.simpleName` turns a useful
 * log line into `ed2`/`ad2` exactly when a field capture is being read. Keep the mapping here
 * instead of relying on an obfuscation rule; the sealed hierarchy makes this `when` exhaustive.
 */
val PlaybackEvent.debugName: String
    get() = when (this) {
        is PlaybackEvent.Loading -> "Loading"
        is PlaybackEvent.Playing -> "Playing"
        is PlaybackEvent.Paused -> "Paused"
        is PlaybackEvent.Position -> "Position"
        is PlaybackEvent.Seeked -> "Seeked"
        is PlaybackEvent.PositionCorrection -> "PositionCorrection"
        is PlaybackEvent.Shuffle -> "Shuffle"
        is PlaybackEvent.Repeat -> "Repeat"
        is PlaybackEvent.Ended -> "Ended"
        is PlaybackEvent.Stopped -> "Stopped"
        is PlaybackEvent.Unavailable -> "Unavailable"
        is PlaybackEvent.Unknown -> "Unknown(${type})"
    }
