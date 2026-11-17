package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Compile-time proof that a non-Spotify service can satisfy [ServiceRuntime] without inheriting
 * librespot, quality settings or a native player.
 *
 * This is intentionally not registered in [AppSessions] yet. The Navidrome adapter fills in the
 * catalog, writer and auth; the playback path below is already the neutral one: the catalog
 * resolves a URL and headers, and the shared [PlaybackController] hands them to the engine.
 */
class NavidromeRuntime(
    override val catalog: MediaCatalog,
    override val writer: MediaLibraryWriter?,
    override val playback: PlaybackController,
    override val queue: QueueRepository,
    override val history: HistoryRepository,
    override val settings: StateFlow<PlaybackSettings>,
    override val signedIn: StateFlow<Boolean>,
    override val episodeProgress: EpisodeProgressRepository,
    override val episodeMeta: EpisodeMetaRepository,
    override val network: NetworkMonitor? = null,
    override val resumePoint: ResumePoint? = null,
    private val scope: CoroutineScope
) : ServiceRuntime {
    override val service: ServiceId = ServiceId.Navidrome
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Navidrome
    override val isOnline: Boolean get() = network?.isOnline ?: true

    override suspend fun signIn(input: AuthInput) = TODO("Navidrome auth")

    override fun signOut() = TODO("Navidrome auth")

    override suspend fun play(item: MediaItem, positionMs: Long, gapless: Boolean) {
        val source = catalog.resolvePlaybackSource(item)
        playback.start(item.key, source.uri, positionMs, headers = source.headers)
    }

    override suspend fun resume(): Boolean = playback.resume()

    override suspend fun seek(positionMs: Long) = playback.seek(positionMs)

    override fun pause() = playback.pause()

    override fun togglePlayback() = playback.togglePlayPause()

    override fun stopPlayback() {
        scope.launch { playback.stop() }
    }

    override fun haltPlayback() {
        scope.launch { playback.stop() }
    }

    override fun setAutoplay(enabled: Boolean) = Unit

    override fun clearCaches() = Unit
}
