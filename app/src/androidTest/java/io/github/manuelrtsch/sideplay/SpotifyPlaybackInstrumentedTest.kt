package io.github.manuelrtsch.sideplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.manuelrtsch.sideplay.data.SpotifyGateway
import io.github.manuelrtsch.sideplay.playback.MediaSessionOwner
import io.github.manuelrtsch.sideplay.playback.MediaSessionRegistry
import io.github.manuelrtsch.sideplay.playback.SpotifyPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotifyPlaybackInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Keeps playback tests from starting the real foreground service. */
    private val silentContext = object : ContextWrapper(context) {
        override fun startForegroundService(service: Intent): ComponentName? = null
    }

    @Test
    fun mediaSessionPublishesMetadataAndPauseState() {
        val gateway = FakeGateway()
        val playback = SpotifyPlayback(gateway, scope, MediaSessionRegistry(), context = silentContext)
        onMain {
            playback.setNowPlaying(track("Session track"), 12_000L)
            playback.updateState(true, 12_000L)
        }

        val controller = MediaController(silentContext, requireNotNull(playback.sessionToken))

        waitUntil { controller.playbackState?.state == PlaybackState.STATE_PLAYING }
        assertEquals("Session track", controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))
        assertTrue((controller.playbackState?.position ?: -1L) >= 12_000L)

        onMain { playback.pause() }

        waitUntil { controller.playbackState?.state == PlaybackState.STATE_PAUSED }
        assertFalse(playback.isActive)
        assertTrue(gateway.paused)
        onMain { playback.setNowPlaying(null) }
    }

    @Test
    fun activatingAnotherMediaOwnerPausesSideplay() {
        val gateway = FakeGateway()
        val registry = MediaSessionRegistry()
        val playback = SpotifyPlayback(gateway, scope, registry, context = silentContext)
        val music = FakeOwner("music")
        registry.register(music)
        onMain {
            playback.register()
            playback.setNowPlaying(track("Handoff"), 0L)
            playback.updateState(true, 0L)
        }
        assertTrue(playback.isActive)

        onMain {
            music.isActive = true
            registry.activate(music)
        }

        waitUntil { !playback.isActive }
        assertSame(music, registry.active)
        assertTrue(gateway.paused)
        onMain { playback.setNowPlaying(null) }
    }

    @Test
    fun headphoneUnplugPausesPlayback() {
        val gateway = FakeGateway()
        val playback = SpotifyPlayback(gateway, scope, MediaSessionRegistry(), context = silentContext)
        onMain {
            playback.setNowPlaying(track("Unplugged"), 0L)
            playback.updateState(true, 0L)
        }
        assertTrue(playback.isActive)

        // The platform protects ACTION_AUDIO_BECOMING_NOISY, so invoke the receiver's callback.
        onMain { playback.onAudioBecomingNoisy() }

        waitUntil { !playback.isActive }
        assertTrue(gateway.paused)
        onMain { playback.setNowPlaying(null) }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Timed out")
            Thread.sleep(20L)
        }
    }

    private fun track(title: String) = SpotifyTrack(
        id = "track-${title.hashCode()}",
        title = title,
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L,
        uri = "spotify:track:test"
    )

    private class FakeGateway : SpotifyGateway {
        @Volatile var paused = false

        override suspend fun connect(accessToken: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun recreatePlayer() = true
        override suspend fun preload(uri: String) = Unit
        override suspend fun load(uri: String, positionMs: Long, autoplay: Boolean) = Unit
        override suspend fun play() = Unit
        override suspend fun pause() {
            paused = true
        }

        override suspend fun stop() = Unit
        override suspend fun seek(positionMs: Long) = Unit
    }

    private class FakeOwner(override val sessionId: String) : MediaSessionOwner {
        override val notificationChannelId = "channel-$sessionId"
        override val notificationChannelName = "Channel $sessionId"
        override val notificationId = sessionId.hashCode()
        override var isActive = false

        override fun pause() {
            isActive = false
        }
    }
}
