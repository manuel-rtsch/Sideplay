package io.github.manuelrtsch.sidetrack

import android.content.Context
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
}
