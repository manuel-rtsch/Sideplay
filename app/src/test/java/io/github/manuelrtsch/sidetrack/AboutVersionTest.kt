package io.github.manuelrtsch.sidetrack

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@RunWith(AndroidJUnit4::class)
class AboutVersionTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the git describe tail is not part of the plain version`() {
        assertEquals("0.3.5", plainVersionName("0.3.5-11-g55e317f"))
        assertEquals("0.3.5", plainVersionName("0.3.5-11-g55e317f-dirty"))
        assertEquals("0.3.5", plainVersionName("0.3.5-11-g55e317f-dirty-dev"))
        assertEquals("0.3.5", plainVersionName("0.3.5-dirty"))
        assertEquals("0.3.5", plainVersionName("0.3.5-dev"))
        assertEquals("0.3.5", plainVersionName("0.3.5"))
        assertEquals("0.1.0", plainVersionName("0.1.0"))
    }

    @Test
    fun buildNumberOnlyAppearsAfterTheVersionCardIsClicked() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val versionCode = packageInfo.longVersionCode
        val versionName = plainVersionName(packageInfo.versionName.orEmpty())

        compose.setContent {
            SidekitTheme(spacing = SidekitSpacing.Compact) {
                SidekitCanvas {
                    SidetrackAboutScreen(onBack = {})
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Sidetrack $versionName").assertExists()
        compose.onNodeWithText("Build $versionCode").assertDoesNotExist()
        compose.onNodeWithText(spotifyEngineLabel).assertDoesNotExist()
        compose.onNodeWithText("Sidetrack $versionName").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Build $versionCode").assertExists()
        // The engine is part of the same reveal: a tester's screenshot then says whether the APK
        // carries the native bridge or the rate-limited Web API fallback.
        compose.onNodeWithText(spotifyEngineLabel).assertExists()
    }

    @Test
    fun `the engine label names the bridge only when the build packaged it`() {
        assertEquals(
            if (BuildConfig.NATIVE_SPOTIFY) "Native Spotify engine" else "Spotify Web API, no native bridge",
            spotifyEngineLabel
        )
    }
}
