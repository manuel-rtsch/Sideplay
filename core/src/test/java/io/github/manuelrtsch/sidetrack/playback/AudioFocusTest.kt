package io.github.manuelrtsch.sidetrack.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Audio focus is where "playback mysteriously stops" and "playback talks over a call" live. The
 * rules are small but three of the four focus changes have distinct behaviour, so each is pinned
 * here rather than left to a device.
 */
@RunWith(RobolectricTestRunner::class)
class AudioFocusTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var audio: AudioManager

    private var pauses = 0
    private var resumes = 0

    @Before
    fun setUp() {
        audio = context.getSystemService(AudioManager::class.java)
        pauses = 0
        resumes = 0
    }

    @Test fun `a granted request reports that playback may start`() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)

        assertTrue(focus().request())
    }

    @Test fun `a denied request reports that playback may not start`() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertFalse(focus().request())
    }

    @Test fun `a held focus is not requested from the system twice`() {
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val focus = focus()
        focus.request()

        // A second call that would be denied proves it never reached the system again.
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertTrue(focus.request())
    }

    @Test fun `abandoning a held focus releases it`() {
        val focus = focus()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus.request()

        focus.abandon()

        assertNotNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `abandoning without a held focus does not release anyone else's`() {
        val focus = focus()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        focus.request()

        focus.abandon()

        assertNull(shadowOf(audio).lastAbandonedAudioFocusRequest)
    }

    @Test fun `a transient loss pauses and gain resumes`() {
        val focus = focus()
        focus.request()

        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(1, pauses)
        assertEquals(0, resumes)

        change(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, resumes)
    }

    @Test fun `gain without a preceding transient loss does not resume`() {
        val focus = focus()
        focus.request()

        change(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, resumes)
    }

    @Test fun `a permanent loss pauses and is not resumed by gain`() {
        val focus = focus()
        focus.request()

        change(AudioManager.AUDIOFOCUS_LOSS)
        change(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(1, pauses)
        assertEquals(0, resumes)
    }

    @Test fun `a transient loss before a permanent one does not resume`() {
        val focus = focus()
        focus.request()

        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        change(AudioManager.AUDIOFOCUS_LOSS)
        change(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(2, pauses)
        assertEquals(0, resumes)
    }

    @Test fun `abandoning during a transient loss cancels the resume`() {
        val focus = focus()
        focus.request()
        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        focus.abandon()
        change(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(0, resumes)
    }

    @Test fun `ducking leaves playback alone`() {
        val focus = focus()
        focus.request()

        change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        assertEquals(0, pauses)
        assertEquals(0, resumes)
    }

    @Test fun `a permanent loss makes the next request ask the system again`() {
        val focus = focus()
        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus.request()
        change(AudioManager.AUDIOFOCUS_LOSS)

        shadowOf(audio).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        assertFalse(focus.request())
    }

    private fun focus() = AudioFocus(
        context,
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build(),
        onPause = { pauses++ },
        onResume = { resumes++ }
    )

    private fun change(event: Int) {
        val request = shadowOf(audio).lastAudioFocusRequest
        assertNotNull("no focus request was made", request)
        listenerOf(request!!).onAudioFocusChange(event)
    }

    /**
     * Robolectric keeps the listener on its shadow request; the field name is a shadow detail, so
     * it is resolved by type rather than by name.
     */
    private fun listenerOf(request: Any): AudioManager.OnAudioFocusChangeListener {
        val field = request.javaClass.declaredFields.first {
            AudioManager.OnAudioFocusChangeListener::class.java.isAssignableFrom(it.type)
        }
        field.isAccessible = true
        return field.get(request) as AudioManager.OnAudioFocusChangeListener
    }
}
