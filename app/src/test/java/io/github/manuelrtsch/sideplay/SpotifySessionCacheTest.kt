package io.github.manuelrtsch.sideplay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.manuelrtsch.sideplay.data.SpotifySession
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotifySessionCacheTest {
    @Test fun `cache size counts artwork and audio and clear removes both`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = File(context.cacheDir, "artwork").apply { mkdirs() }
        File(artwork, "cover.img").writeBytes(ByteArray(128))
        val audio = File(context.cacheDir, "spotify-audio").apply { mkdirs() }
        File(audio, "track.bin").writeBytes(ByteArray(256))
        val session = SpotifySession(context = context)

        assertEquals(384L, session.cacheSizeBytes())

        session.clearCaches()

        assertEquals(0L, session.cacheSizeBytes())
    }
}
