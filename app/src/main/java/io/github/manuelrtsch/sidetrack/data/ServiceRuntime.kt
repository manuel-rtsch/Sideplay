package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.flow.StateFlow

// The repositories are service-neutral (they key everything by MediaKey); the Spotify names are
// historical. These aliases keep the runtime contract free of provider names.
typealias QueueRepository = SpotifyQueueRepository
typealias HistoryRepository = SpotifyHistoryRepository
typealias EpisodeProgressRepository = SpotifyEpisodeProgressRepository
typealias EpisodeMetaRepository = SpotifyEpisodeMetaRepository
typealias ResumePoint = SpotifyResumePoint

/**
 * Everything the UI needs from one signed-in music service.
 *
 * A runtime owns the service's catalog, writer, queue, history, settings and playback controller.
 * [AppSession] keeps one runtime per installed service and exposes the active one, so the UI never
 * reaches into a provider-specific session. Spotify's runtime is [SpotifySession]; a Navidrome
 * runtime implements the same contract.
 *
 * The core is deliberately small. Provider-specific abilities live in the optional interfaces
 * below, so a service that has no native player, no quality settings or no cache does not have to
 * pretend it does.
 */
interface ServiceRuntime {
    val service: ServiceId
    val capabilities: ServiceCapabilities

    val catalog: MediaCatalog
    val writer: MediaLibraryWriter?

    val queue: QueueRepository
    val history: HistoryRepository
    val settings: StateFlow<PlaybackSettings>
    val playback: PlaybackController

    val signedIn: StateFlow<Boolean>
    val network: NetworkMonitor?
    val isOnline: Boolean
    val resumePoint: ResumePoint?
    val episodeProgress: EpisodeProgressRepository
    val episodeMeta: EpisodeMetaRepository

    suspend fun signIn(input: AuthInput)
    fun signOut()

    /** Resolves the item's playback source and starts it. */
    suspend fun play(item: MediaItem, positionMs: Long = 0L, gapless: Boolean = false)
    suspend fun resume(): Boolean
    suspend fun seek(positionMs: Long)
    fun pause()
    fun togglePlayback()
    fun stopPlayback()
    fun haltPlayback()

    fun setAutoplay(enabled: Boolean)
    fun clearCaches()
}

/** A runtime backed by a native player that can report its own availability and halt state. */
interface NativePlaybackRuntime {
    val nativeAvailable: Boolean
    val halting: Boolean
    fun notePlayRequest(id: Long, uri: String?)
    fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean
}

/** A runtime that exposes audio-quality and DSP preferences. */
interface QualitySettingsRuntime {
    fun setAudioQuality(quality: AudioQuality)
    fun setNormalization(enabled: Boolean)
    fun setGapless(enabled: Boolean)
}

/** A runtime with a local audio cache. */
interface CacheRuntime {
    fun cacheSizeBytes(): Long
    fun clearAudioCache()
}

// The UI asks the runtime these questions without caring which optional interface it implements.
// A service that does not implement one gets the neutral answer instead of a no-op override.

val ServiceRuntime.nativeAvailable: Boolean
    get() = (this as? NativePlaybackRuntime)?.nativeAvailable ?: false

val ServiceRuntime.halting: Boolean
    get() = (this as? NativePlaybackRuntime)?.halting ?: false

fun ServiceRuntime.notePlayRequest(id: Long, uri: String?) {
    (this as? NativePlaybackRuntime)?.notePlayRequest(id, uri)
}

fun ServiceRuntime.consumeHaltStop(uri: String?, playRequestId: Long): Boolean = (this as? NativePlaybackRuntime)?.consumeHaltStop(uri, playRequestId) ?: false

fun ServiceRuntime.setAudioQuality(quality: AudioQuality) {
    (this as? QualitySettingsRuntime)?.setAudioQuality(quality)
}

fun ServiceRuntime.setNormalization(enabled: Boolean) {
    (this as? QualitySettingsRuntime)?.setNormalization(enabled)
}

fun ServiceRuntime.setGapless(enabled: Boolean) {
    (this as? QualitySettingsRuntime)?.setGapless(enabled)
}

fun ServiceRuntime.cacheSizeBytes(): Long = (this as? CacheRuntime)?.cacheSizeBytes() ?: 0L

fun ServiceRuntime.clearAudioCache() {
    (this as? CacheRuntime)?.clearAudioCache()
}
