package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.RecordingProvider
import io.github.manuelrtsch.sidetrack.RecordingServiceRuntime
import io.github.manuelrtsch.sidetrack.ServiceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The process-wide session is what every screen and the playback service reach for, so its
 * unprovisioned state has to be silent rather than throwing: a notification press can arrive
 * before the picker has ever run.
 */
class AppSessionTest {
    private val spotify = ServiceChoice(ServiceId.Spotify, "Spotify")
    private val navidrome = ServiceChoice(ServiceId.Navidrome, "Navidrome")

    @Test fun `a session with no stored service starts unprovisioned`() {
        val app = AppSession(store = null)

        assertFalse(app.provisioned)
        assertNull(app.active.value)
        assertNull(app.runtimeOrNull)
        assertNull(app.playbackOrNull)
        assertNull(app.provider)
        assertEquals("", app.serviceName)
        assertFalse(app.signedIn.value)
    }

    @Test fun `asking for the runtime before a service is chosen is a programming error`() {
        val app = AppSession(store = null)

        assertThrows(IllegalStateException::class.java) { app.runtime }
    }

    @Test fun `transport commands before provisioning are silent`() {
        val app = AppSession(store = null)

        app.pause()
        app.togglePlayback()
        app.stopPlayback()
        app.haltPlayback()
    }

    @Test fun `installing a service exposes its runtime and provider`() {
        val app = AppSession(store = null)
        val runtime = RecordingServiceRuntime()
        val provider = RecordingProvider(navidrome)

        app.install(navidrome, provider, runtime)

        assertTrue(app.provisioned)
        assertSame(runtime, app.runtimeOrNull)
        assertSame(runtime, app.runtime)
        assertSame(provider, app.provider)
        assertEquals("Navidrome", app.serviceName)
    }

    @Test fun `installing a service publishes it to screens`() {
        val app = AppSession(store = null)

        app.install(spotify, RecordingProvider(spotify), RecordingServiceRuntime())

        assertEquals(ServiceId.Spotify, app.active.value)
    }

    @Test fun `pause is forwarded to the installed runtime`() {
        val runtime = RecordingServiceRuntime()
        val app = installed(spotify, runtime)

        app.pause()

        assertEquals(listOf("pause"), runtime.calls)
    }

    @Test fun `togglePlayback is forwarded to the installed runtime`() {
        val runtime = RecordingServiceRuntime()
        val app = installed(spotify, runtime)

        app.togglePlayback()

        assertEquals(listOf("toggle"), runtime.calls)
    }

    @Test fun `stopping playback is forwarded to the installed runtime`() {
        val runtime = RecordingServiceRuntime()
        val app = installed(spotify, runtime)

        app.stopPlayback()

        assertEquals(listOf("stop"), runtime.calls)
    }

    @Test fun `halting playback is forwarded to the installed runtime`() {
        val runtime = RecordingServiceRuntime()
        val app = installed(spotify, runtime)

        app.haltPlayback()

        assertEquals(listOf("halt"), runtime.calls)
    }

    @Test fun `signedIn follows the installed runtime`() {
        val runtime = RecordingServiceRuntime()
        val app = installed(spotify, runtime)

        runtime.signedInFlow.value = true

        assertTrue(app.signedIn.value)
    }

    @Test fun `playback is the installed runtime's controller`() {
        val runtime = RecordingServiceRuntime()
        val app = installed(spotify, runtime)

        assertSame(runtime.playback, app.playback)
        assertSame(runtime.playback, app.playbackOrNull)
    }

    @Test fun `installing another service replaces the runtime and the active service`() {
        val first = RecordingServiceRuntime()
        val second = RecordingServiceRuntime()
        val app = installed(spotify, first)

        app.install(navidrome, RecordingProvider(navidrome), second)

        assertEquals(ServiceId.Navidrome, app.active.value)
        assertSame(second, app.runtimeOrNull)
        assertEquals("Navidrome", app.serviceName)
    }

    private fun installed(choice: ServiceChoice, runtime: RecordingServiceRuntime): AppSession = AppSession(store = null).apply { install(choice, RecordingProvider(choice), runtime) }
}
