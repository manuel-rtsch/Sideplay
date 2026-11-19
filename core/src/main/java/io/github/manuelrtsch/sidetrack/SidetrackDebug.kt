package io.github.manuelrtsch.sidetrack

import android.util.Log

/**
 * Temporary live-debug logging for the Sidetrack playback path.
 *
 * Enable on device with:
 *   adb shell setprop log.tag.SidetrackDebug DEBUG
 *
 * The tag is checked at every call, so the property can be toggled without reinstalling.
 *
 * Shared across modules, hence public.
 */
object SidetrackDebug {
    const val TAG = "SidetrackDebug"

    val enabled: Boolean get() = Log.isLoggable(TAG, Log.DEBUG)

    fun log(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    /**
     * Lazy variant for hot paths: the message is only built when the tag is enabled, so a
     * disabled debug build does not allocate a string on every publish or audio read.
     */
    inline fun log(message: () -> String) {
        if (enabled) Log.d(TAG, message())
    }

    fun log(message: String, error: Throwable) {
        if (enabled) Log.d(TAG, message, error)
    }
}
