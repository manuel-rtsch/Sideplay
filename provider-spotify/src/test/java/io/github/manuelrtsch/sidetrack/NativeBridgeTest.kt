package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.bridge.NativeBridge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `NativeBridge` is the JNI boundary, and its contract is that a build, emulator or device without
 * `libsidetrack_spotify.so` behaves like "Spotify playback is unavailable" rather than crashing the
 * process. These tests cover both states: an unloadable library, and a loaded library whose symbols
 * are missing or throw (the case a partial ABI split produces).
 */
class NativeBridgeTest {
    @After fun resetNativeState() {
        markLoaded(false)
    }

    @Test fun `without the library every call answers the neutral value`() {
        markLoaded(false)

        assertFalse(NativeBridge.isAvailable)
        assertFalse(NativeBridge.initialize())
        assertEquals("native library unavailable", NativeBridge.connect("token"))
        assertFalse(NativeBridge.setAccessToken("token"))
        assertFalse(NativeBridge.hasPlayer())
        assertFalse(NativeBridge.createPlayer(160, false, true))
        assertFalse(NativeBridge.recreatePlayer(160, false, true))
        assertFalse(NativeBridge.preload("spotify:track:1"))
        assertFalse(NativeBridge.load("spotify:track:1", 0L, autoplay = true))
        assertFalse(NativeBridge.play())
        assertFalse(NativeBridge.pause())
        assertFalse(NativeBridge.stop())
        assertFalse(NativeBridge.seek(1_000L))
        assertNull(NativeBridge.readAudio(0L))
        assertNull(NativeBridge.audioFormat())
        assertNull(NativeBridge.pollEvent())
        assertNull(NativeBridge.catalogLibrary("", 0L, 20L))
        assertNull(NativeBridge.catalogSearch("night", "track", 0L, 20L))
        assertNull(NativeBridge.collectionContains("liked", "spotify:track:1"))
        assertFalse(NativeBridge.collectionWrite("liked", "spotify:track:1", removed = false))
        assertNull(NativeBridge.playlistSaved("playlist"))
        assertFalse(NativeBridge.playlistSave("playlist", save = true))
        assertNull(NativeBridge.playlistItemsAdd("playlist", "spotify:track:1"))
        assertNull(NativeBridge.playlistItemsRemove("playlist", "spotify:track:1"))
        // Fire-and-forget calls must also be safe without a library.
        NativeBridge.setTmpDir("/tmp")
        NativeBridge.setCacheDir("/tmp", 1_000L)
        NativeBridge.disconnect()
        NativeBridge.drainAudio()
    }

    @Test fun `a loaded library with missing symbols is still treated as a failed call`() {
        markLoaded(true)
        assertTrue(NativeBridge.isAvailable)

        // The JVM throws UnsatisfiedLinkError for every symbol here; the bridge must translate that
        // to "false"/"null" instead of letting it escape into the service or the UI.
        assertFalse(NativeBridge.initialize())
        assertTrue(NativeBridge.connect("token")!!.startsWith("bridge failure: "))
        assertFalse(NativeBridge.setAccessToken("token"))
        assertFalse(NativeBridge.hasPlayer())
        assertFalse(NativeBridge.createPlayer(160, false, true))
        assertFalse(NativeBridge.recreatePlayer(160, false, true))
        assertFalse(NativeBridge.preload("spotify:track:1"))
        assertFalse(NativeBridge.load("spotify:track:1", 0L, autoplay = false))
        assertFalse(NativeBridge.play())
        assertFalse(NativeBridge.pause())
        assertFalse(NativeBridge.stop())
        assertFalse(NativeBridge.seek(0L))
        assertNull(NativeBridge.readAudio(100L))
        assertNull(NativeBridge.audioFormat())
        assertNull(NativeBridge.pollEvent())
        assertNull(NativeBridge.catalogArtist("artist"))
        assertNull(NativeBridge.catalogSongRadio("track", 10L))
        assertNull(NativeBridge.playlistItemsAdd("playlist", "spotify:track:1"))
        assertNull(NativeBridge.playlistItemsRemove("playlist", "spotify:track:1"))
        NativeBridge.setTmpDir("/tmp")
        NativeBridge.setCacheDir("/tmp", 1_000L)
        NativeBridge.disconnect()
        NativeBridge.drainAudio()
    }

    @Test fun `the library name is the one the Rust build produces`() {
        assertEquals("sidetrack_spotify", NativeBridge.LIBRARY)
    }

    /**
     * Forces whichever library state the test needs. `NativeLibraries` keeps its loaded/failed sets
     * private, and there is no public way to pretend a `.so` is present; this is the only seam short
     * of shipping a test-only ABI.
     */
    private fun markLoaded(loaded: Boolean) {
        val type = Class.forName("hrln.interactive.sidekit.sdk.system.NativeLibraries")
        val loadedSet = type.getDeclaredField("loaded").apply { isAccessible = true }
            .get(null) as MutableSet<*>
        val failedSet = type.getDeclaredField("failed").apply { isAccessible = true }
            .get(null) as MutableSet<*>

        @Suppress("UNCHECKED_CAST")
        val loadedNames = loadedSet as MutableSet<String>

        @Suppress("UNCHECKED_CAST")
        val failedNames = failedSet as MutableSet<String>
        if (loaded) {
            loadedNames.add(NativeBridge.LIBRARY)
            failedNames.remove(NativeBridge.LIBRARY)
        } else {
            loadedNames.remove(NativeBridge.LIBRARY)
            failedNames.remove(NativeBridge.LIBRARY)
        }
    }
}
