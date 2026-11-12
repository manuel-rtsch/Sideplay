package io.github.manuelrtsch.sideplay

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.SwipeAction
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup
import kotlinx.coroutines.delay

@Composable
fun SpotifyNowPlayingScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onSave: () -> Unit,
    onQueue: () -> Unit,
    onAlbum: () -> Unit,
    onShow: () -> Unit,
    onArtist: () -> Unit,
    onUndo: () -> Unit,
    onDismissNotice: () -> Unit
) {
    val current = state.current
    // Episodes have no saved-track endpoint, so Save is a track-only action.
    val save = (current as? SpotifyTrack)?.let {
        SideAction.square(
            "save",
            if (state.savedCurrent) SidekitIcon.Bookmark else SidekitIcon.Bookmark,
            if (state.savedCurrent) "Saved" else "Save",
            onSave,
            tone = if (state.savedCurrent) ActionTone.Secondary else ActionTone.Muted,
            motion = ActionMotion.Up
        )
    }
    val actions = spotifyBar(
        state,
        onDismissNotice,
        onUndo,
        spotifyBack("Back", onBack).first(),
        listOfNotNull(save)
    )
    val options = if (current == null) {
        emptyList()
    } else {
        buildList {
            add(SideAction("queue", "Queue", SidekitIcon.QueueMusic, onClick = onQueue))
            if (current is SpotifyTrack) add(SideAction("album", "Album", SidekitIcon.Album, onClick = onAlbum))
        }
    }
    SidekitScaffold(title = null, actions = actions, options = options) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyMessage("Nothing playing", hint = "Pick something from your library")
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val art = minOf(maxWidth, maxHeight)
                    SpotifyCover(
                        current.artRes,
                        Modifier.size(art),
                        shape = SidekitTheme.shapes.item,
                        bordered = true,
                        contentDescription = "${current.title} artwork",
                        artUrl = current.artUrl,
                        onClick = when (current) {
                            is SpotifyTrack -> onAlbum
                            is SpotifyEpisode -> onShow
                        },
                        onClickLabel = when (current) {
                            is SpotifyTrack -> "Open album"
                            is SpotifyEpisode -> "Open show"
                        }
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SidekitText(
                        current.title,
                        Modifier.fillMaxWidth(),
                        style = SidekitTheme.typography.headline,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                    if (current.creator.isNotBlank()) {
                        SidekitText(
                            current.creator,
                            Modifier.fillMaxWidth().clickable(enabled = current.artistId != null, onClickLabel = "Open artist", onClick = onArtist),
                            style = SidekitTheme.typography.label,
                            color = SidekitTheme.colors.surfaceA6,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                SpotifySeek(state.positionMs, current.durationMs, onSeek)
                SidekitItemRow {
                    SpotifyIconTile(
                        SidekitIcon.Shuffle,
                        if (state.shuffle) "Shuffle on" else "Shuffle off",
                        onShuffle,
                        tone = if (state.shuffle) SpotifyTileTone.On else SpotifyTileTone.Neutral,
                        weight = 0.85f
                    )
                    SpotifyIconTile(SidekitIcon.SkipPrevious, "Previous", onPrevious, weight = 0.85f)
                    SpotifyIconTile(
                        if (state.isPlaying) SidekitIcon.Pause else SidekitIcon.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        onPlayPause,
                        tone = SpotifyTileTone.Play,
                        weight = 1.2f
                    )
                    SpotifyIconTile(SidekitIcon.SkipNext, "Next", onNext, weight = 0.85f)
                    SpotifyIconTile(
                        SidekitIcon.Undo,
                        state.repeat.label,
                        onRepeat,
                        tone = if (state.repeat == RepeatMode.Off) SpotifyTileTone.Neutral else SpotifyTileTone.On,
                        weight = 0.85f
                    )
                }
            }
        }
    }
}

@Composable
private fun removeAction(label: String, onClick: () -> Unit) = SwipeAction(
    label = label,
    icon = SidekitIcon.DeleteForever,
    color = SidekitTheme.colors.dangerA1,
    armedColor = SidekitTheme.colors.dangerA2,
    destructive = true,
    onClick = onClick
)

@Composable
fun QueueScreen(
    state: SpotifyUiState,
    onBack: () -> Unit,
    onNowPlaying: () -> Unit,
    onJump: (String) -> Unit,
    onPlayNext: (SpotifyMediaItem) -> Unit,
    onRemove: (SpotifyMediaItem) -> Unit,
    onRemoveHistory: (SpotifyMediaItem) -> Unit,
    onClearUpcoming: () -> List<SpotifyMediaItem>,
    onRestoreUpcoming: (List<SpotifyMediaItem>) -> Unit,
    onShuffle: () -> Unit,
    onDismissNotice: () -> Unit
) {
    var focusedId by remember { mutableStateOf(state.current?.id) }
    var pendingUndo by remember { mutableStateOf<List<SpotifyMediaItem>?>(null) }
    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) {
            delay(5_000L)
            pendingUndo = null
        }
    }
    val back = spotifyBack("Back", onBack).first()
    val normal = withNotice(
        state.notice,
        onDismissNotice,
        listOf(back, SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    )
    val actions = if (pendingUndo != null) {
        undoBar(
            back,
            SideAction.undo("undoClear", "Undo", "Undo clear upcoming") {
                onRestoreUpcoming(pendingUndo.orEmpty())
                pendingUndo = null
            }
        )
    } else {
        normal
    }
    val focused = (listOfNotNull(state.current) + state.upcoming + state.history).firstOrNull { it.id == focusedId }
    val options = buildList {
        if (focused != null && focused.id != state.current?.id && focused.id !in state.history.map { it.id }) {
            add(SideAction("playNext", "Play next", SidekitIcon.ArrowUpward, onClick = { onPlayNext(focused) }))
            add(SideAction("remove", "Remove", SidekitIcon.DeleteForever, tone = ActionTone.Destructive, onClick = { onRemove(focused) }))
        }
        add(SideAction("clear", "Clear upcoming", SidekitIcon.DeleteForever, tone = ActionTone.Destructive, onClick = { pendingUndo = onClearUpcoming() }))
        add(SideAction("shuffle", if (state.shuffle) "Shuffle on" else "Shuffle off", SidekitIcon.Shuffle, onClick = onShuffle))
    }
    SidekitScaffold(title = "Queue", actions = actions, options = options) { padding ->
        val listState = rememberLazyListState()
        val swipes = rememberSwipeRevealGroup()
        val currentFocus = remember { FocusRequester() }
        LaunchedEffect(state.current?.id) { if (focusedId == null) runCatching { currentFocus.requestFocus() } }
        SidekitList(padding, state = listState) {
            val current = state.current
            if (current == null) {
                item(key = "empty") { EmptyMessage("Nothing queued", hint = "Search to find something to play") }
            } else {
                item(key = "current") {
                    SpotifyMediaRow(
                        current,
                        Modifier.listFade(listState, 0).focusRequester(currentFocus).onFocusChanged { if (it.isFocused) focusedId = current.id },
                        secondary = "Now playing",
                        onClick = { onJump(current.id) }
                    )
                }
                itemsIndexed(state.upcoming, key = { _, item -> "u${item.id}" }) { index, item ->
                    SidekitSwipeRow(
                        key = "u${item.id}",
                        group = swipes,
                        modifier = Modifier.listFade(listState, index + 1),
                        action = removeAction("Remove ${item.title} from queue") { onRemove(item) }
                    ) {
                        SpotifyMediaRow(
                            item,
                            Modifier.onFocusChanged { if (it.isFocused) focusedId = item.id },
                            secondary = item.creator,
                            onClick = { onJump(item.id) }
                        )
                    }
                }
                if (state.history.isNotEmpty()) {
                    val historyLabelIndex = state.upcoming.size + 1
                    item(key = "historyLabel") {
                        SectionLabel("History", Modifier.listFade(listState, historyLabelIndex))
                    }
                    itemsIndexed(state.history.take(20), key = { _, item -> "h${item.id}" }) { index, item ->
                        SidekitSwipeRow(
                            key = "h${item.id}",
                            group = swipes,
                            modifier = Modifier.listFade(listState, index + 2 + state.upcoming.size),
                            action = removeAction("Remove ${item.title} from history") { onRemoveHistory(item) }
                        ) {
                            SpotifyMediaRow(
                                item,
                                Modifier,
                                secondary = item.creator,
                                onClick = { onJump(item.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
