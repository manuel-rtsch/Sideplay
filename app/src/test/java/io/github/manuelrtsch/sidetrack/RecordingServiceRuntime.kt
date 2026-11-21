package io.github.manuelrtsch.sidetrack

import android.content.Context
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.EpisodeMetaRepository
import io.github.manuelrtsch.sidetrack.data.EpisodeProgressRepository
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.data.MusicServiceProvider
import io.github.manuelrtsch.sidetrack.data.NetworkMonitor
import io.github.manuelrtsch.sidetrack.data.PlaybackSettings
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceChoice
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import io.github.manuelrtsch.sidetrack.data.SignInKind
import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.Media3Player
import io.github.manuelrtsch.sidetrack.playback.Media3PlayerListener
import io.github.manuelrtsch.sidetrack.playback.Media3PlayerStatus
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A provider-neutral runtime that records the transport calls the app makes, so the app-level
 * owners (`AppSession`, the playback service) can be tested without a real provider or player.
 *
 * The state holders are the real ones: only the service's network side is stubbed.
 */
class RecordingServiceRuntime(
    override val service: ServiceId = ServiceId.Navidrome,
    val calls: MutableList<String> = mutableListOf(),
    val signedInFlow: MutableStateFlow<Boolean> = MutableStateFlow(false),
    /** Pass a context when the test exercises the notification, which needs a media session. */
    playbackContext: Context? = null
) : ServiceRuntime {
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
    override val catalog: MediaCatalog = SilentCatalog(capabilities)
    override val writer: MediaLibraryWriter? = null
    override val queue = QueueRepository()
    override val history = HistoryRepository()
    override val settings: StateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings())
    override val playback: PlaybackController = PlaybackController(
        Media3PlaybackEngine { SilentMedia3Player() },
        scope = CoroutineScope(Dispatchers.Unconfined),
        context = playbackContext
    )
    override val signedIn: StateFlow<Boolean> = signedInFlow
    override val network: NetworkMonitor? = null
    override val isOnline: Boolean = true
    override val resumePoint: ResumePoint? = null
    override val episodeProgress = EpisodeProgressRepository()
    override val episodeMeta = EpisodeMetaRepository()

    override suspend fun signIn(input: AuthInput) = Unit

    override fun signOut() = Unit

    override suspend fun play(item: MediaItem, positionMs: Long, gapless: Boolean) = Unit

    override suspend fun resume(): Boolean = false

    override suspend fun seek(positionMs: Long) = Unit

    override fun pause() {
        calls += "pause"
    }

    override fun togglePlayback() {
        calls += "toggle"
    }

    override fun stopPlayback() {
        calls += "stop"
    }

    override fun haltPlayback() {
        calls += "halt"
    }

    override fun setAutoplay(enabled: Boolean) = Unit

    override fun clearCaches() = Unit
}

/** A provider whose sign-in never runs; the app-level owners do not touch it. */
class RecordingProvider(private val choice: ServiceChoice) : MusicServiceProvider {
    override val id: ServiceId = choice.id
    override val displayName: String = choice.displayName
    override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic
    override val signInKind: SignInKind = SignInKind.Browser

    override fun createAuth(): ServiceAuth = object : ServiceAuth {
        override suspend fun signIn(input: AuthInput): ServiceAccount = ServiceAccount(choice.id, userId = "user", displayName = "User")
        override suspend fun restore(): ServiceAccount? = null
        override suspend fun signOut() = Unit
    }
}

/** A catalog with no content. Every page is empty and no call fails. */
class SilentCatalog(override val capabilities: ServiceCapabilities = ServiceCapabilities.Subsonic) : MediaCatalog {
    override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = Page()

    override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? = null

    override suspend fun artist(key: MediaKey): Artist? = null

    override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> = Page()

    override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> = Page()

    override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults = SearchResults()

    override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource = PlaybackSource(id = item.key.encode(), uri = item.uri.orEmpty())
}

/** A player that never produces audio or events; only its command surface is exercised. */
class SilentMedia3Player : Media3Player {
    override var listener: Media3PlayerListener? = null

    override fun prepare(source: PlaybackSource, startPositionMs: Long, playWhenReady: Boolean) = Unit

    override fun play() = Unit

    override fun pause() = Unit

    override fun stop() = Unit

    override fun seekTo(positionMs: Long) = Unit

    override fun release() = Unit

    override val isPlaying: Boolean = false
    override val currentPositionMs: Long = 0L
    override val status: Media3PlayerStatus = Media3PlayerStatus.Idle
}
