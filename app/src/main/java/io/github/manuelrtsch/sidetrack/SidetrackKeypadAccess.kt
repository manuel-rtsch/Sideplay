package io.github.manuelrtsch.sidetrack

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * The keypad Back key is delivered by Sidekit's accessibility service. A Sidekit host owns its own
 * copy; a standalone Sidetrack ships the service in its own APK, so it has to ask Android to enable
 * that component. Android does not expose a runtime grant for this: the only route is the
 * Accessibility settings screen.
 */
internal object SidetrackKeypadAccess {
    private const val SERVICE_SUFFIX = "KeypadAccessibilityService"

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it.packageName == context.packageName && it.className.endsWith(SERVICE_SUFFIX) }
    }

    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
}
