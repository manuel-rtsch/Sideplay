package io.github.manuelrtsch.sidetrack

import android.app.Application

/**
 * Process entry point for diagnostics that must exist before the first Activity or service.
 *
 * The rolling playback log is deliberately installed here: a stall during an early play command
 * or a notification action has to be captured even when no screen was ever opened.
 */
class SidetrackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Debug and dev builds are the ones used for a field capture, so they keep the file on
        // without the log tag. A release build still opts in with `log.tag.SidetrackDebug DEBUG`.
        SidetrackDebug.installFileLog(
            this,
            always = BuildConfig.BUILD_TYPE == "debug" || BuildConfig.BUILD_TYPE == "dev"
        )
    }
}
