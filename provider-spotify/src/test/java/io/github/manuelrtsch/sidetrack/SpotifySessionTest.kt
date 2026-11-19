package io.github.manuelrtsch.sidetrack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.manuelrtsch.sidetrack.auth.CredentialPersistenceException
import io.github.manuelrtsch.sidetrack.auth.SpotifyAuth
import io.github.manuelrtsch.sidetrack.auth.SpotifyCredentialStore
import io.github.manuelrtsch.sidetrack.auth.SpotifyCredentials
import io.github.manuelrtsch.sidetrack.auth.SpotifyTokenClient
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.FilePositionStore
import io.github.manuelrtsch.sidetrack.data.ResumePoint
import io.github.manuelrtsch.sidetrack.data.SpotifyFailure
import io.github.manuelrtsch.sidetrack.data.SpotifyGatewayException
import io.github.manuelrtsch.sidetrack.data.SpotifySession
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The process-wide Spotify identity: who is signed in, what token the catalog gets, and the state
 * that outlives a session. Playback itself is covered by the controller and engine tests; here the
 * native library is absent, which is exactly the degraded path the app ships on non-Spotify builds.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifySessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeStore
    private lateinit var tokens: FakeTokens

    @Before fun cleanState() {
        store = FakeStore()
        tokens = FakeTokens()
        listOf(
            "sidetracks-queue.bin",
            "sidetracks-history.bin",
            "sidetracks-resume.bin",
            "sidetracks-episode-progress.bin",
            "sidetracks-episode-meta.bin"
        ).forEach { File(context.filesDir, it).delete() }
        File(context.cacheDir, "artwork").deleteRecursively()
        File(context.cacheDir, "spotify-audio").deleteRecursively()
    }

    private fun session(
        now: Long = NOW,
        ctx: Context? = context,
        credentials: SpotifyCredentials? = null
    ): SpotifySession {
        store.credentials = credentials
        return SpotifySession(
            auth = SpotifyAuth(store, tokens, nowMs = { now }),
            context = ctx
        )
    }

    private fun credentials(expiresAtMs: Long = NOW + 600_000L, refreshToken: String? = "refresh") = SpotifyCredentials("access", refreshToken, expiresAtMs, "streaming")

    // --- identity and token --------------------------------------------------------------------

    @Test fun `an empty store starts signed out`() {
        val session = session()

        assertFalse(session.signedIn.value)
        assertEquals(ServiceId.Spotify, session.service)
    }

    @Test fun `a stored session starts signed in and a broken store starts signed out`() {
        assertTrue(session(credentials = credentials()).signedIn.value)

        store.failRead = true
        assertFalse(session(ctx = null).signedIn.value)
    }

    @Test fun `a valid token is handed out without refreshing`() = runBlocking {
        val session = session(credentials = credentials())

        assertEquals("access", session.accessToken())
        assertEquals(0, tokens.refreshCalls)
    }

    @Test fun `an expiring token is refreshed and the rotated session stored`() = runBlocking {
        store.credentials = credentials(expiresAtMs = NOW + 1_000L)
        tokens.next = SpotifyCredentials("fresh", "rotated", NOW + 3_600_000L, "")
        val session = SpotifySession(auth = SpotifyAuth(store, tokens, nowMs = { NOW }))

        assertEquals("fresh", session.accessToken())
        assertEquals(1, tokens.refreshCalls)
        assertEquals("rotated", store.credentials?.refreshToken)
        // A refresh response without a scope keeps the one the original grant asked for.
        assertEquals("streaming", store.credentials?.scope)
    }

    @Test fun `an expired token without a refresh token yields null`() = runBlocking {
        val session = session(credentials = credentials(expiresAtMs = NOW - 1L, refreshToken = null))

        assertNull(session.accessToken())
        assertEquals(0, tokens.refreshCalls)
    }

    @Test fun `a failed refresh returns null and keeps the store untouched`() = runBlocking {
        store.credentials = credentials(expiresAtMs = NOW - 1L)
        tokens.fail = true
        val session = SpotifySession(auth = SpotifyAuth(store, tokens, nowMs = { NOW }))

        assertNull(session.accessToken())
        assertEquals("access", store.credentials?.accessToken)
    }

    @Test fun `a refresh the store rejects still yields the new token`() = runBlocking {
        store.credentials = credentials(expiresAtMs = NOW - 1L)
        store.failSave = true
        tokens.next = SpotifyCredentials("fresh", "rotated", NOW + 3_600_000L, "streaming")
        val session = SpotifySession(auth = SpotifyAuth(store, tokens, nowMs = { NOW }))

        assertEquals("fresh", session.accessToken())
    }

    // --- sign in and out -----------------------------------------------------------------------

    @Test fun `password sign-in is rejected before anything changes`() = runBlocking {
        val session = session()

        val error = runCatching {
            session.signIn(AuthInput.Password("http://server", "user", "secret"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(session.signedIn.value)
    }

    @Test fun `sign-out clears identity queue history resume and cached files`() {
        File(context.cacheDir, "artwork").apply { mkdirs() }
        File(File(context.cacheDir, "artwork"), "cover.img").writeBytes(ByteArray(32))
        File(context.cacheDir, "spotify-audio").apply { mkdirs() }
        File(File(context.cacheDir, "spotify-audio"), "track.bin").writeBytes(ByteArray(64))
        val session = session(credentials = credentials())
        session.queue.play(listOf(track("a"), track("b")), 0)
        session.history.record(track("a"), 1L)
        FilePositionStore(File(context.filesDir, "sidetracks-resume.bin"))
            .write(ResumePoint(track("a").key, 1_000L))
        assertTrue(session.signedIn.value)

        session.signOut()

        assertFalse(session.signedIn.value)
        assertNull(store.credentials)
        assertNull(session.queue.snapshot.current)
        assertTrue(session.queue.snapshot.upcoming.isEmpty())
        assertTrue(session.history.items().isEmpty())
        assertNull(session.resumePoint)
        assertEquals(0L, session.cacheSizeBytes())
    }

    // --- persisted state -----------------------------------------------------------------------

    @Test fun `queue and history survive a new session`() {
        val first = session()
        first.queue.play(listOf(track("a"), track("b"), track("c")), 1)
        first.history.record(track("b"), 42L)

        val second = session()

        assertEquals(listOf("b", "c"), listOf(second.queue.snapshot.current?.id) + second.queue.snapshot.upcoming.map { it.id })
        assertEquals(listOf("b"), second.history.items().map { it.id })
        assertEquals(42L, second.history.snapshot().single().playedAtMs)
    }

    @Test fun `a stored resume point is read and a later sign-out removes it`() {
        FilePositionStore(File(context.filesDir, "sidetracks-resume.bin"))
            .write(ResumePoint(track("a").key, 12_345L))

        val session = session()

        assertEquals(12_345L, session.resumePoint?.positionMs)
        assertEquals("a", session.resumePoint?.key?.id)

        session.signOut()

        assertNull(session.resumePoint)
        assertNull(FilePositionStore(File(context.filesDir, "sidetracks-resume.bin")).read())
    }

    // --- settings ------------------------------------------------------------------------------

    @Test fun `the playback settings default to Spotify's normal profile`() {
        val settings = session().settings.value

        assertEquals(AudioQuality.High, settings.audioQuality)
        assertFalse(settings.normalization)
        assertTrue(settings.gapless)
        assertTrue(settings.autoplay)
    }

    @Test fun `each setting updates the session immediately`() {
        val session = session()

        session.setAudioQuality(AudioQuality.VeryHigh)
        session.setNormalization(true)
        session.setGapless(false)
        session.setAutoplay(false)

        assertEquals(AudioQuality.VeryHigh, session.settings.value.audioQuality)
        assertTrue(session.settings.value.normalization)
        assertFalse(session.settings.value.gapless)
        assertFalse(session.settings.value.autoplay)
    }

    @Test fun `setting the same value is a no-op`() {
        val session = session()
        val before = session.settings.value

        session.setAudioQuality(before.audioQuality)
        session.setNormalization(before.normalization)
        session.setGapless(before.gapless)
        session.setAutoplay(before.autoplay)

        assertEquals(before, session.settings.value)
    }

    // --- playback entry points -----------------------------------------------------------------

    @Test fun `without credentials playback fails as an expired session`() = runBlocking {
        val session = session(ctx = null)

        val connect = runCatching { session.ensurePlaybackConnected() }.exceptionOrNull()
            as SpotifyGatewayException
        assertEquals(SpotifyFailure.AuthenticationExpired, connect.failure)

        val play = runCatching { session.play(track("a").key, "spotify:track:a") }.exceptionOrNull()
            as SpotifyGatewayException
        assertEquals(SpotifyFailure.AuthenticationExpired, play.failure)

        val resume = runCatching { session.resume() }.exceptionOrNull() as SpotifyGatewayException
        assertEquals(SpotifyFailure.AuthenticationExpired, resume.failure)
    }

    private class FakeStore(
        var credentials: SpotifyCredentials? = null,
        var failRead: Boolean = false,
        var failSave: Boolean = false
    ) : SpotifyCredentialStore {
        override fun read(): SpotifyCredentials? {
            if (failRead) throw IllegalStateException("keystore unavailable")
            return credentials
        }

        override fun save(credentials: SpotifyCredentials) {
            if (failSave) throw CredentialPersistenceException("cannot persist")
            this.credentials = credentials
        }

        override fun clear() {
            credentials = null
        }
    }

    private class FakeTokens : SpotifyTokenClient {
        var next: SpotifyCredentials? = null
        var fail = false
        var refreshCalls = 0

        override suspend fun exchange(
            clientId: String,
            code: String,
            verifier: String,
            redirectUri: String
        ): SpotifyCredentials = error("not used")

        override suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials {
            refreshCalls++
            if (fail) throw IllegalStateException("refresh rejected")
            return next ?: error("no refresh response")
        }
    }

    private fun track(id: String): Track = Track(
        id = id,
        title = "Track $id",
        artist = "Aurora",
        artistId = "ar1",
        albumId = "al1",
        artRes = 0,
        durationMs = 180_000L,
        uri = "spotify:track:$id",
        service = ServiceId.Spotify
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
