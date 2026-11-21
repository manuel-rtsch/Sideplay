package io.github.manuelrtsch.sidetrack.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.ServiceId
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The process-wide provisioning path: which service gets built, what the picker sees, and what
 * happens when it asks for one twice or for one this build does not know. The singleton is reset
 * around every test, so each one starts the way a cold process does.
 *
 * What is *remembered* for the next launch is not asserted here: the selection goes through
 * `GuardedEncryptedStore`, whose keystore is not usable under Robolectric, so the write is
 * swallowed by its guard and the read comes back empty. That boundary is pinned separately by
 * `CredentialStoreGuardTest` and `StoreFailureTest`.
 */
@RunWith(RobolectricTestRunner::class)
class AppSessionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun freshProcess() {
        resetSingleton()
        // The selection is stored encrypted; a leftover from another test would provision a service.
        File(context.filesDir, "sidetracks_service").delete()
    }

    @After fun cleanUp() {
        resetSingleton()
        File(context.filesDir, "sidetracks_service").delete()
    }

    @Test fun `the picker lists every known service in order and rejects the rest`() {
        assertEquals(
            listOf(
                ServiceId.Jellyfin,
                ServiceId.Navidrome,
                ServiceId.NextcloudMusic,
                ServiceId.Plex,
                ServiceId.Spotify,
                ServiceId.Subsonic
            ),
            KnownServices.map { it.id }
        )
        assertEquals("Spotify", serviceChoice(ServiceId.Spotify)?.displayName)
        assertNull(serviceChoice(ServiceId("deezer")))
    }

    @Test fun `selecting a service this build does not know provisions nothing`() {
        val app = AppSessions.get(context)

        assertFalse(AppSessions.select(context, ServiceId("deezer")))
        assertFalse(app.provisioned)
    }

    @Test fun `selecting a service provisions it`() {
        val app = AppSessions.get(context)

        assertTrue(AppSessions.select(context, ServiceId.Navidrome))

        assertEquals(ServiceId.Navidrome, app.active.value)
        assertTrue(app.provisioned)
        assertEquals(ServiceId.Navidrome, app.runtimeOrNull?.service)
    }

    @Test fun `selecting the installed service again keeps its runtime`() {
        AppSessions.select(context, ServiceId.Navidrome)
        val app = AppSessions.get(context)
        val runtime = app.runtimeOrNull

        assertTrue(AppSessions.select(context, ServiceId.Navidrome))

        assertSame(runtime, app.runtimeOrNull)
    }

    @Test fun `each Subsonic family entry provisions its own family runtime`() {
        val app = AppSessions.get(context)

        assertTrue(AppSessions.select(context, ServiceId.NextcloudMusic))

        assertEquals(ServiceId.NextcloudMusic, app.active.value)
        val runtime = app.runtimeOrNull as? SubsonicRuntime
        assertEquals(SubsonicFamily.NextcloudMusic, runtime?.family)
        assertEquals(ServiceId.NextcloudMusic, runtime?.service)
        assertEquals("sidetracks_nextcloud-music", runtime?.family?.credentialStoreName)
    }

    @Test fun `selecting another service replaces the one that held the media session`() {
        AppSessions.select(context, ServiceId.Navidrome)
        val app = AppSessions.get(context)
        val navidromeRuntime = app.runtimeOrNull

        assertTrue(AppSessions.select(context, ServiceId.Jellyfin))

        assertEquals(ServiceId.Jellyfin, app.active.value)
        assertEquals(ServiceId.Jellyfin, app.runtimeOrNull?.service)
        assertFalse(app.runtimeOrNull === navidromeRuntime)
    }

    /** [AppSessions] caches one session per process; a cold launch is what these tests model. */
    private fun resetSingleton() {
        val field = AppSessions::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }
}
