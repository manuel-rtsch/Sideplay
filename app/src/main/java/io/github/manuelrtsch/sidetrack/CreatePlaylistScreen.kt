package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitTextField
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * Names a new playlist for the active service. [onSubmit] receives the trimmed name; the caller
 * creates it on the service and opens the result. The create row is only reachable once a name is
 * typed, so an empty or whitespace-only name cannot create an untitled playlist.
 */

@Composable
fun CreatePlaylistScreen(
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onSubmit: (String) -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    val createFocus = remember { FocusRequester() }
    val canCreate = name.trim().isNotEmpty()
    val actions = sidetrackActions(
        chrome,
        onDismissNotice,
        onOpenNowPlaying = {},
        onDismissNowPlaying = onDismissNowPlaying,
        actions = remember { listOf(SideAction.back("back", "Library", onBack)) }
    )
    SidekitScaffold(
        title = "New playlist",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "name") {
                SidekitTextField(
                    name,
                    { name = it },
                    Modifier.fillMaxWidth().padding(horizontal = SidekitTheme.spacing.itemPadding)
                        .semantics { contentDescription = "Playlist name" },
                    textStyle = SidekitTheme.typography.title,
                    placeholder = "Playlist name",
                    singleLine = true,
                    autoFocus = true,
                    focusOnKeypad = true,
                    onDone = { if (canCreate) createFocus.requestFocus() }
                )
            }
            item(key = "create") {
                SidetrackItem(
                    "Create playlist",
                    Modifier.listFade(listState, 1)
                        .then(if (canCreate) Modifier.focusRequester(createFocus) else Modifier)
                        .semantics { if (!canCreate) disabled() },
                    icon = SidekitIcon.PlaylistAdd,
                    showLinkArrow = true,
                    onClick = { if (canCreate) onSubmit(name.trim()) }
                )
            }
        }
    }
}
