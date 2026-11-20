package io.github.manuelrtsch.sidetrack

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.manuelrtsch.sidetrack.data.AppSessions
import io.github.manuelrtsch.sidetrack.playback.PlaybackService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpotifyPlaybackServiceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session get() = AppSessions.get(context).runtime
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val notificationId get() = session.playback.notificationId

    @Before
    fun grantNotifications() {
        // A fresh install opens at the service picker, so this suite has to provision Spotify
        // before it can drive the runtime it tests.
        if (!AppSessions.get(context).provisioned) AppSessions.select(context, ServiceId.Spotify)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    @After
    fun cleanUp() {
        onMain {
            session.playback.updateState(false, 0L)
            session.playback.setNowPlaying(null)
        }
        context.stopService(Intent(context, PlaybackService::class.java))
        manager.cancel(notificationId)
    }

    @Test
    fun playingTrackStartsTheForegroundServiceAndPublishesTransportActions() {
        ActivityScenario.launch(SidetrackActivity::class.java).use {
            onMain {
                session.playback.setNowPlaying(track)
                session.playback.updateState(true, 0L)
            }

            val notification = waitForNotification()
            assertEquals("sidetrack", notification.channelId)
            assertEquals("Service track", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
            assertEquals("Artist", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
            assertEquals(listOf("Previous", "Pause", "Next"), notification.actions.map { it.title.toString() })
            assertNotNull(notification.contentIntent)
            assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(manager.getNotificationChannel("sidetrack") != null)
        }
    }

    /**
     * The service posts a placeholder the instant it enters the foreground, and the placeholder
     * shares [notificationId] with the real notification. Waiting for the id alone therefore
     * returns the placeholder and makes the content assertions flaky on a cold service, so wait
     * for the transport actions only the real notification carries. The placeholder builds no
     * actions at all, so the field is null rather than empty and has to be null-checked.
     */
    private fun waitForNotification(): Notification {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (true) {
            manager.activeNotifications.firstOrNull { it.id == notificationId }?.notification?.let { notification ->
                if (notification.actions?.isNotEmpty() == true) return notification
            }
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("Notification was not posted")
            Thread.sleep(20L)
        }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private val track = Track(
        id = "service-track",
        title = "Service track",
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L,
        uri = "spotify:track:service"
    )
}
