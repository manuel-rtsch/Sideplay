package io.github.manuelrtsch.sideplay

import android.util.Log

/**
 * Temporary live-debug logging for the Sideplay playback path.
 *
 * Enable on device with:
 *   adb shell setprop log.tag.SideplayDebug DEBUG
 *
 * The tag is checked at every call, so the property can be toggled without reinstalling.
 */
internal object SideplayDebug {
    const val TAG = "SideplayDebug"

    val enabled: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)

    fun log(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    fun log(message: String, error: Throwable) {
        if (enabled) Log.d(TAG, message, error)
    }
}
