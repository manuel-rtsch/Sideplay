package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.manuelrtsch.sidetrack.data.AppSessions
import io.github.manuelrtsch.sidetrack.data.KnownServices
import io.github.manuelrtsch.sidetrack.data.SignInKind
import kotlinx.coroutines.launch

internal fun NavGraphBuilder.settingsGraph(
    nav: NavHostController,
    hostBacked: Boolean
) {
    composable(SETTINGS) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val account by vm.accountState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        val playback by vm.playbackState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val appSession = remember(context) { AppSessions.get(context) }
        LaunchedEffect(Unit) { vm.refreshCacheSize() }
        SettingsScreen(
            account,
            chrome,
            playback.recent.size,
            serviceName = appSession.serviceName,
            capabilities = vm.capabilities,
            onBack = { nav.popBackStack() },
            onAudioQuality = { nav.navigate(AUDIO_QUALITY) },
            onNormalization = vm::toggleNormalization,
            onGapless = vm::toggleGapless,
            onAutoplay = vm::toggleAutoplay,
            onClearCache = vm::clearCache,
            onClearHistory = vm::clearHistory,
            onUndoClearHistory = vm::undoClearHistory,
            onSignOut = {
                vm.signOut()
                // Signing out is the only route back to the picker: choosing a service is a
                // provisioning step, not a setting, so it must not happen behind a live app.
                nav.navigate(SERVICE_PICKER) {
                    popUpTo(ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            },
            onDismissNotice = vm::dismissNotice,
            // A host-backed Sidekit owns the theme and the keypad, so the Keyboard row is hidden
            // rather than offering edits the host would ignore. Appearance stays: the host owns
            // the case colour and compact layout inside it, but not Sidetrack's own row density
            // or its Now Playing backdrop, and the screen drops the host-owned rows itself.
            onAppearance = { nav.navigate(APPEARANCE) },
            onKeyboard = if (hostBacked) null else ({ nav.navigate(KEYBOARD) }),
            onAbout = { nav.navigate(ABOUT) }
        )
    }
    composable(SERVICE_PICKER) {
        val context = LocalContext.current
        val appSession = remember(context) { AppSessions.get(context) }
        // The picker is the first screen on a fresh install and a pushed screen when it comes
        // from Sign in. Either way it is the same neutral screen: no pre-selection, and the
        // same two bar actions (About, Help) rather than a back button.
        ServicePickerScreen(
            services = KnownServices,
            onAbout = { nav.navigate(ABOUT) },
            onHelp = { nav.navigate(HELP) },
            onSelect = { id ->
                if (!AppSessions.select(context, id)) {
                    false
                } else {
                    // A service that still holds credentials goes straight in; a new one starts
                    // at sign-in, exactly as it would on the next launch.
                    val next = if (appSession.signedIn.value) HOME else SIGN_IN
                    nav.navigate(next) {
                        popUpTo(ROOT) { inclusive = false }
                        launchSingleTop = true
                    }
                    true
                }
            }
        )
    }
    composable(AUDIO_QUALITY) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val account by vm.accountState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        AudioQualityScreen(
            account,
            chrome,
            onBack = { nav.popBackStack() },
            onSelect = vm::setAudioQuality,
            onDismissNotice = vm::dismissNotice
        )
    }
    composable(APPEARANCE) {
        SidetrackAppearanceScreen(hostBacked = hostBacked, onBack = { nav.popBackStack() })
    }
    composable(KEYBOARD) {
        val backLabel = if (nav.previousBackStackEntry?.destination?.route in setOf(ONBOARDING, HELP)) {
            "Welcome"
        } else {
            "Settings"
        }
        SidetrackKeyboardScreen(
            onBack = { nav.popBackStack() },
            backLabel = backLabel,
            keypadAccessAvailable = !hostBacked
        )
    }
    composable(ABOUT) {
        val backLabel = when (nav.previousBackStackEntry?.destination?.route) {
            SIGN_IN -> "Sign in"
            ONBOARDING, HELP -> "Welcome"
            else -> "Settings"
        }
        SidetrackAboutScreen(onBack = { nav.popBackStack() }, backLabel = backLabel)
    }
    composable(ONBOARDING) {
        SidetrackOnboardingScreen(
            hostBacked = hostBacked,
            onAbout = { nav.navigate(ABOUT) },
            onKeyboard = { nav.navigate(KEYBOARD) },
            onDone = {
                nav.navigate(HOME) {
                    // Replace Welcome (and anything still below it, such as Sign in), so Back
                    // from Home leaves Sidetrack instead of replaying the welcome. The flag is
                    // written by the screen before this runs.
                    popUpTo(ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            }
        )
    }
    composable(HELP) {
        // The same Welcome body the first run shows, opened before a service is chosen: it
        // teaches the keypad, so it is the most useful thing a first-run user can read here.
        SidetrackOnboardingScreen(
            hostBacked = hostBacked,
            onAbout = { nav.navigate(ABOUT) },
            onKeyboard = { nav.navigate(KEYBOARD) },
            onDone = { nav.popBackStack() },
            onBack = { nav.popBackStack() }
        )
    }
    composable(SIGN_IN) { entry ->
        val vm = entry.sidetrackViewModel(nav)
        val account by vm.accountState.collectAsStateWithLifecycle()
        val chrome by vm.chromeState.collectAsStateWithLifecycle()
        val context = LocalContext.current
        LaunchedEffect(account.signedIn) {
            if (account.signedIn) {
                val next = if (SidetrackLocalSettings(context).needsOnboarding()) ONBOARDING else HOME
                nav.navigate(next) {
                    popUpTo(ROOT) { inclusive = false }
                    launchSingleTop = true
                }
            }
        }
        val appSession = AppSessions.get(context)
        // The provider describes its own sign-in: a password form with optional public demo
        // defaults, or a browser/custom-tab flow with no fields. No shared code names a service.
        val signInKind = appSession.provider?.signInKind
        val passwordAuth = signInKind is SignInKind.Password
        val defaults = (signInKind as? SignInKind.Password)?.defaults?.let {
            PasswordSignInDefaults(it.serverUrl, it.username, it.password)
        }
        SignInScreen(
            chrome,
            onBack = {
                // Usually the picker is the step behind. A signed-out launch of a provisioned
                // install starts here with nothing behind, so Back opens the picker instead of
                // being a dead end — the same answer, reached without a state to check.
                if (!nav.popBackStack()) {
                    nav.navigate(SERVICE_PICKER) { launchSingleTop = true }
                }
            },
            onSignIn = vm::signIn,
            onDismissNotice = vm::dismissNotice,
            serviceName = appSession.serviceName.ifEmpty { "your music service" },
            passwordAuth = passwordAuth,
            defaults = defaults,
            onPasswordSignIn = vm::signIn
        )
    }
}
