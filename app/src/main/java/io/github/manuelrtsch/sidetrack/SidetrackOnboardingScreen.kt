package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCompactItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.settings.KeypadGuide

/**
 * The one-time welcome shown after sign-in. It teaches the three keypad actions a new listener
 * needs before Home, keeps the module and Back-key status compact, and leaves the full key guide
 * on the Keyboard screen instead of asking the user to explore a diagram first.
 *
 * The same body is the picker's Help page ([onBack] non-null): it then reads the same way but
 * returns instead of starting the app, and the onboarding flag stays unwritten so the real welcome
 * still happens once a service is chosen.
 *
 * A Sidekit host owns the keypad choice, so with a host installed the keyboard row is
 * informational and the module and input mode keep coming from Sidekit.
 */
@Composable
fun SidetrackOnboardingScreen(
    hostBacked: Boolean,
    onAbout: () -> Unit,
    onKeyboard: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current.applicationContext
    val local = remember(context) { SidetrackLocalSettings(context) }
    val keypad = LocalKeypad.current
    val keypadPrefs = remember(context) {
        SharedPreferencesKeypadPreferences(context, SidetrackLocalSettings.KEYPAD_FILE)
    }
    val custom = keypad?.mapper?.customModule ?: keypadPrefs.customModule()
    var accessRevision by remember { mutableIntStateOf(0) }
    val keypadBackEnabled = remember(accessRevision) { SidetrackKeypadAccess.isEnabled(context) }
    // Android does not report an accessibility-service change through a callback. Refresh when
    // the user comes back from the Keyboard screen or the system Accessibility screen.
    LifecycleResumeEffect(Unit) {
        accessRevision++
        onPauseOrDispose {}
    }
    val module = keypad?.module ?: custom ?: keypadPrefs.module() ?: KeypadModule.T9
    val keyboardLabel = when {
        hostBacked -> "${module.label} · Sidekit"
        custom != null -> "${custom.label} · custom"
        else -> module.label
    }

    SidetrackOnboardingContent(
        module = module,
        keyboardLabel = keyboardLabel,
        customMapping = custom != null,
        canOpenKeyboard = !hostBacked,
        onOpenKeyboard = onKeyboard,
        canManageKeypadAccess = !hostBacked,
        keypadBackEnabled = keypadBackEnabled,
        onAbout = onAbout,
        onBack = onBack,
        onDone = {
            local.markOnboardingSeen()
            onDone()
        }
    )
}

/**
 * Presentational body, kept separate so the screenshot suite can draw a deterministic keypad
 * without a real Sidekit host.
 */
@Composable
internal fun SidetrackOnboardingContent(
    module: KeypadModule,
    keyboardLabel: String,
    customMapping: Boolean = false,
    canOpenKeyboard: Boolean = true,
    onOpenKeyboard: () -> Unit = {},
    canManageKeypadAccess: Boolean = false,
    keypadBackEnabled: Boolean = false,
    onAbout: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val list = rememberLazyListState()

    SidekitScaffold(
        title = "Welcome",
        actions = listOf(
            // Help mode returns to the picker; the first run keeps its single way forward.
            if (onBack != null) {
                SideAction.back("back", "Back", onBack)
            } else {
                SideAction.forward("getStarted", "Get started", onDone)
            }
        ),
        // A real extra action, so pressing Options here demonstrates what the card explains.
        options = listOf(
            SideAction("about", "About Sidetrack", SidekitIcon.BrandAwareness, onClick = onAbout)
        )
    ) { padding ->
        SidetrackKeypadWelcomeList(
            padding = padding,
            list = list,
            module = module,
            keyboardLabel = keyboardLabel,
            customMapping = customMapping,
            canOpenKeyboard = canOpenKeyboard,
            onOpenKeyboard = onOpenKeyboard,
            canManageKeypadAccess = canManageKeypadAccess,
            keypadBackEnabled = keypadBackEnabled
        )
    }
}

@Composable
private fun SidetrackKeypadWelcomeList(
    padding: PaddingValues,
    list: LazyListState,
    module: KeypadModule,
    keyboardLabel: String,
    customMapping: Boolean,
    canOpenKeyboard: Boolean,
    onOpenKeyboard: () -> Unit,
    canManageKeypadAccess: Boolean,
    keypadBackEnabled: Boolean
) {
    SidekitList(padding, state = list) {
        item(key = "intro") {
            SidekitText(
                "Learn three keypad habits.",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.caption,
                maxLines = 1
            )
        }
        item(key = "essentials") {
            KeypadEssentials(module, customMapping = customMapping)
        }
        item(key = "tryIt") {
            SidekitText(
                "Try it: press Options now — About Sidetrack is there.",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.small,
                color = SidekitTheme.colors.surfaceA6,
                maxLines = 2
            )
        }
        item(key = "guideTitle") {
            SidekitText(
                "Where those keys are",
                Modifier.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.caption,
                maxLines = 1
            )
        }
        item(key = "guide") { KeypadGuide(module) }
        if (customMapping) {
            item(key = "guideCustom") {
                SidekitText(
                    "A custom mapping is active; the guide shows the module’s default labels.",
                    Modifier.padding(horizontal = 18.dp),
                    style = SidekitTheme.typography.tiny,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = 2
                )
            }
        }
        item(key = "keyboard") {
            val backStatus = when {
                !canManageKeypadAccess -> null
                keypadBackEnabled -> "Back on"
                else -> "Back off"
            }
            SidekitCompactItem(
                "Keyboard",
                secondary = if (canOpenKeyboard) {
                    listOfNotNull(keyboardLabel, backStatus).joinToString(" · ") + " ›"
                } else {
                    keyboardLabel
                },
                onClick = if (canOpenKeyboard) onOpenKeyboard else null
            )
        }
    }
}
