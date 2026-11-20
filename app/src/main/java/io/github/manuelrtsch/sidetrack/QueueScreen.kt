package io.github.manuelrtsch.sidetrack

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.sdk.ui.UndoWindowLifecycle
import hrln.interactive.sidekit.sdk.ui.rememberUndoWindow
import io.github.manuelrtsch.sidetrack.ui.QueueReorderAutoScroll
import io.github.manuelrtsch.sidetrack.ui.rememberQueueReorderState
import io.github.manuelrtsch.sidetrack.ui.rememberSwipeRevealGroup
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
                reorderMode && queueReorderKey(event, reorder, onMoveUpcoming, finishReorder)
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
        val band = rememberQueueRowBand(padding, listState, firstQueueItem)
        val keepRowInBand: (Int, Any) -> Unit = { index, key -> band.keep(index, key) }
        val keepInView = if (reorderMode) reorder.to else -1
        SideEffect { band.trackLift(keepInView) }
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
        val current = playback.current
        val queueRowHost = QueueRowHost(
            listState = listState,
            reorder = reorder,
            swipes = swipes,
            rowActions = rowActions,
            rowFocus = rowFocus,
            firstUpcomingFocus = firstUpcomingFocus,
            moreFocus = moreFocus,
            firstQueueItem = firstQueueItem,
            reorderMode = reorderMode,
            focusedKey = focusedKey,
            gutterKey = gutterKey,
            current = current,
            lastIndex = visibleUpcoming.lastIndex,
            onFocusedKey = { focusedKey = it },
            keepRowInBand = keepRowInBand,
            openRowActions = openRowActions,
            onJump = onJump,
            onMoveUpcoming = onMoveUpcoming,
            onCommitReorder = onCommitReorder,
            closeSwipes = swipes::closeAll
        )
        SidekitList(padding, state = listState) {
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
                    QueueUpcomingRow(
                        index = index,
                        item = item,
                        key = upcomingKeys[index],
                        host = queueRowHost
                    )
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
