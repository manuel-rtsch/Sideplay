package io.github.manuelrtsch.sideplay

import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hrln.interactive.sidekit.design.action.ActionMotion
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitIconImage
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sideplay.playback.formatDuration
import io.github.manuelrtsch.sideplay.ui.QueueIndication
import io.github.manuelrtsch.sideplay.ui.QueueReorderAutoScroll
import io.github.manuelrtsch.sideplay.ui.SidekitSwipeRow
import io.github.manuelrtsch.sideplay.ui.SwipeAction
import io.github.manuelrtsch.sideplay.ui.queueReorder
import io.github.manuelrtsch.sideplay.ui.rememberQueueReorderState
import io.github.manuelrtsch.sideplay.ui.rememberSwipeRevealGroup
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onDismissNotice: () -> Unit
) {
    val current = state.current
    // Episodes have no saved-track endpoint, so Save is a track-only action.
    val save = (current as? SpotifyTrack)?.let {
        SideAction.square(
            "save",
            if (state.savedCurrent) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
            if (state.savedCurrent) "Saved" else "Save",
            onSave,
            tone = if (state.savedCurrent) ActionTone.Secondary else ActionTone.Muted,
            motion = ActionMotion.Up
        )
    }
    val actions = spotifyBar(
        state,
        onDismissNotice,
        onOpenNowPlaying = {},
        onDismissNowPlaying = {},
        continueWith = spotifyBack("Back", onBack).first(),
        extra = listOfNotNull(save),
        showNowPlayingNotice = false
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
                            Modifier.fillMaxWidth().clickable(enabled = current.artistId.spotifyIdOrNull() != null, onClickLabel = "Open artist", onClick = onArtist),
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
    onMoveUpcoming: (Int, Int) -> Unit,
    onRemove: (SpotifyMediaItem) -> Int,
    onRestoreRemoved: (Int, SpotifyMediaItem) -> Unit,
    onRemoveHistory: (SpotifyMediaItem) -> Unit,
    onClearUpcoming: () -> List<SpotifyMediaItem>,
    onRestoreUpcoming: (List<SpotifyMediaItem>) -> Unit,
    onShuffle: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    var focusedId by remember { mutableStateOf(state.current?.id) }
    // The now-playing row keeps focus when the track changes underneath it, so its onFocusChanged
    // does not fire again. Track that here so focusedId can follow the new current track.
    var currentRowFocused by remember { mutableStateOf(false) }
    var pendingUndo by remember { mutableStateOf<QueueUndo?>(null) }
    val scope = rememberCoroutineScope()
    // The list and the reorder state live above the scaffold: the key handler on the scaffold needs
    // them, and the rows below share the same list state.
    val listState = rememberLazyListState()
    val reorder = rememberQueueReorderState(listState)
    // The gap item only exists when there is a tail, so it shifts every later list index by one.
    val queueGap = if (state.upcoming.isNotEmpty()) 1 else 0
    // The upcoming row that owns the gutter control: the last row the keypad focused.
    var keypadIndex by remember { mutableStateOf<Int?>(null) }
    // The gutter follows the focused row, but only once the focus has settled: holding Up/Down
    // through the queue must not flash a button on every row it passes.
    var gutterIndex by remember { mutableStateOf<Int?>(null) }
    val ownRow: (Int?) -> Unit = {
        keypadIndex = it
        gutterIndex = it
    }
    // The focused row whose play square has been swapped for the edit squares.
    var editingId by remember { mutableStateOf<String?>(null) }
    // Stable per-entry identity, so a moved row keeps its node (and its focus) between frames.
    val upcomingKeys = remember(state.upcoming) { entryKeys(state.upcoming) }
    // Set whenever focus must be put back on [keypadIndex] after a lift, drop or cancel. It also
    // suppresses button ownership from following the transient focus that Compose hands to a
    // neighbouring row when the button it was on disappears.
    var focusPending by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    // Rows outside the queue (now playing, history) have no gutter: focus landing on one closes
    // whatever the queue had open. Transient focus Compose shuffles mid-lift is not a landing.
    val takeFocus: (String, Int) -> Unit = { id, index ->
        if (reorder.from == null && !focusPending) {
            focusedId = id
            editingId = null
            ownRow(if (index >= 0) index else null)
        }
    }
    val currentFocus = remember { FocusRequester() }
    LaunchedEffect(keypadIndex) {
        if (keypadIndex == null) {
            gutterIndex = null
        } else {
            // Close the previous row's control as soon as the focus border moves. Leaving it
            // open until the new row's delay elapses makes the old row blink after it has
            // already lost focus.
            gutterIndex = null
            delay(GUTTER_DELAY_MS)
            gutterIndex = keypadIndex
        }
    }
    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) {
            delay(5_000L)
            pendingUndo = null
        }
    }
    // The focused row is only interesting when it is an upcoming track: that is what turns the
    // bar's action into Play next. Look it up directly instead of building a combined list and a
    // history id list on every recomposition.
    val historyIds = remember(state.history) { state.history.mapTo(HashSet()) { it.id } }
    val focusedUpcoming = when {
        focusedId == null || focusedId == state.current?.id -> null
        else -> state.upcoming.firstOrNull { it.id == focusedId }?.takeIf { it.id !in historyIds }
    }
    val hasUpcoming = state.upcoming.isNotEmpty()
    val back = spotifyBack("Sideplay", onBack).first()
    // Removing a track offers Undo in the bar and leaves the keypad on the row that moved up.
    val removeFromQueue: (SpotifyMediaItem) -> Unit = { item ->
        val index = onRemove(item)
        if (index >= 0) {
            pendingUndo = QueueUndo.Removed(index, item)
            if (keypadIndex != null) {
                val remaining = state.upcoming.size - 1
                focusPending = remaining > 0
                ownRow(if (remaining > 0) index.coerceIn(0, remaining - 1) else null)
            }
        }
    }
    // The bar carries one contextual action: Play next for a focused queue item, otherwise Clear
    // while there is something to clear. Remove stays on the row's swipe.
    val contextAction = when {
        focusedUpcoming != null -> SideAction(
            "playNext",
            "Play next",
            SidekitIcon.ArrowUpward,
            onClick = {
                onPlayNext(focusedUpcoming)
                // The track is now top of the queue and the row it came from is empty: leave the
                // keypad on the now-playing row at the top rather than on a hole in the list.
                editingId = null
                ownRow(null)
                focusedId = state.current?.id
                scope.launch {
                    listState.animateScrollToItem(0)
                    runCatching { currentFocus.requestFocus() }
                }
            }
        )

        hasUpcoming -> SideAction(
            "clear",
            "Clear",
            SidekitIcon.Close,
            tone = ActionTone.Destructive,
            contentDescription = "Clear upcoming",
            onClick = { pendingUndo = QueueUndo.Cleared(onClearUpcoming()) }
        )

        else -> null
    }
    val normal = spotifyActions(
        state,
        onDismissNotice,
        onNowPlaying,
        onDismissNowPlaying,
        listOfNotNull(back, contextAction)
    )
    val undo = when (val pending = pendingUndo) {
        is QueueUndo.Cleared -> SideAction.undo("undoClear", "Undo", "Undo clear upcoming") {
            onRestoreUpcoming(pending.items)
            pendingUndo = null
        }

        is QueueUndo.Removed -> SideAction.undo("undoRemove", "Undo", "Undo remove ${pending.item.title}") {
            onRestoreRemoved(pending.index, pending.item)
            pendingUndo = null
        }

        null -> null
    }
    val actions = if (undo != null) undoBar(back, undo) else normal
    // Options is capped at two entries plus the close button: one action, then one icon toggle.
    // Shuffle reorders the upcoming tail, so it appears only when Clear does.
    val options = buildList {
        add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
        if (hasUpcoming) {
            add(
                SideAction.square(
                    "shuffle",
                    SidekitIcon.Shuffle,
                    if (state.shuffle) "Shuffle on" else "Shuffle off",
                    onShuffle,
                    tone = if (state.shuffle) ActionTone.Secondary else ActionTone.Muted,
                    keepOptionsOpen = true
                )
            )
        }
    }
    SidekitScaffold(
        title = "Queue",
        // While a keypad lift is active, Up/Down nudge the drop slot, Confirm drops and Back
        // cancels. Left/Right are swallowed so focus cannot wander off the lifted row and leave it
        // stranded; everything else (including Back) falls through to the normal handlers.
        modifier = Modifier.onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            if (!reorder.keypad || native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
            when (native.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val delta = if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else +1
                    // Rows keep their identity across a move, so the lifted row keeps focus and
                    // nothing has to be re-requested: that is what makes stepping smooth.
                    reorder.step(delta)?.let { (from, to) ->
                        onMoveUpcoming(from, to)
                        ownRow(to)
                    }
                    true
                }

                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true

                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // A dropped row comes back with its play square, not the edit pair.
                    editingId = null
                    ownRow(reorder.from ?: keypadIndex)
                    reorder.end()
                    focusPending = true
                    true
                }

                else -> false
            }
        },
        actions = actions,
        options = options
    ) { padding ->
        val swipes = rememberSwipeRevealGroup()
        val view = LocalView.current
        QueueReorderAutoScroll(reorder)
        LaunchedEffect(state.current?.id) {
            if (focusedId == null) runCatching { currentFocus.requestFocus() }
            // The now-playing row is still focused, but its item changed: keep the bar's context
            // action pointed at the new current track.
            if (currentRowFocused) focusedId = state.current?.id
        }
        // Keep the moved row in view. As in Sidekit's Arrange apps, the scroll is requested in the
        // same measure pass as the reorder, so the row never draws at its old position for a frame
        // and can never end up outside the viewport. The row is parked one place down rather than
        // at the very top, so the track it is about to pass stays visible above it; at the top of
        // the queue that neighbour is the now-playing row.
        // Sidekit's list scrolls a focused row into view with an animation. A held key repeats
        // faster than that animation can finish, so the focus outruns the scroll, the focused row
        // leaves the viewport, the list disposes it and the focus is lost. Parking the row in the
        // same measure pass keeps the scroll level with the key, exactly as the keypad lift does.
        val density = LocalDensity.current
        val pitchPx = with(density) { (SidekitTheme.spacing.itemHeight + SidekitTheme.spacing.itemOuterGap).toPx() }
        val bandTop = with(density) { padding.calculateTopPadding().toPx() }
        fun keepRowInBand(index: Int, key: Any) {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
            val offset = item.offset - info.viewportStartOffset
            val listIndex = index + 1 + queueGap
            // Park the focused row one slot below the band top, never at the very top. The row
            // above it must stay composed, or the next Up has no focusable neighbour and the
            // keypad loses focus. Down already parked here; Up used to park at the top, which is
            // why holding Up stalled after a row or two while Down kept working.
            val target = bandTop + pitchPx
            if (offset < target - 1f || offset > target + 1f) {
                listState.requestScrollToItem(listIndex, -pitchPx.roundToInt())
            }
        }
        val keepInView = if (reorder.keypad) reorder.to else -1
        var lastKeptInView by remember { mutableStateOf(-1) }
        SideEffect {
            if (keepInView < 0) {
                lastKeptInView = -1
            } else if (keepInView != lastKeptInView) {
                val lifted = keepInView + 1 + queueGap
                val above = (lifted - 1).coerceAtLeast(0)
                // The gap spacer is not a row: step over it to the now-playing row.
                listState.requestScrollToItem(if (queueGap > 0 && above == queueGap) 0 else above)
                lastKeptInView = keepInView
            }
        }
        // Put focus back on the lifted row after a lift, a drop or a cancel, so the keypad keeps
        // working: the row's list key may have changed underneath it.
        LaunchedEffect(focusPending) {
            if (!focusPending) return@LaunchedEffect
            // One frame for the row to recompose, since a drop changes the row's list key.
            withFrameNanos { }
            focusPending = false
            runCatching { rowFocus.requestFocus() }
        }
        // The keypad lift commits as it steps, so cancelling is a move back to where it started.
        BackHandler(enabled = reorder.keypad) {
            val origin = reorder.origin
            val current = reorder.from
            reorder.cancel()
            if (current != null && origin >= 0 && current != origin) onMoveUpcoming(current, origin)
            if (origin >= 0) ownRow(origin)
            focusPending = true
        }
        SidekitList(padding, state = listState) {
            val current = state.current
            val gap = queueGap
            if (current == null) {
                item(key = "empty") { EmptyMessage("Nothing queued", hint = "Search to find something to play") }
            } else {
                item(key = "current") {
                    SpotifyMediaRow(
                        current,
                        Modifier
                            .listFade(listState, 0)
                            .focusRequester(currentFocus)
                            .onFocusChanged {
                                currentRowFocused = it.isFocused
                                if (it.isFocused) takeFocus(current.id, -1)
                            },
                        secondary = "Now playing",
                        onClick = { onJump(current.id) }
                    )
                }
                if (gap == 1) item(key = "queueGap") { Spacer(Modifier.height(QueueGap)) }
                // Keys are the track's identity, not its position: a row that moves keeps its node,
                // so it keeps focus and never blinks out and back in. Duplicates get an occurrence
                // suffix because the queue may legitimately hold the same track twice.
                itemsIndexed(state.upcoming, key = { index, _ -> upcomingKeys[index] }) { index, item ->
                    val key = upcomingKeys[index]
                    val pickedUp = reorder.from == index
                    val keypadFocused = gutterIndex == index && !pickedUp
                    // Pressing a focused row swaps its play square for the edit squares.
                    val editing = keypadFocused && editingId == item.id
                    val square = SidekitTheme.spacing.itemHeight
                    val targetGutter = when {
                        editing -> square * 2 + GutterGap
                        pickedUp -> LiftedGutter
                        keypadFocused -> square
                        else -> 0.dp
                    }
                    // The gutter opens and closes rather than snapping: the row narrows as the
                    // control slides in, and widens again as it leaves.
                    val gutter by animateDpAsState(targetGutter, tween(GUTTER_ANIM_MS), label = "gutter")
                    val gutterAlpha by animateFloatAsState(
                        if (targetGutter > 0.dp) 1f else 0f,
                        tween(GUTTER_ANIM_MS),
                        label = "gutterAlpha"
                    )
                    // The control stays composed while it fades out, so it has something to animate.
                    val gutterMode = when {
                        pickedUp -> GutterMode.Lifted
                        editing -> GutterMode.Edit
                        keypadFocused -> GutterMode.Play
                        else -> null
                    }
                    var lastGutterMode by remember { mutableStateOf<GutterMode?>(null) }
                    if (gutterMode != null) lastGutterMode = gutterMode
                    val shownGutter = gutterMode ?: lastGutterMode
                    val narrowed = targetGutter > 0.dp
                    CompositionLocalProvider(LocalIndication provides QueueIndication) {
                        Box(
                            Modifier
                                // The picked-up row is the thing being looked at: never fade it.
                                .then(if (pickedUp) Modifier else Modifier.listFade(listState, index + 1 + gap))
                                .zIndex(if (pickedUp) 1f else 0f)
                                .graphicsLayer {
                                    // The queue reorders as the finger crosses each row, so only the
                                    // picked-up row is offset: it is the one under the finger.
                                    translationY = if (pickedUp) reorder.translation else 0f
                                    if (pickedUp) {
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .queueReorder(
                                    state = reorder,
                                    index = index,
                                    count = state.upcoming.size,
                                    key = key,
                                    onPickUp = {
                                        swipes.closeAll()
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    },
                                    onDrop = onMoveUpcoming
                                )
                        ) {
                            SidekitSwipeRow(
                                key = key,
                                group = swipes,
                                // A focused or picked-up row narrows to leave the gutter free on its right.
                                modifier = if (gutter > 0.dp) Modifier.padding(end = gutter) else Modifier,
                                action = removeAction("Remove ${item.title} from queue") { removeFromQueue(item) }
                            ) {
                                SpotifyMediaRow(
                                    item,
                                    Modifier
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                focusedId = item.id
                                                // Never fight a lift: the drag and the keypad both
                                                // scroll the row themselves.
                                                if (reorder.from == null) keepRowInBand(index, key)
                                                // Only a deliberate focus move hands the button over;
                                                // focus Compose shuffles mid-lift or mid-drop does not.
                                                if (reorder.from == null && !focusPending) {
                                                    if (keypadIndex != index) editingId = null
                                                    keypadIndex = index
                                                }
                                            }
                                        }
                                        .then(if (keypadIndex == index) Modifier.focusRequester(rowFocus) else Modifier),
                                    secondary = item.creator,
                                    // A focused or picked-up row gives its play time up to the gutter.
                                    trailing = if (narrowed) null else formatDuration(item.durationMs),
                                    // A focused row's body opens the edit squares instead of playing,
                                    // and pressing it again closes them: the play square is how it
                                    // plays, so nothing ever starts a track by accident.
                                    onClick = {
                                        when {
                                            editing -> editingId = null
                                            keypadFocused -> editingId = item.id
                                            else -> onJump(item.id)
                                        }
                                    }
                                )
                            }
                            val lift = {
                                val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
                                reorder.begin(index, state.upcoming.size, offset.toFloat(), keypad = true)
                                // The menu has done its job: the row comes back as a play square.
                                editingId = null
                                ownRow(index)
                                focusPending = true
                            }
                            // Composed only while the gutter has width, so a faded-out control can
                            // never be tapped.
                            if (gutter > 0.dp) {
                                Box(
                                    Modifier
                                        .align(Alignment.CenterEnd)
                                        .graphicsLayer {
                                            alpha = gutterAlpha
                                            translationX = (1f - gutterAlpha) * GUTTER_SLIDE.toPx()
                                        }
                                ) {
                                    when (shownGutter) {
                                        // A lifted row shows the bare arrows, as Arrange apps does: the block
                                        // is the way in, the arrows are what the lift is doing.
                                        GutterMode.Lifted -> Column(
                                            Modifier.align(Alignment.CenterEnd).width(LiftedGutter),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            if (index > 0) ReorderArrow(up = true, size = LiftedIconSize)
                                            if (index < state.upcoming.lastIndex) ReorderArrow(up = false, size = LiftedIconSize)
                                        }

                                        GutterMode.Edit -> Row(horizontalArrangement = Arrangement.spacedBy(GutterGap)) {
                                            RearrangeSquare(
                                                up = index > 0,
                                                down = index < state.upcoming.lastIndex,
                                                onClick = lift
                                            )
                                            GutterSquare(
                                                color = SidekitTheme.colors.dangerA1,
                                                icon = SidekitIcon.DeleteForever,
                                                label = "Remove ${item.title} from queue",
                                                onClick = { removeFromQueue(item) }
                                            )
                                        }

                                        GutterMode.Play -> GutterSquare(
                                            color = SidekitTheme.colors.successA1,
                                            icon = SidekitIcon.PlayArrow,
                                            label = "Play ${item.title} now",
                                            onClick = { onJump(item.id) }
                                        )

                                        null -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.history.isNotEmpty()) {
                    val historyLabelIndex = state.upcoming.size + 1 + gap
                    item(key = "historyLabel") {
                        SectionLabel("History", Modifier.listFade(listState, historyLabelIndex))
                    }
                    itemsIndexed(state.history.take(20), key = { index, item -> "$index:h${item.id}" }) { index, item ->
                        SidekitSwipeRow(
                            key = "$index:h${item.id}",
                            group = swipes,
                            modifier = Modifier.listFade(listState, index + 2 + state.upcoming.size + gap),
                            action = removeAction("Remove ${item.title} from history") { onRemoveHistory(item) }
                        ) {
                            SpotifyMediaRow(
                                item,
                                Modifier.onFocusChanged { if (it.isFocused) takeFocus(item.id, -1) },
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

/** Stable keys for the upcoming list: track identity plus an occurrence rank for duplicates. */
private fun entryKeys(items: List<SpotifyMediaItem>): List<String> {
    val seen = HashMap<String, Int>(items.size)
    return items.map { item ->
        val rank = seen[item.id] ?: 0
        seen[item.id] = rank + 1
        "u${item.id}#$rank"
    }
}

/** What the bar's Undo will put back. */
private sealed interface QueueUndo {
    class Cleared(val items: List<SpotifyMediaItem>) : QueueUndo

    class Removed(val index: Int, val item: SpotifyMediaItem) : QueueUndo
}

/** Icon size of the arrows stacked inside the rearrange square. */
private val RearrangeIconSize = 22.dp

/** How long a row must hold the focus before its gutter control appears. */
private const val GUTTER_DELAY_MS = 250L

/** A lifted row only lends the arrows a sliver of width, as Arrange apps does. */
private val LiftedGutter = 30.dp

/** How far the gutter control slides in from the right as it appears. */
private val GUTTER_SLIDE = 12.dp

/** How long the gutter takes to open or close. */
private const val GUTTER_ANIM_MS = 160

private enum class GutterMode { Lifted, Edit, Play }

/** Icon size of the bare arrows beside a lifted row. */
private val LiftedIconSize = 24.dp

/** Gap between two gutter squares. */
private val GutterGap = 3.dp

/** Breathing room between the now-playing row and the top of the queue. */
private val QueueGap = 6.dp

/**
 * A full-height square row control, the same footprint as the swipe reveal's Remove from queue
 * block: one item-height square, the item's corner radius, a large icon. Only the colour and the
 * glyph change between play, rearrange and remove.
 */
@Composable
private fun GutterSquare(
    color: androidx.compose.ui.graphics.Color,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: SidekitIcon? = null,
    content: (@Composable () -> Unit)? = null
) {
    Box(
        modifier
            .size(SidekitTheme.spacing.itemHeight)
            .clip(SidekitTheme.shapes.item)
            .background(color)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (content != null) {
            content()
        } else if (icon != null) {
            SidekitIconImage(icon, tint = SidekitTheme.colors.light, contentDescription = null)
        }
    }
}

/**
 * The rearrange square: up and down arrows stacked, showing only the directions the track can still
 * travel. Pressing it lifts the row for keypad reordering.
 */
@Composable
private fun RearrangeSquare(up: Boolean, down: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    GutterSquare(
        color = SidekitTheme.colors.surfaceA4,
        label = "Reorder",
        modifier = modifier,
        onClick = onClick ?: {}
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (up) ReorderArrow(up = true, size = RearrangeIconSize, tint = SidekitTheme.colors.light)
            if (down) ReorderArrow(up = false, size = RearrangeIconSize, tint = SidekitTheme.colors.light)
        }
    }
}

/**
 * A directional symbol beside a picked-up row. It is inert — one shows which way the row can
 * travel, it is not focusable or clickable. Keypad moving happens through the lift's key handler.
 */
@Composable
private fun ReorderArrow(
    up: Boolean,
    size: Dp = SidekitTheme.spacing.iconSize,
    tint: Color = SidekitTheme.colors.surfaceA5
) {
    SidekitIconImage(
        SidekitIcon.ArrowUpward,
        modifier = if (up) Modifier else Modifier.rotate(180f),
        size = size,
        tint = tint,
        contentDescription = null
    )
}
