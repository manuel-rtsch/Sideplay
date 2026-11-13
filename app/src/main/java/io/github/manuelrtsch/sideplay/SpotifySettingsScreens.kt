package io.github.manuelrtsch.sideplay

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
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
import hrln.interactive.sidekit.design.theme.SidekitTheme

private enum class SettingsConfirmation { SignOut, ClearCache, ClearHistory }

@Composable
fun SpotifySettingsScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onAudioQuality: () -> Unit,
    onAutoplay: () -> Unit,
    onClearCache: () -> Unit,
    onClearHistory: () -> Unit,
    onSignOut: () -> Unit,
    onDismissNotice: () -> Unit,
    onAppearance: (() -> Unit)? = null,
    onAbout: () -> Unit = {}
) {
    var confirmation by remember { mutableStateOf<SettingsConfirmation?>(null) }
    val context = LocalContext.current
    val back = spotifyBack("Back", onBack).first()
    val normal = withNotice(
        state.notice,
        onDismissNotice,
        buildList {
            add(back)
            if (state.signedIn) {
                add(SideAction.delete("signOut", "Sign out", onClick = { confirmation = SettingsConfirmation.SignOut }))
            }
        }
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
                SideplayItem(
                    "Account",
                    Modifier.listFade(listState, 0).focusRequester(first),
                    secondary = if (state.signedIn) "Signed in" else "Not signed in",
                    secondaryToEnd = true
                )
            }
            item(key = "autoplay") {
                SideplayItem(
                    "Autoplay",
                    Modifier.listFade(listState, 1),
                    secondary = if (state.autoplay) "On" else "Off",
                    secondaryToEnd = true,
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = onAutoplay
                )
            }
            item(key = "quality") {
                SideplayItem(
                    "Audio quality",
                    Modifier.listFade(listState, 2),
                    secondary = "${state.audioQuality.bitrateKbps} kbps",
                    secondaryToEnd = true,
                    secondaryNoEllipsis = true,
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = onAudioQuality
                )
            }
            item(key = "cache") {
                SideplayItem(
                    "Clear cache",
                    Modifier.listFade(listState, 3),
                    secondary = Formatter.formatShortFileSize(context, state.cacheSizeBytes),
                    secondaryToEnd = true,
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = { confirmation = SettingsConfirmation.ClearCache }
                )
            }
            item(key = "history") {
                SideplayItem(
                    "Clear history",
                    Modifier.listFade(listState, 4),
                    secondary = "${state.history.size} items",
                    secondaryToEnd = true,
                    showLinkArrow = true,
                    showLinkArrowWithSecondary = true,
                    onClick = { confirmation = SettingsConfirmation.ClearHistory }
                )
            }
            if (onAppearance != null) {
                item(key = "appearance") {
                    SideplayItem(
                        "Theme and keypad",
                        Modifier.listFade(listState, 5),
                        secondary = "Sideplay",
                        secondaryToEnd = true,
                        showLinkArrow = true,
                        showLinkArrowWithSecondary = true,
                        onClick = onAppearance
                    )
                }
            }
            val aboutIndex = if (onAppearance != null) 6 else 5
            item(key = "about") {
                SideplayItem(
                    "About Sideplay",
                    Modifier.listFade(listState, aboutIndex),
                    showLinkArrow = true,
                    onClick = onAbout
                )
            }
        }
    }
}

@Composable
fun SpotifyAudioQualityScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onSelect: (AudioQuality) -> Unit,
    onDismissNotice: () -> Unit
) {
    val actions = withNotice(
        state.notice,
        onDismissNotice,
        listOf(SideAction.back("back", "Settings", onBack))
    )
    SidekitScaffold(
        title = "Audio quality",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
        SidekitList(padding, state = listState) {
            AudioQuality.entries.forEachIndexed { index, quality ->
                val selected = quality == state.audioQuality
                item(key = quality.name) {
                    SideplayItem(
                        quality.label,
                        Modifier.listFade(listState, index).then(
                            if (index == 0) Modifier.focusRequester(first) else Modifier
                        ),
                        secondary = "${quality.bitrateKbps} kbps",
                        secondaryToEnd = true,
                        secondaryNoEllipsis = true,
                        colour = if (selected) ItemColour.Secondary else ItemColour.Primary,
                        onClick = { onSelect(quality) }
                    )
                }
            }
        }
    }
}

@Composable
fun SpotifySignInScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onLogIn: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val colors = SidekitTheme.colors
    val actions = withNotice(
        state.notice,
        onDismissNotice,
        listOf(backToLauncher(onBack), SideAction.forward("login", "Log in", onLogIn))
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
                        SidekitText("Sideplay plays from your Spotify account.", style = SidekitTheme.typography.bodySmall, maxLines = 2)
                        SidekitText(
                            "Your password is entered in the browser, never here.",
                            style = SidekitTheme.typography.caption,
                            color = colors.surfaceA6,
                            maxLines = 2
                        )
                    }
                }
            }
            item(key = "login") {
                SideplayItem(
                    "Log in with Spotify",
                    Modifier.listFade(listState, 1),
                    showLinkArrow = true,
                    onClick = onLogIn
                )
            }
            item(key = "status") {
                SideplayItem(
                    "Status",
                    Modifier.listFade(listState, 2),
                    secondary = when {
                        state.authPending -> "Waiting for browser"
                        state.signedIn -> "Connected"
                        else -> "Waiting"
                    },
                    secondaryToEnd = true,
                    colour = ItemColour.Muted
                )
            }
        }
    }
}

private fun backToLauncher(onBack: () -> Unit) = SideAction.back("back", "Exit", onBack)
