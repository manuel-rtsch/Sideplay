package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme

/**
 * The cap on a playlist name: long enough for a record title, short enough that a pasted paragraph
 * does not become the name of a menu row.
 */
internal const val PLAYLIST_NAME_MAX = 100

/** How many recently played tracks the form offers as seeds; enough to build a short playlist. */
internal const val RECENT_SEED_LIMIT = 10

/**
 * Names a new playlist for the active service and optionally seeds it from [recentTracks]. The
 * newest [RECENT_SEED_LIMIT] distinct tracks are offered; nothing is selected until tapped. Tapping
 * a row toggles its membership and the checkmark takes the place of the duration, so a selected
 * track stays visibly selected even after the list scrolls.
 *
 * [onSubmit] receives the trimmed name and the seed keys in the order the tracks are shown. The
 * name is the boxed FormField the sign-in form uses. Create lives in the action bar next to Back
 * and only joins it once a name is typed; Done leaves the field without creating.
 */
@Composable
fun CreatePlaylistScreen(
    chrome: ChromeUiState,
    recentTracks: List<Track> = emptyList(),
    onBack: () -> Unit,
    onSubmit: (String, List<MediaKey>) -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    // Keys, not indices: the recent list can change while the form is open, and a key is what the
    // writer needs anyway.
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val focusManager = LocalFocusManager.current
    val canCreate = name.trim().isNotEmpty()
    val seeds = remember(recentTracks) {
        recentTracks.distinctBy { it.key.encode() }.take(RECENT_SEED_LIMIT)
    }
    val submit = {
        if (canCreate) {
            val seed = seeds.filter { it.key.encode() in selected }.map { it.key }
            onSubmit(name.trim(), seed)
        }
    }
    val actions = sidetrackActions(
        chrome,
        onDismissNotice,
        onOpenNowPlaying = {},
        onDismissNowPlaying = onDismissNowPlaying,
        actions = buildList {
            add(SideAction.back("back", "Library", onBack))
            if (canCreate) add(SideAction.forward("create", "Create", submit))
        }
    )
    SidekitScaffold(
        title = "New playlist",
        actions = actions
    ) { padding ->
        val listState = rememberLazyListState()
        SidekitList(padding, state = listState) {
            item(key = "name") {
                FormField(
                    name,
                    { typed -> name = typed },
                    label = "Playlist name",
                    placeholder = "e.g. Sunday morning",
                    modifier = Modifier.listFade(listState, 0),
                    fieldDescription = "Playlist name",
                    focusOnKeypad = true,
                    autoFocus = true,
                    imeAction = ImeAction.Done,
                    // Done finishes the name, it does not create: creating is the bar's button,
                    // pressed deliberately once the name and seeds read right.
                    onDone = { focusManager.clearFocus() },
                    maxLength = PLAYLIST_NAME_MAX
                )
            }
            if (seeds.isNotEmpty()) {
                item(key = "seedHeader") {
                    SectionLabel(
                        if (selected.isEmpty()) "Start with" else "Start with · ${selected.size} selected",
                        Modifier.listFade(listState, 1)
                    )
                }
                itemsIndexed(
                    seeds,
                    key = { _, track -> "seed:${track.key.encode()}" },
                    contentType = { _, _ -> "seed" }
                ) { index, track ->
                    val key = track.key.encode()
                    val chosen = key in selected
                    MediaRow(
                        track,
                        Modifier.listFade(listState, index + 2),
                        trailingContent = if (chosen) {
                            {
                                SidekitIconImage(
                                    SidekitIcon.Check,
                                    tint = SidekitTheme.colors.successA1
                                )
                            }
                        } else {
                            null
                        },
                        checked = chosen,
                        onClick = {
                            selected = if (chosen) selected - key else selected + key
                        }
                    )
                }
            }
        }
    }
}
