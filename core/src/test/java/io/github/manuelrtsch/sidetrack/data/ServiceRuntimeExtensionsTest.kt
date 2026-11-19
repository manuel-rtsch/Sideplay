package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.AudioQuality
import io.github.manuelrtsch.sidetrack.LibraryEntry
import io.github.manuelrtsch.sidetrack.LibrarySection
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.MediaKey
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.ServiceCapabilities
import io.github.manuelrtsch.sidetrack.ServiceId
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI asks every runtime the same questions, so a service that does not implement an optional
 * interface must answer the neutral value instead of crashing on a cast. These are the seams the
 * ViewModel and settings screens rely on.
 */
class ServiceRuntimeExtensionsTest {
    @Test fun `a runtime without native playback reports no native player`() {
        assertFalse(fake().nativeAvailable)
    }

    @Test fun `a runtime without native playback never halts`() {
        assertFalse(fake().halting)
    }

    @Test fun `noting a play request on a runtime without native playback is a no-op`() {
        fake().notePlayRequest(id = 3L, uri = "spotify:track:x")
    }

    @Test fun `a runtime without native playback never consumes a halt stop`() {
        assertFalse(fake().consumeHaltStop(uri = "spotify:track:x", playRequestId = 3L))
    }

    @Test fun `quality setters on a runtime without them are no-ops`() {
        val runtime = fake()

        runtime.setAudioQuality(AudioQuality.VeryHigh)
        runtime.setNormalization(enabled = true)
        runtime.setGapless(enabled = false)
    }

    @Test fun `a runtime without a cache reports an empty cache`() {
        assertEquals(0L, fake().cacheSizeBytes())
    }

    @Test fun `clearing the cache on a runtime without one is a no-op`() {
        fake().clearAudioCache()
    }

    @Test fun `native playback answers are forwarded to the runtime`() {
        val runtime = NativeRuntime()

        assertTrue(runtime.nativeAvailable)
        assertTrue(runtime.halting)

        runtime.notePlayRequest(id = 7L, uri = "spotify:track:x")

        assertTrue(runtime.consumeHaltStop(uri = "spotify:track:x", playRequestId = 7L))
        assertEquals(listOf(7L to "spotify:track:x"), runtime.requests)
    }

    @Test fun `quality setters are forwarded to the runtime`() {
        val runtime = QualityRuntime()

        runtime.setAudioQuality(AudioQuality.Normal)
        runtime.setNormalization(enabled = true)
        runtime.setGapless(enabled = false)

        assertEquals(listOf("quality:Normal", "normalization:true", "gapless:false"), runtime.calls)
    }

    @Test fun `cache answers are forwarded to the runtime`() {
        val runtime = CacheOnlyRuntime()

        assertEquals(4_096L, runtime.cacheSizeBytes())
        runtime.clearAudioCache()

        assertTrue(runtime.cleared)
    }

    private fun fake() = FakeRuntime()

    /** A runtime with no optional interfaces, as a Jellyfin or Navidrome install sees it. */
    private open class FakeRuntime : ServiceRuntime {
        override val service: ServiceId = ServiceId.Local
        override val capabilities: ServiceCapabilities = ServiceCapabilities.Navidrome
        override val catalog: MediaCatalog = EmptyCatalog()
        override val writer: MediaLibraryWriter? = null
        override val queue = QueueRepository()
        override val history = HistoryRepository()
        override val settings: StateFlow<PlaybackSettings> = MutableStateFlow(PlaybackSettings())
        override val playback: PlaybackController = PlaybackController(
            Media3PlaybackEngine { NullPlayer() },
            scope = CoroutineScope(Dispatchers.Unconfined)
        )
        override val signedIn: StateFlow<Boolean> = MutableStateFlow(false)
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
        override fun pause() = Unit
        override fun togglePlayback() = Unit
        override fun stopPlayback() = Unit
        override fun haltPlayback() = Unit
        override fun setAutoplay(enabled: Boolean) = Unit
        override fun clearCaches() = Unit
    }

    private class NativeRuntime :
        FakeRuntime(),
        NativePlaybackRuntime {
        override val nativeAvailable: Boolean = true
        override val halting: Boolean = true
        val requests = mutableListOf<Pair<Long, String?>>()

        override fun notePlayRequest(id: Long, uri: String?) {
            requests += id to uri
        }

        override fun consumeHaltStop(uri: String?, playRequestId: Long): Boolean = true
    }

    private class QualityRuntime :
        FakeRuntime(),
        QualitySettingsRuntime {
        val calls = mutableListOf<String>()

        override fun setAudioQuality(quality: AudioQuality) {
            calls += "quality:${quality.name}"
        }

        override fun setNormalization(enabled: Boolean) {
            calls += "normalization:$enabled"
        }

        override fun setGapless(enabled: Boolean) {
            calls += "gapless:$enabled"
        }
    }

    private class CacheOnlyRuntime :
        FakeRuntime(),
        CacheRuntime {
        var cleared = false

        override fun cacheSizeBytes(): Long = 4_096L

        override fun clearAudioCache() {
            cleared = true
        }
    }

    private class EmptyCatalog : MediaCatalog {
        override val capabilities: ServiceCapabilities = ServiceCapabilities.Navidrome

        override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> = Page()

        override suspend fun collection(key: MediaKey, offset: Int, limit: Int): io.github.manuelrtsch.sidetrack.Collection? = null

        override suspend fun artist(key: MediaKey): io.github.manuelrtsch.sidetrack.Artist? = null

        override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<io.github.manuelrtsch.sidetrack.Album> = Page()

        override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<io.github.manuelrtsch.sidetrack.Track> = Page()

        override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults = SearchResults()

        override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource = PlaybackSource(id = item.key.encode(), uri = item.uri.orEmpty())
    }

    /** No player is ever prepared in these tests; the engine only needs a factory. */
    private class NullPlayer : Media3Player {
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
}
