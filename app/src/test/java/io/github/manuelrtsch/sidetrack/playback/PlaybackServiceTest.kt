package io.github.manuelrtsch.sidetrack.playback

import android.app.NotificationManager
import android.content.Intent
import io.github.manuelrtsch.sidetrack.RecordingProvider
import io.github.manuelrtsch.sidetrack.RecordingServiceRuntime
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.data.AppSession
import io.github.manuelrtsch.sidetrack.data.AppSessions
import io.github.manuelrtsch.sidetrack.data.ServiceChoice
import io.github.manuelrtsch.sidetrack.data.ServiceRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The playback service is the only thing keeping audio alive when SideKit is not in front, and the
 * notification is its only UI. The transport actions and the notification's content are therefore
 * pinned here rather than left to a device: a cold process, a signed-out session and a paused
 * player each take a different branch.
 */
@RunWith(RobolectricTestRunner::class)
class PlaybackServiceTest {
    private lateinit var service: PlaybackService
    private lateinit var runtime: RecordingServiceRuntime

    @Before
    fun setUp() {
        service = start(serviceContext = null)
    }

    @Test fun `creating the service registers the playback channel`() {
        val manager = service.getSystemService(NotificationManager::class.java)

        assertNotNull(manager.getNotificationChannel(PlaybackNotificationHooks.CHANNEL_ID))
    }

    @Test fun `a service with nothing playing leaves the foreground`() {
        service.onStartCommand(null, 0, 0)

        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `a playing track is promoted to a media notification with its title`() {
        play(track(title = "Night Signals"))
        service.onStartCommand(null, 0, 0)

        val notification = shadowOf(service).lastForegroundNotification

        assertEquals("Night Signals", notification.extras.getCharSequence("android.title").toString())
    }

    @Test fun `the notification names the artist and the sleep timer when one is running`() {
        play(track(title = "Night Signals"))
        service.onStartCommand(null, 0, 0)

        val notification = shadowOf(service).lastForegroundNotification
        val text = notification.extras.getCharSequence("android.text").toString()

        assertTrue(text.startsWith("Aurora"))
    }

    @Test fun `the notification offers previous, pause and next`() {
        play(track(title = "Night Signals"))
        service.onStartCommand(null, 0, 0)

        val actions = shadowOf(service).lastForegroundNotification.actions.map { it.title.toString() }

        assertEquals(listOf("Previous", "Pause", "Next"), actions)
    }

    @Test fun `a paused track's notification offers play`() {
        val controller = play(track(title = "Night Signals"))
        controller.updateState(isPlaying = false, position = 0L)

        service.onStartCommand(null, 0, 0)

        val notification = shadowOf(service.getSystemService(NotificationManager::class.java))
            .getNotification(controller.notificationId)
        assertEquals(listOf("Previous", "Play", "Next"), notification.actions.map { it.title.toString() })
    }

    @Test fun `a play-pause press toggles playback`() {
        play(track(title = "Night Signals"))

        service.onStartCommand(Intent(ACTION_PLAY_PAUSE), 0, 0)

        assertEquals(listOf("toggle"), runtime.calls)
    }

    @Test fun `a next press asks the player for the next item`() {
        val controller = play(track(title = "Night Signals"))
        var next = 0
        controller.onNext = { next++ }

        service.onStartCommand(Intent(ACTION_NEXT), 0, 0)

        assertEquals(1, next)
    }

    @Test fun `a previous press asks the player for the previous item`() {
        val controller = play(track(title = "Night Signals"))
        var previous = 0
        controller.onPrevious = { previous++ }

        service.onStartCommand(Intent(ACTION_PREVIOUS), 0, 0)

        assertEquals(1, previous)
    }

    @Test fun `a stop press stops playback`() {
        play(track(title = "Night Signals"))

        service.onStartCommand(Intent(ACTION_STOP), 0, 0)

        assertEquals(listOf("stop"), runtime.calls)
    }

    @Test fun `a session with no selected service drops the notification and stops`() {
        install(runtime = null)

        service.onStartCommand(Intent(ACTION_PLAY_PAUSE), 0, 0)

        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun `removing the task halts the audio`() {
        play(track(title = "Night Signals"))

        service.onTaskRemoved(null)

        assertEquals(listOf("halt"), runtime.calls)
    }

    /**
     * Builds the service and installs the process session it will read. The player needs the
     * service as its context so the notification has a media session token to point at.
     */
    private fun start(serviceContext: PlaybackService?): PlaybackService {
        val created = Robolectric.buildService(PlaybackService::class.java).create().get()
        runtime = RecordingServiceRuntime(playbackContext = serviceContext ?: created)
        install(runtime)
        return created
    }

    private fun install(runtime: ServiceRuntime?) {
        val app = AppSession(store = null)
        val choice = ServiceChoice(ServiceId.Spotify, "Spotify")
        if (runtime != null) app.install(choice, RecordingProvider(choice), runtime)
        val field = AppSessions::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(AppSessions, app)
    }

    private fun play(item: Track): PlaybackController {
        val controller = runtime.playback
        controller.setNowPlaying(item)
        controller.updateState(isPlaying = true, position = 0L)
        return controller
    }

    private fun track(title: String) = Track(
        id = "night_signals",
        title = title,
        artist = "Aurora",
        artistId = "aurora",
        albumId = "night",
        artRes = 0,
        durationMs = 180_000L
    )

    private companion object {
        const val ACTION_PLAY_PAUSE = "io.github.manuelrtsch.sidetrack.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.manuelrtsch.sidetrack.NEXT"
        const val ACTION_PREVIOUS = "io.github.manuelrtsch.sidetrack.PREVIOUS"
        const val ACTION_STOP = "io.github.manuelrtsch.sidetrack.STOP"
    }
}
