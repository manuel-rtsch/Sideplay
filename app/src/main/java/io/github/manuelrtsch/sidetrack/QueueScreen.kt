package io.github.manuelrtsch.sidetrack

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow
import io.github.manuelrtsch.sidetrack.R
import io.github.manuelrtsch.sidetrack.playback.formatDuration
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.QueueIndication
import io.github.manuelrtsch.sidetrack.ui.QueueReorderAutoScroll
import io.github.manuelrtsch.sidetrack.ui.SidekitSwipeRow
import io.github.manuelrtsch.sidetrack.ui.SwipeAction
import io.github.manuelrtsch.sidetrack.ui.longPressHaptic
import io.github.manuelrtsch.sidetrack.ui.queueReorder
import io.github.manuelrtsch.sidetrack.ui.rememberQueueReorderState
import io.github.manuelrtsch.sidetrack.ui.rememberSwipeRevealGroup
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun QueueScreen(
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onNowPlaying: () -> Unit,
    onSleepTimer: () -> Unit,
    onJump: (Int) -> Unit,
    onMoveUpcoming: (Int, Int) -> Unit,
    onCommitReorder: () -> Unit,
    onRemove: (Int) -> Int,
    onRestoreRemoved: (Int, MediaItem) -> Unit,
    onClearUpcoming: () -> List<MediaItem>,
    onRestoreUpcoming: (List<MediaItem>) -> Unit,
    onShuffle: () -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    onKeypadBackOverride: ((() -> Boolean)?) -> Unit = {}
) {
    var pendingUndo by remember { mutableStateOf<QueueUndo?>(null) }
    // Removing from the queue offers the same five visible seconds as every other Undo in the app.
    val undoWindow = rememberUndoWindow { pendingUndo = null }
    UndoWindowLifecycle(undoWindow) { pendingUndo = null }
    var reorderMode by remember { mutableStateOf(false) }
    // The current item and tail size at the start of a reorder. If either changes (playback moves
    // on, another surface edits the queue, radio loads), the lift no longer describes the row it
    // started on, so the mode abandons instead of moving a stranger.
    var reorderCurrentId by remember { mutableStateOf<String?>(null) }
    var reorderSize by remember { mutableStateOf(0) }
    // The list and the reorder state live above the scaffold: the key handler on the scaffold needs
    // them, and the rows below share the same list playback.
    val listState = rememberLazyListState()
    val rowActions = rememberRowActionBarState()
    rowActions.dismissOnScroll(listState)
    val reorder = rememberQueueReorderState(listState)
    // Touch swipes and the keypad gutter share the same right edge. Keep one group here so a
    // keypad move can dismiss an open swipe instead of leaving two action buttons on screen.
    val swipes = rememberSwipeRevealGroup()
    val hasUpcoming = playback.upcoming.isNotEmpty()
    val hasCurrent = playback.current != null
    val shownUpcoming = remember { mutableIntStateOf(CATALOG_PAGE_SIZE) }
    val visibleUpcoming = remember(playback.upcoming, shownUpcoming.intValue) {
        playback.upcoming.take(shownUpcoming.intValue)
    }
    val canReorder = visibleUpcoming.size > 1
    // Current row, then the "Up next" header when there is a tail. The header is the visible home
    // of shuffle state, so the mode no longer exists only in an icon's content description. Its own
    // top padding is the separation from the now-playing row: no extra spacer is needed.
    val currentRow = if (hasCurrent) 1 else 0
    val queueHeader = if (hasUpcoming) 1 else 0
    val firstQueueItem = currentRow + queueHeader
    // Keys are the track's identity, not its position: a row that moves keeps its node, so it
    // keeps focus and never blinks out and back in. Duplicates get an occurrence suffix because
    // the queue may legitimately hold the same track twice.
    val upcomingKeys = remember(visibleUpcoming) { entryKeys(visibleUpcoming) }
    // Focus state is keyed, not indexed: a shuffle or a move can change every index while the
    // row under the focus ring stays the same. This is what used to let a gutter land on the
    // wrong track after shuffle.
    var focusedKey by remember { mutableStateOf<String?>(null) }
    var gutterKey by remember { mutableStateOf<String?>(null) }
    var focusPending by remember { mutableStateOf(false) }
    // Remove, clear and reorder leave nothing focused. The list must not pick a neighbour on its
    // own: a keypad user should have to choose the next row explicitly.
    var clearFocusPending by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val currentFocus = remember { FocusRequester() }
    val firstUpcomingFocus = remember { FocusRequester() }
    val moreFocus = rememberLoadMoreFocus()
    val focusManager = LocalFocusManager.current
    // The focused row owns the gutter, but only once focus has settled: holding Up/Down through
    // the queue must not flash a button on every row it passes.
    LaunchedEffect(focusedKey, reorderMode, rowActions.selection) {
        if (reorderMode || focusedKey == null || rowActions.selection != null) {
            gutterKey = null
        } else {
            gutterKey = null
            delay(GUTTER_DELAY_MS)
            gutterKey = focusedKey
        }
    }
    LaunchedEffect(Unit) {
        if (playback.current != null) {
            runCatching { currentFocus.requestFocus() }
        } else if (upcomingKeys.isNotEmpty()) {
            runCatching { firstUpcomingFocus.requestFocus() }
        }
    }
    // Put focus back on the row (or the current item) after a remove, a drop or a cancel. A row
    // whose list key changed has to recompose once before it can take focus.
    LaunchedEffect(focusPending) {
        if (!focusPending) return@LaunchedEffect
        withFrameNanos { }
        focusPending = false
        val target = if (focusedKey != null) rowFocus else currentFocus
        runCatching { target.requestFocus() }
    }
    LaunchedEffect(clearFocusPending) {
        if (!clearFocusPending) return@LaunchedEffect
        withFrameNanos { }
        clearFocusPending = false
        runCatching { focusManager.clearFocus(force = true) }
    }
    // A keypad move away from the swiped row dismisses the touch action. If the move lands on the
    // swiped row itself the swipe stays, because it is still the row the user is on.
    LaunchedEffect(focusedKey) {
        val open = swipes.openKey
        if (open != null && open != focusedKey) swipes.closeAll()
    }
    // A row can disappear while its swipe is open (playback moves on, another surface clears the
    // queue). Drop the stale key so it cannot keep every gutter suppressed.
    LaunchedEffect(swipes.openKey, upcomingKeys) {
        val open = swipes.openKey
        if (open != null && upcomingKeys.none { it == open }) swipes.closeAll()
    }
    // A reorder must not survive the queue changing underneath it. Commit what was already picked
    // (if anything) and leave the mode; moving back to a stale index could target another track.
    LaunchedEffect(playback.current?.id, visibleUpcoming.size) {
        if (!reorderMode) return@LaunchedEffect
        if (playback.current?.id == reorderCurrentId && visibleUpcoming.size == reorderSize) return@LaunchedEffect
        val moved = reorder.hasMoved
        reorder.end()
        reorderMode = false
        if (moved) onCommitReorder()
        focusedKey = null
        clearFocusPending = true
    }
    val back = remember { sidetrackBack("Back", onBack).first() }
    // Removing a track offers Undo in the bar but drops the focus: the list must not choose a
    // neighbour for the user. The index identifies the occurrence, so duplicates are safe.
    val removeFromQueue: (Int, MediaItem) -> Unit = { index, item ->
        val removed = onRemove(index)
        if (removed >= 0) {
            pendingUndo = QueueUndo.Removed(removed, item)
            undoWindow.start()
            focusedKey = null
            clearFocusPending = true
        }
    }
    val clearUpcoming: () -> Unit = {
        rowActions.close()
        pendingUndo = QueueUndo.Cleared(onClearUpcoming())
        undoWindow.start()
        focusedKey = null
        clearFocusPending = true
    }
    val finishReorder: () -> Unit = {
        if (reorderMode) {
            val moved = reorder.hasMoved
            reorder.end()
            reorderMode = false
            // Only a real move makes the hand-picked order authoritative. Pressing Done without
            // moving must not silently turn shuffle off.
            if (moved) onCommitReorder()
            focusedKey = null
            clearFocusPending = true
        }
    }
    val cancelReorder: () -> Unit = {
        if (reorderMode) {
            val moved = reorder.hasMoved
            val origin = reorder.origin
            val current = reorder.from
            val sameQueue = playback.current?.id == reorderCurrentId && visibleUpcoming.size == reorderSize
            reorder.cancel()
            if (moved) {
                if (sameQueue && current != null && origin >= 0 && current != origin) {
                    onMoveUpcoming(current, origin)
                } else {
                    // The queue changed underneath the lift; the old origin no longer exists.
                    onCommitReorder()
                }
            }
            reorderMode = false
            focusedKey = null
            clearFocusPending = true
        }
    }
    val startReorderAt: (Int) -> Unit = { requested ->
        if (canReorder && !reorderMode) {
            swipes.closeAll()
            rowActions.close()
            val target = requested.coerceIn(0, visibleUpcoming.lastIndex)
            val targetKey = upcomingKeys[target]
            focusedKey = targetKey
            val offset = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.key == targetKey }
                ?.offset ?: 0
            reorderCurrentId = playback.current?.id
            reorderSize = visibleUpcoming.size
            reorder.begin(target, visibleUpcoming.size, offset.toFloat(), keypad = true)
            reorderMode = true
            focusPending = true
        }
    }
    val openRowActions: (Int, MediaItem) -> Unit = { index, item ->
        val key = upcomingKeys[index]
        focusedKey = key
        rowActions.show(
            RowActionSelection(
                key = key,
                title = item.title,
                focusRequester = rowFocus,
                actions = buildList {
                    add(
                        RowAction(
                            key = "remove",
                            label = "Remove",
                            contentDescription = "Remove ${item.title} from queue",
                            icon = SidekitIcon.DeleteForever,
                            tone = ActionTone.Destructive,
                            onClick = { removeFromQueue(index, item) }
                        )
                    )
                    if (canReorder) {
                        add(
                            RowAction(
                                key = "move",
                                label = "Move",
                                contentDescription = "Move ${item.title}",
                                icon = SidekitIcon.SwapVert,
                                tone = ActionTone.Primary,
                                onClick = { startReorderAt(index) }
                            )
                        )
                    }
                }
            )
        )
    }
    // Clear is the one list-wide action in the main bar. Shuffle is a toggle, so it lives in
    // Options beside the other list-wide destinations.
    val clearAction = if (hasUpcoming && !reorderMode) {
        SideAction(
            "clear",
            "Clear",
            SidekitIcon.Close,
            tone = ActionTone.Destructive,
            contentDescription = "Clear upcoming",
            onClick = clearUpcoming
        )
    } else {
        null
    }
    // The queue's own top row is the current item, so the now-playing confirmation would only
    // repeat it. Errors and confirmations still replace the bar.
    val normal = sidetrackActions(
        chrome,
        onDismissNotice,
        onNowPlaying,
        onDismissNowPlaying,
        listOfNotNull(back, clearAction),
        showNowPlayingNotice = false
    )
    val undo = when (val pending = pendingUndo) {
        is QueueUndo.Cleared -> SideAction.undo("undoClear", "Undo", "Undo clear upcoming") {
            undoWindow.cancel()
            onRestoreUpcoming(pending.items)
            pendingUndo = null
        }

        is QueueUndo.Removed -> SideAction.undo("undoRemove", "Undo", "Undo remove ${pending.item.title}") {
            undoWindow.cancel()
            onRestoreRemoved(pending.index, pending.item)
            pendingUndo = null
        }

        null -> null
    }
    val actions = when {
        reorderMode -> listOf(
            SideAction.cancel("cancelReorder", "Cancel", onClick = cancelReorder),
            SideAction(
                "doneReorder",
                "Done",
                contentDescription = "Finish reordering",
                onClick = finishReorder
            )
        )

        rowActions.selection != null -> rowActionBarActions(rowActions, normal)

        undo != null -> undoBar(back, undo)

        else -> normal
    }
    // Reordering is a list mode, not a row menu. Shuffle is an icon-only toggle; the sleep timer
    // is the list-wide destination that replaces the old Now Playing shortcut.
    val options = if (reorderMode) {
        emptyList()
    } else {
        buildList {
            if (playback.upcoming.size > 1) {
                add(
                    SideAction.square(
                        "shuffle",
                        SidekitIcon.Shuffle,
                        if (playback.shuffle) "Shuffle on" else "Shuffle off",
                        onShuffle,
                        tone = if (playback.shuffle) ActionTone.Secondary else ActionTone.Muted,
                        keepOptionsOpen = true
                    )
                )
            }
            add(SideAction("sleepTimer", "Sleep timer", SidekitIcon.Bedtime, onClick = onSleepTimer))
        }
    }
    SidekitScaffold(
        title = "Queue",
        // While reordering, Up/Down nudge the drop slot and Confirm drops. Left/Right are swallowed
        // so focus cannot wander off the lifted row and leave it stranded; Back cancels through the
        // BackHandler below.
        modifier = Modifier
            .dismissRowActionsOnVerticalKey(rowActions)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (!reorderMode || native.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val delta = if (native.keyCode == KeyEvent.KEYCODE_DPAD_UP) -1 else +1
                        // Rows keep their identity across a move, so the lifted row keeps focus and
                        // nothing has to be re-requested: that is what makes stepping smooth.
                        reorder.step(delta)?.let { (from, to) -> onMoveUpcoming(from, to) }
                        true
                    }

                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true

                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        finishReorder()
                        true
                    }

                    else -> false
                }
            },
        actions = actions,
        options = options
    ) { padding ->
        val view = LocalView.current
        QueueReorderAutoScroll(reorder)
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
            val listIndex = firstQueueItem + index
            // Park the focused row one slot below the band top, never at the very top. The row
            // above it must stay composed, or the next Up has no focusable neighbour and the
            // keypad loses focus. Down already parked here; Up used to park at the top, which is
            // why holding Up stalled after a row or two while Down kept working.
            val target = bandTop + pitchPx
            if (offset < target - 1f || offset > target + 1f) {
                listState.requestScrollToItem(listIndex, -pitchPx.roundToInt())
            }
        }
        val keepInView = if (reorderMode) reorder.to else -1
        var lastKeptInView by remember { mutableStateOf(-1) }
        SideEffect {
            if (keepInView < 0) {
                lastKeptInView = -1
            } else if (keepInView != lastKeptInView) {
                val lifted = firstQueueItem + keepInView
                // The gap and header are not rows: at the top of the queue, park on the
                // now-playing row instead of a label.
                val above = if (keepInView == 0) 0 else lifted - 1
                listState.requestScrollToItem(above)
                lastKeptInView = keepInView
            }
        }
        // A mode's left action is Cancel, not Back. Claim the keypad Back key while either mode
        // is open so it closes the mode instead of popping the queue.
        DisposableEffect(reorderMode, rowActions.selection, onKeypadBackOverride) {
            when {
                reorderMode -> onKeypadBackOverride {
                    cancelReorder()
                    true
                }

                rowActions.selection != null -> onKeypadBackOverride {
                    rowActions.close()
                    true
                }

                else -> onKeypadBackOverride(null)
            }
            onDispose { onKeypadBackOverride(null) }
        }
        // The keypad lift commits as it steps, so cancelling is a move back to where it started.
        BackHandler(enabled = reorderMode) { cancelReorder() }
        BackHandler(enabled = rowActions.selection != null) { rowActions.close() }
        if (!hasCurrent && !hasUpcoming) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                StatusScreen(
                    "Nothing queued",
                    hint = "Search to find something to play"
                )
            }
            return@SidekitScaffold
        }
        LoadMoreFocus(
            moreFocus,
            listState,
            visibleUpcoming.size,
            loading = false,
            firstItemListIndex = firstQueueItem
        )
        SidekitList(padding, state = listState) {
            val current = playback.current
            if (current != null) {
                item(key = "current") {
                    MediaRow(
                        current,
                        Modifier
                            .listFade(listState, 0)
                            .focusRequester(currentFocus)
                            .onFocusChanged { if (it.isFocused) focusedKey = null },
                        secondary = "Now playing",
                        onClick = onNowPlaying
                    )
                }
            }
            if (hasUpcoming) {
                item(key = "queueHeader") {
                    QueueSectionHeader(
                        count = playback.upcoming.size,
                        shuffled = playback.shuffle
                    )
                }
                itemsIndexed(
                    visibleUpcoming,
                    key = { index, _ -> upcomingKeys[index] },
                    contentType = { _, item -> if (item is Track) "track" else "episode" }
                ) { index, item ->
                    val key = upcomingKeys[index]
                    val selected = rowActions.selection?.key == key
                    CloseRowActionsWhenDisposed(selected, rowActions)
                    val pickedUp = reorder.from == index
                    // A swiped-open row already shows its Remove button; the gutter would be
                    // a second copy of the same action.
                    val focused = gutterKey == key && !pickedUp && !reorderMode && swipes.openKey == null
                    val square = SidekitTheme.spacing.itemHeight
                    val targetGutter = when {
                        pickedUp -> LiftedGutter
                        focused -> square
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
                        focused -> GutterMode.Actions
                        else -> null
                    }
                    var lastGutterMode by remember { mutableStateOf<GutterMode?>(null) }
                    if (gutterMode != null) lastGutterMode = gutterMode
                    val shownGutter = gutterMode ?: lastGutterMode
                    val gutterPx = with(density) { gutter.toPx() }
                    CompositionLocalProvider(LocalIndication provides QueueIndication) {
                        Box(
                            Modifier
                                // The picked-up row is the thing being looked at: never fade it.
                                .then(
                                    if (pickedUp) {
                                        Modifier
                                    } else {
                                        Modifier.listFade(listState, firstQueueItem + index)
                                    }
                                )
                                .zIndex(if (pickedUp) 1f else 0f)
                                .graphicsLayer {
                                    // The queue reorders as the finger crosses each row, so only
                                    // the picked-up row is offset: it is the one under the finger.
                                    translationY = if (pickedUp) reorder.translation else 0f
                                    if (pickedUp) {
                                        scaleX = 1.02f
                                        scaleY = 1.02f
                                    }
                                }
                                .queueReorder(
                                    state = reorder,
                                    index = index,
                                    count = visibleUpcoming.size,
                                    key = key,
                                    onPickUp = {
                                        swipes.closeAll()
                                        view.longPressHaptic()
                                    },
                                    onDrop = onMoveUpcoming,
                                    onDropEnd = { if (!reorderMode) onCommitReorder() }
                                )
                        ) {
                            SidekitSwipeRow(
                                key = key,
                                group = swipes,
                                // A focused or picked-up row slides left to leave the gutter free
                                // on its right; its width stays the same, as with a touch swipe.
                                modifier = Modifier.graphicsLayer { translationX = -gutterPx },
                                action = SwipeAction(
                                    label = "Actions for ${item.title}",
                                    icon = SidekitIcon.Apps,
                                    iconRes = R.drawable.ic_more_horiz,
                                    color = SidekitTheme.colors.surfaceA4,
                                    onClick = { openRowActions(index, item) }
                                ),
                                // The gutter is the keypad's Remove; a second focus-revealed
                                // delete square would crowd the same edge.
                                revealOnFocus = false
                            ) {
                                MediaRow(
                                    item,
                                    Modifier
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                focusedKey = key
                                                // Never fight a lift: the drag and the keypad both
                                                // scroll the row themselves.
                                                if (reorder.from == null) keepRowInBand(index, key)
                                            }
                                        }
                                        .then(
                                            if (selected) {
                                                Modifier.border(2.dp, SidekitTheme.colors.light, SidekitTheme.shapes.item)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .then(
                                            if (focusedKey == key) {
                                                Modifier.focusRequester(rowFocus)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .then(
                                            if (current == null && index == 0) {
                                                Modifier.focusRequester(firstUpcomingFocus)
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .then(moreFocus.rowModifier(index)),
                                    secondary = item.creator,
                                    // The side control gets the right edge while it is visible.
                                    trailing = if (gutter > 0.dp) null else formatDuration(item.durationMs),
                                    // Enter has one meaning on every row: play it now. Reorder
                                    // lives in Options, so the row body never turns into a menu.
                                    onClick = { onJump(index) }
                                )
                            }
                            // Composed only while the gutter has width, so a faded-out control
                            // can never be tapped.
                            if (gutter > 0.dp) {
                                Box(
                                    Modifier
                                        .matchParentSize()
                                        .graphicsLayer {
                                            alpha = gutterAlpha
                                            translationX = (1f - gutterAlpha) * GUTTER_SLIDE.toPx()
                                        },
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    when (shownGutter) {
                                        // A lifted row shows the bare arrows, as Arrange apps does:
                                        // the row is the thing being moved, the arrows show where.
                                        GutterMode.Lifted -> Column(
                                            Modifier.align(Alignment.CenterEnd).width(LiftedGutter),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            if (index > 0) ReorderArrow(up = true, size = LiftedIconSize)
                                            if (index < visibleUpcoming.lastIndex) ReorderArrow(up = false, size = LiftedIconSize)
                                        }

                                        GutterMode.Actions -> GutterSquare(
                                            color = SidekitTheme.colors.surfaceA4,
                                            label = "Actions for ${item.title}",
                                            content = { MeatballIcon() },
                                            onClick = { openRowActions(index, item) }
                                        )

                                        null -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
                if (playback.upcoming.size > visibleUpcoming.size) {
                    item(key = "queueMore") {
                        LoadMoreRow(
                            "Load more",
                            loading = false,
                            onLoadMore = {
                                moreFocus.onLoadMore(visibleUpcoming.size)
                                shownUpcoming.intValue += CATALOG_PAGE_SIZE
                            },
                            modifier = Modifier.listFade(listState, firstQueueItem + visibleUpcoming.size),
                            focusState = moreFocus
                        )
                    }
                }
            }
        }
    }
}

/** A non-focusable label that makes the tail and shuffle state visible at a glance. */
@Composable
private fun QueueSectionHeader(count: Int, shuffled: Boolean, modifier: Modifier = Modifier) {
    val label = buildString {
        append("Up next · ")
        append(count)
        append(if (count == 1) " track" else " tracks")
        if (shuffled && count > 1) append(" · Shuffled")
    }
    // Same heading as Home, Library, search and album/playlist sections: uppercase, small, muted.
    SectionLabel(label, modifier.fillMaxWidth())
}

/** Stable keys for the upcoming list: track identity plus an occurrence rank for duplicates. */
private fun entryKeys(items: List<MediaItem>): List<String> {
    val seen = HashMap<String, Int>(items.size)
    return items.map { item ->
        val rank = seen[item.id] ?: 0
        seen[item.id] = rank + 1
        "u${item.id}#$rank"
    }
}

/** What the bar's Undo will put back. */
private sealed interface QueueUndo {
    class Cleared(val items: List<MediaItem>) : QueueUndo

    class Removed(val index: Int, val item: MediaItem) : QueueUndo
}
