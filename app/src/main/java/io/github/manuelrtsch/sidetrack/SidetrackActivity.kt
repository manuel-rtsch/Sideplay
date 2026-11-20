package io.github.manuelrtsch.sidetrack

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.sdk.SidekitStores
import hrln.interactive.sidekit.sdk.app.SidekitActivity
import hrln.interactive.sidekit.sdk.config.SidekitConfigClient
import hrln.interactive.sidekit.sdk.input.applyInputMode
import hrln.interactive.sidekit.sdk.system.KeypadSystemBack
import io.github.manuelrtsch.sidetrack.data.AppSessions
import io.github.manuelrtsch.sidetrack.playback.PlaybackService

/**
 * The Sidetrack shell. It reads Sidekit's configuration when a compatible host is installed and
 * keeps its own writable theme and keypad stores otherwise.
 *
 * Host integration is best-effort: an absent, older, or unreadable Sidekit never blocks startup.
 * Sidetrack then runs standalone with the settings it already had.
 */
class SidetrackActivity : SidekitActivity() {
    private val homeRequests = mutableIntStateOf(0)
    private val searchRequests = mutableIntStateOf(0)

    private var navController: NavHostController? = null
    private var keypadBackOverride: (() -> Boolean)? = null
    private val systemBack: () -> Boolean = {
        keypadBackOverride?.invoke() ?: (navController?.keypadBack() == true)
    }

    /**
     * The Sundial's centre and edge keys, whose taps Sidekit re-delivers as window events.
     *
     * The transport is looked up per event instead of held: the service can be chosen, signed out
     * of or replaced while the activity stays alive, and a key with no player is left to the
     * platform.
     */
    private val mediaKeys = SidetrackMediaKeys {
        AppSessions.get(this).runtimeOrNull?.let { runtime ->
            MediaKeyTransport { command ->
                when (command) {
                    MediaKeyCommand.TogglePlayback -> runtime.togglePlayback()
                    MediaKeyCommand.Next -> runtime.playback.requestNext()
                    MediaKeyCommand.Previous -> runtime.playback.requestPrevious()
                }
            }
        }
    }

    /**
     * A screen with a temporary mode (the Queue's reorder or clear confirmation) can claim the
     * keypad Back key first. Returning null gives it back to the screen's left action.
     */
    fun setKeypadBackOverride(handler: (() -> Boolean)?) {
        keypadBackOverride = handler
    }

    override fun onSidekitHome() {
        homeRequests.intValue++
    }

    override fun onSidekitSearch() {
        searchRequests.intValue++
    }

    override val stores: SidekitStores.Stores by lazy {
        val stores = SidekitStores.forHostOrLocal(this, intent, LOCAL_SETTINGS, LOCAL_KEYPAD)
        // A Sidekit host owns the layout; without one, Sidetrack defaults to compact.
        if (!stores.isHostBacked) SidetrackLocalSettings(this).applyStandaloneThemeDefaults()
        stores
    }

    override fun onSidekitCreate(savedInstanceState: Bundle?) {
        // kotlin-logging, pulled in by the Jellyfin SDK, defaults to its SLF4J backend on Android
        // but ships no org.slf4j classes, so the SDK's first log call during sign-in dies with
        // NoClassDefFoundError: org/slf4j/LoggerFactory. This property selects the native
        // android.util.Log backend instead; it must be set before the SDK logs for the first time.
        System.setProperty("kotlin-logging-to-android-native", "true")
        if (savedInstanceState == null) SidetrackLinks.handle(intent)
        // The media notification is app-owned and driven from core through injected hooks. They
        // must be installed here, before any player can demand a notification, so core never has
        // to name the service.
        PlaybackService.installPlaybackNotificationHooks()
    }

    /**
     * Re-read the host's keypad choice every time Sidetrack comes back to the front. The user may
     * have switched between T9 and mini QWERTY while Sidetrack was in the background; without this
     * the keypad keeps the module and input mode it started with, so QWERTY's T/D/B/J navigation
     * cross never becomes active.
     */
    override fun onSidekitResume(keypad: Keypad) {
        val config = if (stores.isHostBacked) SidekitConfigClient.read(this, stores.hostAuthority) else null
        // Standalone Sidetrack keeps its own input mode; a host build owns it when present.
        val local = SidetrackLocalSettings(this)
        keypad.applyInputMode(config?.inputModeOrDefault() ?: local.inputMode)
        val module = config?.keypadModuleOrDefault() ?: keypadPreferences.module()
        if (module != null) keypad.useModule(module)
        // The stored module is only a memory, and it is wrong whenever the keypad tile was swapped
        // while Sidetrack was not looking. Sidekit reads the module the attached device names, so
        // every screen starts on the keypad that is really there.
        keypad.observeAttached()
        keypad.useCustomMapping(config?.customModuleOrDefault() ?: keypadPreferences.customModule())
        KeypadSystemBack.delegate = systemBack
    }

    // The delegate is process-global, so it must not outlive the screen. Clearing it on pause was
    // too eager: a dialog on top only pauses the activity, and Back handling then stayed gone until
    // the next resume. onStop still fires when Sidetrack is backgrounded, which is the case that
    // actually needs the delegate released.
    override fun onStop() {
        if (KeypadSystemBack.delegate === systemBack) KeypadSystemBack.delegate = null
        super.onStop()
    }

    /**
     * Media keys arrive here after Sidekit has had its chance at them. A Sundial tap is a synthetic
     * press Sidekit re-sent while watching for a hold, so the platform's own media-button routing
     * never sees it; the player is driven from the same callback the notification uses.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // The keys Sidekit passes on, with the module it currently believes is attached. The
        // consumed ones (Options, the gamepad's X/Y/Start) never get here, so this is the other
        // half of the keypad picture, not the whole of it.
        SidetrackDebug.log {
            "key=${KeyEvent.keyCodeToString(keyCode)} device=${event.device?.name} " +
                "module=${keypad.module} custom=${keypad.mapper.customModule}"
        }
        return mediaKeys.handle(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = mediaKeys.handle(event) || super.onKeyUp(keyCode, event)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SidetrackLinks.handle(intent)
    }

    @Composable
    override fun SidekitContent() {
        val context = LocalContext.current
        val nav = rememberNavController()
        // Pick the first screen before the NavHost composes, so a signed-out launch never draws
        // Home for a frame and then replaces it with Sign in. A fresh install starts at the
        // service picker, which is the only place a service is chosen; a signed-in install that
        // has not finished the welcome yet starts there once, including existing users after an
        // update.
        val start = remember(context) {
            val session = AppSessions.get(context)
            when {
                !session.provisioned -> SIDE_PLAY_SERVICE_PICKER
                !session.signedIn.value -> SIDE_PLAY_SIGN_IN
                SidetrackLocalSettings(context).needsOnboarding() -> SIDE_PLAY_ONBOARDING
                else -> SIDE_PLAY_START
            }
        }
        DisposableEffect(nav) {
            navController = nav
            onDispose {
                if (navController === nav) navController = null
                keypadBackOverride = null
            }
        }
        SidetrackLinks.Follow(nav)
        val home = homeRequests.intValue
        LaunchedEffect(home) {
            if (home > 0) nav.popBackStack(SIDE_PLAY_START, inclusive = false)
        }
        val search = searchRequests.intValue
        LaunchedEffect(search) {
            if (search > 0) nav.navigate(SIDE_PLAY_SEARCH) { launchSingleTop = true }
        }
        // One read for the whole graph: every list row follows the same density, and switching
        // the setting recomposes the graph rather than each list re-reading the preference.
        val compactRows = rememberCompactRows()
        CompositionLocalProvider(LocalCompactRows provides compactRows) {
            NavHost(navController = nav, startDestination = SIDE_PLAY_ROOT) {
                sidetrackGraph(
                    nav,
                    startDestination = start,
                    hostBacked = stores.isHostBacked,
                    onKeypadBackOverride = ::setKeypadBackOverride
                )
            }
        }
    }

    private companion object {
        const val LOCAL_SETTINGS = "sidetrack_settings"
        const val LOCAL_KEYPAD = "sidetrack_keypad"
    }
}
