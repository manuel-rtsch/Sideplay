package io.github.manuelrtsch.sidetrack

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import hrln.interactive.sidekit.design.canvas.SidekitCanvas
import hrln.interactive.sidekit.design.theme.CaseColour
import hrln.interactive.sidekit.design.theme.SidekitSpacing
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.playback.SleepTimerState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

/**
 * Store-catalog screenshots: the same screens as [SidetrackScreenshotTest], but rendered the way
 * the app looks on the Sidephone's keypad with the Lavender case colour.
 *
 * The keypad part is Android's touch mode. Sidekit's header only offers the touch-only Options
 * button (the three dots) while the window is in touch mode, so the shots are taken with the
 * window pushed out of it — the state a keypad press produces on the device.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val state = screenshotState()

    private fun shoot(name: String, content: @Composable () -> Unit) {
        leaveTouchMode()
        compose.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                SidekitTheme(colors = CaseColour.Lavender.palette(), spacing = SidekitSpacing.Compact) {
                    SidekitCanvas { content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("screenshots/store/$name.png")
    }

    /**
     * Puts the window in keypad (non-touch) mode before anything composes. `View.isInTouchMode()`
     * reads the flag from the attach info, so writing it there is enough for the first frame; no
     * listener dispatch is needed because nothing has read the old value yet.
     */
    private fun leaveTouchMode() {
        val view: View = compose.activity.window.decorView
        val attachInfo = field(View::class.java, "mAttachInfo").get(view)
            ?: error("The activity's decor view is not attached yet")
        // API 35 renamed it from mTouchMode to mInTouchMode; try both so a Robolectric SDK bump
        // does not silently bring the dots back.
        val flag = listOf("mInTouchMode", "mTouchMode")
            .firstNotNullOfOrNull { name ->
                runCatching { field(attachInfo.javaClass, name) }.getOrNull()
            }
            ?: error("No touch-mode flag on ${attachInfo.javaClass.name}")
        flag.setBoolean(attachInfo, false)
        check(!view.isInTouchMode) { "The window is still in touch mode" }
    }

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }

    @Test fun home() = shoot("sidetrack_home") {
        SidetrackHomeScreen(
            state.playback,
            state.chrome,
            sleepTimer = MutableStateFlow(SleepTimerState.Off),
            onNowPlaying = {},
            onLibrary = {},
            onSearch = {},
            onSettings = {},
            onPlay = {},
            onOpenQueue = {},
            onAddToQueue = {},
            onRemoveHistory = { null },
            onHistory = {},
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun library() = shoot("sidetrack_library") {
        LibraryScreen(
            state.library,
            state.saved,
            state.chrome,
            onBack = {},
            onSearch = {},
            onFilter = {},
            onTrack = {},
            onAddToQueue = {},
            onPlaylist = {},
            onAlbum = {},
            onArtist = {},
            onShow = {},
            onSaveTrack = {},
            onEnsureSavedTrack = {},
            onLoadMore = {},
            onNowPlaying = {},
            onRetry = {},
            onCreatePlaylist = {},
            canCreatePlaylist = false,
            onDismissNotice = {},
            onDismissNowPlaying = {}
        )
    }

    @Test fun nowPlayingBackdrop() {
        val settings = SidetrackLocalSettings(RuntimeEnvironment.getApplication())
        settings.nowPlayingBackdrop = true
        try {
            shoot("sidetrack_now_playing_backdrop") { NowPlayingUnderTest(state) }
        } finally {
            settings.nowPlayingBackdrop = false
        }
    }
}
