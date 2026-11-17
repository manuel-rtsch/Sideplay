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
    val nativeAvailable: Boolean
    val isOnline: Boolean
    val halting: Boolean
    val resumePoint: ResumePoint?
    val episodeProgress: EpisodeProgressRepository
    val episodeMeta: EpisodeMetaRepository

    suspend fun signIn(openBrowser: suspend (String) -> Unit)
    fun signOut()

    /** Resolves the item's playback source and starts it. */
    suspend fun play(item: MediaItem, positionMs: Long = 0L, gapless: Boolean = false)
    suspend fun resume(): Boolean
    suspend fun seek(positionMs: Long)
    fun pause()
    fun togglePlayback()
    fun stopPlayback()
    fun haltPlayback()

    fun setAudioQuality(quality: AudioQuality)
    fun setNormalization(enabled: Boolean)
    fun setGapless(enabled: Boolean)
    fun setAutoplay(enabled: Boolean)

    fun cacheSizeBytes(): Long
    fun clearAudioCache()
    fun clearCaches()

    fun notePlayRequest(id: Long, uri: String?)
    fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean
}
