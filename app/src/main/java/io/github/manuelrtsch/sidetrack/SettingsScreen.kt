package io.github.manuelrtsch.sidetrack

import android.text.format.Formatter
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalContext
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.action.withNotice
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow

private enum class SettingsConfirmation { SignOut }

@Composable
fun SettingsScreen(
    account: AccountUiState,
    chrome: ChromeUiState,
    recentCount: Int,
    serviceName: String = "",
    capabilities: ServiceCapabilities = ServiceCapabilities.All,
    onBack: () -> Unit,
    onAudioQuality: () -> Unit,
    onNormalization: () -> Unit,
    onGapless: () -> Unit,
    onAutoplay: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Boolean,
    onUndoClearHistory: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    onAppearance: () -> Unit,
    onKeyboard: (() -> Unit)? = null,
    onAbout: () -> Unit = {}
) {
    var confirmation by remember { mutableStateOf<SettingsConfirmation?>(null) }
    // Clearing a list is immediate and reversible, so it offers Undo instead of a second press.
    var historyCleared by remember { mutableStateOf(false) }
    // The Undo offer is measured in visible time: leaving Settings commits the clear instead of
    // burning the window in the background.
    val undoWindow = rememberUndoWindow { historyCleared = false }
    UndoWindowLifecycle(undoWindow) { historyCleared = false }
    val context = LocalContext.current
    val back = remember { sidetrackBack("Back", onBack).first() }
    val baseActions = remember(back) { listOf(back, SideAction.forward("about", "About", onClick = onAbout)) }
    val normal = withNotice(chrome.notice, onDismissNotice, baseActions, chrome.noticeKind)
    val undo = if (historyCleared) {
        SideAction.undo("undoClearHistory", "Undo", "Undo clear history") {
            undoWindow.cancel()
            historyCleared = false
            onUndoClearHistory()
        }
    } else {
        null
    }
    val actions = when {
        confirmation == SettingsConfirmation.SignOut -> listOf(
            SideAction.cancel("cancelSignOut", "Cancel") { confirmation = null },
            SideAction.delete("confirmSignOut", "Sign out", onClick = {
                confirmation = null
                onSignOut()
            })
        )

        undo != null -> undoBar(back, undo)

        else -> normal
    }
    SidekitScaffold(
        title = "Settings",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            // listFade keys off the row's real index in the list. Conditional rows (quality,
            // normalization, gapless, keyboard) shift every index after them, so advance a counter
            // as each row is registered instead of hard-coding slots.
            var rowIndex = 0
            fun LazyListScope.row(key: String, content: @Composable (Modifier) -> Unit) {
                val index = rowIndex++
                item(key = key) { content(Modifier.listFade(listState, index)) }
            }

            row("account") { modifier ->
                // One row for the whole account: which service this is, and the sign-out it leads
                // to. A service is chosen once, before sign-in, so there is nothing to switch here;
                // naming it on the account row says which one signing out would leave.
                SidetrackItem(
                    "Account",
                    modifier.focusRequester(first),
                    secondary = if (account.signedIn) {
                        serviceName.ifEmpty { "Signed in" }
                    } else {
                        "Not signed in"
                    },
                    secondaryToEnd = true,
                    onClick = if (account.signedIn) {
                        { confirmation = SettingsConfirmation.SignOut }
                    } else {
                        null
                    }
                )
            }
            if (capabilities.supportsQualitySelection) {
                row("quality") { modifier ->
                    SidetrackItem(
                        "Audio quality",
                        modifier,
                        secondary = account.audioQuality.label,
                        secondaryToEnd = true,
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onAudioQuality
                    )
                }
            }
            row("autoplay") { modifier ->
                SidetrackItem(
                    "Autoplay",
                    modifier,
                    secondary = if (account.autoplay) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = account.autoplay,
                    onClick = onAutoplay
                )
            }
            if (capabilities.supportsNormalization) {
                row("normalization") { modifier ->
                    SidetrackItem(
                        "Normalize volume",
                        modifier,
                        secondary = if (account.normalization) "On" else "Off",
                        secondaryToEnd = true,
                        toggleable = account.normalization,
                        onClick = onNormalization
                    )
                }
            }
            if (capabilities.supportsGapless) {
                row("gapless") { modifier ->
                    SidetrackItem(
                        "Gapless playback",
                        modifier,
                        secondary = if (account.gapless) "On" else "Off",
                        secondaryToEnd = true,
                        toggleable = account.gapless,
                        onClick = onGapless
                    )
                }
            }
            row("cache") { modifier ->
                SidetrackItem(
                    "Clear cache",
                    modifier,
                    secondary = Formatter.formatShortFileSize(context, account.cacheSizeBytes),
                    secondaryToEnd = true,
                    onClick = onClearCache
                )
            }
            row("history") { modifier ->
                SidetrackItem(
                    "Clear history",
                    modifier,
                    secondary = recentCount.toString(),
                    secondaryToEnd = true,
                    onClick = {
                        historyCleared = onClearHistory()
                        if (historyCleared) undoWindow.start()
                    }
                )
            }
            row("appearance") { modifier ->
                SidetrackItem(
                    "Appearance",
                    modifier,
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = onAppearance
                )
            }
            if (onKeyboard != null) {
                row("keyboard") { modifier ->
                    SidetrackItem(
                        "Keyboard",
                        modifier,
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onKeyboard
                    )
                }
            }
        }
    }
}
