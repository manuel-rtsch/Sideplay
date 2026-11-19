package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sidetrack.playback.ExoPlayerMedia3Player
import io.github.manuelrtsch.sidetrack.playback.Media3PlaybackEngine
import io.github.manuelrtsch.sidetrack.playback.MediaSessionRegistry
import io.github.manuelrtsch.sidetrack.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phase 4 exit check: a real ExoPlayer plays an HTTP stream through the shared
 * [PlaybackController], not through a Spotify-specific path.
 */
@RunWith(AndroidJUnit4::class)
class Media3PlaybackInstrumentedTest {
    @Test
    fun httpStreamPlaysThroughTheSharedController() = runBlocking {
        TestHttpStream().use { stream ->
            val context = ApplicationProvider.getApplicationContext<Context>()
            val player = withContext(Dispatchers.Main) { ExoPlayerMedia3Player(context) }
            val engine = Media3PlaybackEngine { player }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val controller = PlaybackController(engine, scope, MediaSessionRegistry())
            val key = MediaKey(ServiceId.Navidrome, MediaType.Track, "1")

            withContext(Dispatchers.Main) {
                controller.start(key, stream.url, 0L)
            }

            val deadline = System.currentTimeMillis() + 15_000
            var position = 0L
            var playing = false
            while (System.currentTimeMillis() < deadline) {
                withContext(Dispatchers.Main) {
                    playing = player.isPlaying
                    position = player.currentPositionMs
                }
                if (playing && position > 0L) break
                delay(100)
            }

            assertTrue("player never started (playing=$playing position=$position)", playing)
            assertTrue("player never advanced (position=$position)", position > 0L)
            // ExoPlayer opens a fresh item at position 0 without a `Range` header; it only asks
            // for a range when it resumes or seeks inside the file. The claim this test makes is
            // that the shared controller streamed over HTTP at all, so pin the request, not the
            // header shape a particular ExoPlayer build happens to choose.
            assertTrue("the stream was never requested: ${stream.requests}", stream.requests.isNotEmpty())

            withContext(Dispatchers.Main) {
                controller.stop()
                controller.disconnect()
            }
        }
    }
}
