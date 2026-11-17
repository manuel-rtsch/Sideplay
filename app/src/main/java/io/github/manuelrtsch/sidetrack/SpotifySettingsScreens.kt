package io.github.manuelrtsch.sidetrack

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.ItemColour
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

private enum class SettingsConfirmation { SignOut, ClearCache, ClearHistory }

@Composable
fun SpotifySettingsScreen(
    account: AccountUiState,
    chrome: ChromeUiState,
    recentCount: Int,
    onBack: () -> Unit,
    onAudioQuality: () -> Unit,
    onNormalization: () -> Unit,
    onGapless: () -> Unit,
    onAutoplay: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    onAppearance: (() -> Unit)? = null,
    onKeyboard: (() -> Unit)? = null,
    onAbout: () -> Unit = {}
) {
    var confirmation by remember { mutableStateOf<SettingsConfirmation?>(null) }
    val backdrop = rememberArtworkBackdropSetting()
    val context = LocalContext.current
    val back = remember { spotifyBack("Back", onBack).first() }
    val baseActions = remember(back) { listOf(back, SideAction.forward("about", "About", onClick = onAbout)) }
    val normal = withNotice(
        chrome.notice,
        onDismissNotice,
        baseActions
    )
    val actions = when (confirmation) {
        SettingsConfirmation.SignOut -> listOf(
            SideAction.cancel("cancelSignOut", "Cancel") { confirmation = null },
            SideAction.delete("confirmSignOut", "Sign out", onClick = {
                confirmation = null
                onSignOut()
            })
        )

        SettingsConfirmation.ClearCache -> listOf(
            SideAction.cancel("cancelClearCache", "Cancel") { confirmation = null },
            SideAction.delete("confirmClearCache", "Clear", onClick = {
                confirmation = null
                onClearCache()
            })
        )

        SettingsConfirmation.ClearHistory -> listOf(
            SideAction.cancel("cancelClearHistory", "Cancel") { confirmation = null },
            SideAction.delete("confirmClearHistory", "Clear", onClick = {
                confirmation = null
                onClearHistory()
            })
        )

        null -> normal
    }
    SidekitScaffold(
        title = "Settings",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            item(key = "account") {
                SidetrackItem(
                    "Account",
                    Modifier.listFade(listState, 0).focusRequester(first),
                    secondary = if (account.signedIn) "Signed in" else "Not signed in",
                    secondaryToEnd = true,
                    onClick = if (account.signedIn) {
                        { confirmation = SettingsConfirmation.SignOut }
                    } else {
                        null
                    }
                )
            }
            item(key = "quality") {
                SidetrackItem(
                    "Audio quality",
                    Modifier.listFade(listState, 1),
                    secondary = account.audioQuality.label,
                    secondaryToEnd = true,
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = onAudioQuality
                )
            }
            item(key = "autoplay") {
                SidetrackItem(
                    "Autoplay",
                    Modifier.listFade(listState, 2),
                    secondary = if (account.autoplay) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = account.autoplay,
                    onClick = onAutoplay
                )
            }
            item(key = "normalization") {
                SidetrackItem(
                    "Normalize volume",
                    Modifier.listFade(listState, 3),
                    secondary = if (account.normalization) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = account.normalization,
                    onClick = onNormalization
                )
            }
            item(key = "gapless") {
                SidetrackItem(
                    "Gapless playback",
                    Modifier.listFade(listState, 4),
                    secondary = if (account.gapless) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = account.gapless,
                    onClick = onGapless
                )
            }
            item(key = "cache") {
                SidetrackItem(
                    "Clear cache",
                    Modifier.listFade(listState, 5),
                    secondary = Formatter.formatShortFileSize(context, account.cacheSizeBytes),
                    secondaryToEnd = true,
                    onClick = { confirmation = SettingsConfirmation.ClearCache }
                )
            }
            item(key = "history") {
                SidetrackItem(
                    "Clear history",
                    Modifier.listFade(listState, 6),
                    secondary = recentCount.toString(),
                    secondaryToEnd = true,
                    onClick = { confirmation = SettingsConfirmation.ClearHistory }
                )
            }
            item(key = "artworkBackdrop") {
                SidetrackItem(
                    "Art backdrop",
                    Modifier.listFade(listState, 7),
                    secondary = if (backdrop.enabled) "On" else "Off",
                    secondaryToEnd = true,
                    toggleable = backdrop.enabled,
                    onClick = backdrop::toggle
                )
            }
            if (onAppearance != null) {
                item(key = "appearance") {
                    SidetrackItem(
                        "Appearance",
                        Modifier.listFade(listState, 8),
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onAppearance
                    )
                }
            }
            if (onKeyboard != null) {
                item(key = "keyboard") {
                    SidetrackItem(
                        "Keyboard",
                        Modifier.listFade(listState, 9),
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onKeyboard
                    )
                }
            }
        }
    }
}

@Composable
fun SpotifyAudioQualityScreen(
    account: AccountUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onSelect: (AudioQuality) -> Unit,
    onDismissNotice: () -> Unit
) {
    val baseActions = remember { listOf(SideAction.back("back", "Settings", onBack)) }
    val actions = withNotice(
        chrome.notice,
        onDismissNotice,
        baseActions
    )
    SidekitScaffold(
        title = "Audio quality",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, modifier = Modifier.selectableGroup(), state = listState) {
            AudioQuality.entries.forEachIndexed { index, quality ->
                val selected = quality == account.audioQuality
                item(key = quality.name) {
                    SidetrackItem(
                        quality.label,
                        Modifier.listFade(listState, index).then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        secondary = "${quality.bitrateKbps} kbps",
                        secondaryToEnd = true,
                        secondaryNoEllipsis = true,
                        icon = if (selected) SidekitIcon.Check else null,
                        colour = if (selected) ItemColour.Secondary else ItemColour.Primary,
                        selected = selected,
                        selectionRole = Role.RadioButton,
                        onClick = { onSelect(quality) }
                    )
                }
            }
        }
    }
}

@Composable
fun SpotifySignInScreen(
    account: AccountUiState,
    chrome: ChromeUiState,
    onAbout: () -> Unit,
    onSignIn: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val colors = SidekitTheme.colors
    val baseActions = remember {
        listOf(
            SideAction("about", "About", tone = ActionTone.Muted, motion = ActionMotion.Up, onClick = onAbout),
            SideAction.forward("signIn", "Sign in", onSignIn)
        )
    }
    val actions = withNotice(
        chrome.notice,
        onDismissNotice,
        baseActions
    )
    SidekitScaffold(
        title = "Sign in",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "explain") {
                SidekitCard(Modifier.listFade(listState, 0).fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SidekitText(
                            "Connect Sidetrack with your Spotify account",
                            style = SidekitTheme.typography.bodySmall,
                            maxLines = Int.MAX_VALUE
                        )
                        SidekitText(
                            "Sidetrack never sees your password or sensitive information.",
                            style = SidekitTheme.typography.caption,
                            color = colors.surfaceA6,
                            maxLines = Int.MAX_VALUE
                        )
                    }
                }
            }
            item(key = "status") {
                SidetrackItem(
                    "Status",
                    Modifier.listFade(listState, 1),
                    secondary = when {
                        account.authPending -> "Waiting for browser"
                        account.signedIn -> "Connected"
                        else -> "Waiting"
                    },
                    secondaryToEnd = true,
                    colour = ItemColour.Muted
                )
            }
        }
    }
}
