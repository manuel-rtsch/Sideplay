package io.github.manuelrtsch.sidetrack

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import hrln.interactive.sidekit.sdk.theme.SharedPreferencesSidekitThemeStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SidetrackLocalSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences(SidetrackLocalSettings.FILE, Context.MODE_PRIVATE)
    private val themeStore get() = SharedPreferencesSidekitThemeStore(context, SidetrackLocalSettings.FILE)

    @Before fun clearSettings() {
        prefs.edit().clear().commit()
    }

    @Test fun `a fresh standalone install uses the compact layout`() {
        SidetrackLocalSettings(context).applyStandaloneThemeDefaults()

        assertTrue(themeStore.read().compact)
    }

    @Test fun `an explicit choice is not overwritten`() {
        prefs.edit().putBoolean("compact_layout", false).commit()

        SidetrackLocalSettings(context).applyStandaloneThemeDefaults()

        assertFalse(themeStore.read().compact)
    }

    @Test fun `an existing install has not seen the welcome yet`() {
        assertFalse(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test fun `finishing the welcome is remembered across instances`() {
        SidetrackLocalSettings(context).markOnboardingSeen()

        assertTrue(SidetrackLocalSettings(context).hasSeenOnboarding())
    }

    @Test fun `an existing install without keypad access gets one welcome`() {
        prefs.edit().putBoolean("onboarding_seen", true).commit()

        assertTrue(SidetrackLocalSettings(context).needsOnboarding(hostBacked = false))

        SidetrackLocalSettings(context).markOnboardingSeen()

        assertFalse(SidetrackLocalSettings(context).needsOnboarding(hostBacked = false))
    }

    @Test fun `a host backed install does not ask for Sidetrack keypad access`() {
        prefs.edit().putBoolean("onboarding_seen", true).commit()

        assertFalse(SidetrackLocalSettings(context).needsOnboarding(hostBacked = true))
    }

    @Test fun `the keypad accessibility service is detected by package and class suffix`() {
        val component = context.packageName + "/hrln.interactive.sidekit.sdk.system.KeypadAccessibilityService"
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "other.app/.OtherService:$component"
        )

        assertTrue(SidetrackKeypadAccess.isEnabled(context))
    }
}
