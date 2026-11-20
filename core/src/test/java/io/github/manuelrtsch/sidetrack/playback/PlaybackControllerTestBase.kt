package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNotNull
import org.robolectric.Shadows.shadowOf

/**
 * Shared fixture for the controller's contract tests: a Robolectric application context, a
 * recording engine and the media-session registry, plus the audio-focus and item helpers every
 * test class needs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class PlaybackControllerTestBase {
    protected val context: Context = ApplicationProvider.getApplicationContext()
    protected val audio: AudioManager = context.getSystemService(AudioManager::class.java)

    protected lateinit var controller: PlaybackController
    protected var registry: MediaSessionRegistry = MediaSessionRegistry()

    protected fun TestScope.newController(
        engine: PlaybackEngine,
        context: Context? = null,
        elapsedTime: () -> Long = { 0L },
        notifyRefresh: (Context, PlaybackController) -> Unit = { _, _ -> },
        notifySync: (Context, PlaybackController) -> Unit = { _, _ -> }
    ): PlaybackController {
        registry = MediaSessionRegistry()
        return PlaybackController(
            engine = engine,
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            registry = registry,
            context = context,
            elapsedTime = elapsedTime,
            notifyRefresh = notifyRefresh,
            notifySync = notifySync
        ).also { controller = it }
    }

    protected fun focusResponse(response: Int) {
        shadowOf(audio).setNextFocusRequestResponse(response)
    }

    /**
     * Robolectric keeps the listener on its shadow request; the field name is a shadow detail, so
     * it is resolved by type rather than by name (same seam as AudioFocusTest).
     */
    protected fun focusListener(): AudioManager.OnAudioFocusChangeListener {
        val request = shadowOf(audio).lastAudioFocusRequest
        assertNotNull("no focus request was made", request)
        val field = request!!.javaClass.declaredFields.first {
            AudioManager.OnAudioFocusChangeListener::class.java.isAssignableFrom(it.type)
        }
        field.isAccessible = true
        return field.get(request) as AudioManager.OnAudioFocusChangeListener
    }

    protected fun track(uri: String? = "spotify:track:a") = Track(
        id = "a",
        title = "A",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L,
        uri = uri
    )

    protected class FakeEngine : PlaybackEngine {
        override val state = MutableStateFlow(PlaybackEngineState())
        override val ended = MutableStateFlow<String?>(null)
        override var onFailure: (Throwable) -> Unit = {}
        var event: PlaybackEvent? = null

        var starts = 0
        var resumes = 0
        var pauses = 0
        var stops = 0
        var seeks = 0
        var disconnects = 0
        var invalidations = 0
        var lastSource: PlaybackSource? = null
        var lastPosition = 0L
        var seekedTo: Long? = null
        var updatedPosition = 0L
        var updatedIsPlaying = false
        var resumeResult = true
        var reconfigureResult = true
        var reconfiguredSource: PlaybackSource? = null
        var reconfiguredPosition = 0L
        var reconfiguredWhilePlaying = false
        var failStart: Throwable? = null

        override suspend fun start(source: PlaybackSource, positionMs: Long, keepAudioSink: Boolean) {
            starts++
            lastSource = source
            lastPosition = positionMs
            failStart?.let { throw it }
        }

        override suspend fun resume(positionMs: Long): Boolean {
            resumes++
            lastPosition = positionMs
            return resumeResult
        }

        override suspend fun pause() {
            pauses++
        }

        override suspend fun stop() {
            stops++
        }

        override suspend fun seek(positionMs: Long) {
            seeks++
            seekedTo = positionMs
        }

        override suspend fun preload(source: PlaybackSource) = Unit

        override suspend fun reconfigure(
            source: PlaybackSource?,
            positionMs: Long,
            wasPlaying: Boolean
        ): Boolean {
            reconfiguredSource = source
            reconfiguredPosition = positionMs
            reconfiguredWhilePlaying = wasPlaying
            return reconfigureResult
        }

        override suspend fun disconnect() {
            disconnects++
        }

        override suspend fun pollEvent(): PlaybackEvent? = event

        override fun invalidateCommands() {
            invalidations++
            state.value = state.value.copy(isPlaying = false)
        }

        override fun updatePosition(positionMs: Long) {
            updatedPosition = positionMs
        }

        override fun updateState(isPlaying: Boolean, positionMs: Long) {
            updatedPosition = positionMs
            updatedIsPlaying = isPlaying
        }
    }
}
