package io.github.manuelrtsch.sideplay.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Audio focus for one player. Losing focus for good pauses; a transient loss (a call, a
 * notification) pauses and resumes when focus returns. Ducking is left to the system.
 */
class AudioFocus(
    context: Context,
    attributes: AudioAttributes,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var resumeOnGain = false
    private var held = false

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    held = false
                    resumeOnGain = false
                    onPause()
                }

                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    resumeOnGain = true
                    onPause()
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    held = true
                    if (resumeOnGain) onResume()
                    resumeOnGain = false
                }
            }
        }
        .build()

    /** True if playback may start. */
    fun request(): Boolean {
        if (held) return true
        held = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        resumeOnGain = false
        return held || audio == null
    }

    fun abandon() {
        resumeOnGain = false
        if (!held) return
        held = false
        audio?.abandonAudioFocusRequest(request)
    }
}
