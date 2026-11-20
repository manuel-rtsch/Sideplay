package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import hrln.interactive.sidekit.design.action.LocalChrome
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCompactItem
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.input.Keypad
import hrln.interactive.sidekit.design.input.KeypadModule
import hrln.interactive.sidekit.design.input.LocalKeypad
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.input.SharedPreferencesKeypadPreferences
import hrln.interactive.sidekit.sdk.settings.KeypadGuide
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The one-time welcome shown after sign-in. It teaches the three keypad actions a new listener
 * needs before Home, keeps the module and Back-key status compact, and leaves the full key guide
 * on the Keyboard screen instead of asking the user to explore a diagram first.
 *
 * The same body is the picker's Help page ([onBack] non-null): it then reads the same way but
 * returns instead of starting the app, and the onboarding flag stays unwritten so the real welcome
 * still happens once a service is chosen.
 *
 * The page teaches the module that is attached right now. It follows Sidekit's detection while it
 * is open, and a tap on the Keyboard row steps to the next module by hand, so a keypad Sidekit
 * guessed wrong can be corrected without leaving the page the instructions are on.
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
    // The attached module is the truth and it can change while this screen is open: swapping the
    // module, or pressing a key Sidekit has not seen yet, re-detects it (Keypad.observe). Reading
    // it here is what makes the page follow along without being reopened.
    val attached = keypad?.module ?: custom ?: keypadPrefs.module() ?: KeypadModule.T9
    // A manual cycle holds only until the next key reveals a different module, so the page can
    // never settle on a keypad that is not the one attached.
    var manual by remember { mutableStateOf<KeypadModule?>(null) }
    LaunchedEffect(attached) { if (manual != null && manual != attached) manual = null }
    val module = manual ?: attached
    LaunchedEffect(keypad) {
        SidetrackDebug.log {
            "welcome keypad=${keypad != null} module=${keypad?.module} custom=${keypad?.mapper?.customModule}"
        }
        val attachedKeypad = keypad ?: return@LaunchedEffect
        snapshotFlow { attachedKeypad.module to attachedKeypad.mapper.customModule }
            .distinctUntilChanged()
            .collect { (shown, customModule) ->
                SidetrackDebug.log("welcome module=$shown custom=$customModule")
            }
    }
    SidetrackOnboardingContent(
        module = module,
        customMapping = custom != null,
        followsHardware = manual == null,
        canOpenKeyboard = !hostBacked,
        onOpenKeyboard = onKeyboard,
        onCycleKeyboard = {
            val next = nextKeypadModule(module)
            manual = next
            when {
                // Without a keypad there is nothing to re-map, so the choice is only remembered.
                keypad == null -> keypadPrefs.writeModule(next)

                // A custom mapping fixes the module by design: cycling it is choosing which module
                // the attached keys are read as, which is exactly what useCustomMapping does.
                keypad.mapper.customModule != null -> keypad.useCustomMapping(next)

                else -> keypad.useModule(next)
            }
        },
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
    customMapping: Boolean = false,
    /** False once the user cycled by hand: their choice outlives the keys they press after it. */
    followsHardware: Boolean = true,
    canOpenKeyboard: Boolean = true,
    onOpenKeyboard: () -> Unit = {},
    onCycleKeyboard: () -> Unit = {},
    canManageKeypadAccess: Boolean = false,
    keypadBackEnabled: Boolean = false,
    onAbout: () -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null
) {
    val list = rememberLazyListState()
    val keys = rememberWelcomeKeys(module, LocalKeypad.current, followsHardware)
    val optionsOpen = LocalChrome.current?.optionsOpen == true

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
        options = sidetrackOptions(aboutOption(onAbout))
    ) { padding ->
        SidetrackKeypadWelcomeList(
            padding = padding,
            list = list,
            modifier = keys.modifier,
            module = module,
            customMapping = customMapping,
            canOpenKeyboard = canOpenKeyboard,
            onOpenKeyboard = onOpenKeyboard,
            onCycleKeyboard = onCycleKeyboard,
            canManageKeypadAccess = canManageKeypadAccess,
            keypadBackEnabled = keypadBackEnabled,
            pressed = keys.pressed,
            optionsOpen = optionsOpen
        )
    }
}

/**
 * The key presses the page answers, and the modifier that lets it hear them.
 *
 * A page that only reads the attached module still teaches silently: press a key and get nothing
 * back and it reads as "not recognised". The screen therefore watches the keys that reach it,
 * asks Sidekit to re-detect the module from them, and lights up the row the press demonstrated.
 * The modifier never consumes an event, so every key keeps its normal job.
 */
private class WelcomeKeys(val pressed: KeypadHabit?, val modifier: Modifier)

@Composable
private fun rememberWelcomeKeys(
    module: KeypadModule,
    keypad: Keypad?,
    followsHardware: Boolean
): WelcomeKeys {
    val focus = remember { FocusRequester() }
    var pressed by remember { mutableStateOf<KeypadHabit?>(null) }
    // Compose only receives key events for a focused node, and this screen started with none: the
    // first press went to the platform and the page looked deaf until focus was moved by hand.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(pressed) {
        if (pressed == null) return@LaunchedEffect
        delay(1_500L)
        pressed = null
    }
    val modifier = Modifier
        .focusRequester(focus)
        .focusable()
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (native.action == KeyEvent.ACTION_DOWN) {
                // Detection belongs to Sidekit, but asking again from the keys this screen sees is
                // what makes a module swapped while the page is open teach its keys immediately.
                keypad?.observe(native)
                // Sidekit's detector cannot read a shared key such as the D-pad, so the keypad that
                // sent this one is the better answer - unless the user has already overruled it.
                if (followsHardware) {
                    keypad?.followInputDevice(keypadModuleForDevice(native.device?.name))
                }
                keypadHabitForKey(module, native.keyCode)?.let { pressed = it }
            }
            false
        }
    return WelcomeKeys(pressed, modifier)
}

@Composable
private fun SidetrackKeypadWelcomeList(
    padding: PaddingValues,
    list: LazyListState,
    modifier: Modifier,
    module: KeypadModule,
    customMapping: Boolean,
    canOpenKeyboard: Boolean,
    onOpenKeyboard: () -> Unit,
    onCycleKeyboard: () -> Unit,
    canManageKeypadAccess: Boolean,
    keypadBackEnabled: Boolean,
    pressed: KeypadHabit?,
    optionsOpen: Boolean
) {
    // A list only pads its rows a few pixels apart, which reads as one column. The page's groups
    // get a wider break so the selector, the card and the guide each read as their own block.
    val section = Modifier.padding(top = SidekitTheme.spacing.pageGap)
    SidekitList(padding, modifier = modifier, state = list) {
        // The selector opens the page: on a first run the module in front of the user is the first
        // thing to agree on, and on a host-backed install this is the only place to change it.
        item(key = "keyboard") {
            SidekitCompactItem("Select keyboard", secondary = module.label, onClick = onCycleKeyboard)
        }
        item(key = "intro") {
            SidekitText(
                "Learn your keypad.",
                section.padding(horizontal = 18.dp),
                style = SidekitTheme.typography.caption,
                maxLines = 1
            )
        }
        item(key = "essentials") {
            KeypadEssentials(
                module,
                modifier = section,
                customMapping = customMapping,
                pressed = pressed,
                optionsOpen = optionsOpen
            )
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
                section.padding(horizontal = 18.dp),
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
        if (canOpenKeyboard) {
            item(key = "keyboardSettings") {
                val backStatus = when {
                    !canManageKeypadAccess -> null
                    keypadBackEnabled -> "Back on"
                    else -> "Back off"
                }
                SidekitCompactItem(
                    "Keyboard settings",
                    modifier = section,
                    secondary = listOfNotNull("Input, mapping, guide", backStatus).joinToString(" · ") + " ›",
                    onClick = onOpenKeyboard
                )
            }
        }
    }
}
