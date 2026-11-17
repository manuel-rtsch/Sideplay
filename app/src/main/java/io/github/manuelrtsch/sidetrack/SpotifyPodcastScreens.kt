package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.action.ActionTone
import hrln.interactive.sidekit.design.action.SideAction
import hrln.interactive.sidekit.design.components.SidekitCard
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.data.SpotifyEpisodeProgress
import io.github.manuelrtsch.sidetrack.ui.GutterRow
import io.github.manuelrtsch.sidetrack.ui.MeatballIcon
import io.github.manuelrtsch.sidetrack.ui.RowActionSquare
import io.github.manuelrtsch.sidetrack.ui.RowGutterState
import io.github.manuelrtsch.sidetrack.ui.rememberGutterSquareFocus
import io.github.manuelrtsch.sidetrack.ui.rememberRowGutterState

/**
 * A podcast show page. It shares the collection header grammar but gives episodes their own row:
 * real publishing number, publication date, running time and the first lines of show notes. The
 * list has no heading: the page title and the play tile already say what it is.
 */
@Composable
fun ShowScreen(
    collection: SpotifyCollection,
    saved: SavedUiState,
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    episodeProgress: Map<String, SpotifyEpisodeProgress>,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onItem: (Int) -> Unit,
    onSave: () -> Unit,
    onAddLatestToQueue: () -> Unit,
    onAddItemToQueue: (SpotifyMediaItem) -> Unit,
    onNowPlaying: () -> Unit,
    onArtwork: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    onShowNotes: () -> Unit,
    onEpisodeNotes: (SpotifyEpisode) -> Unit,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit
) {
    val colors = SidekitTheme.colors
    val savedCollection = "${SpotifyCollectionType.Show.route}:${collection.id}" in saved.savedCollectionKeys
    val back = remember { spotifyBack("Back", onBack).first() }
    val rowActions = rememberRowActionBarState()
    val actions = rowActionBarActions(
        rowActions,
        spotifyBar(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            back
        )
    )
    val showNotes = remember(collection.description) { plainPodcastText(collection.description) }
    val options = buildList {
        collection.items.firstOrNull()?.let {
            add(SideAction("addToQueue", "Add latest to queue", SidekitIcon.PlaylistAdd, onClick = onAddLatestToQueue))
        }
        if (showNotes.isNotBlank()) {
            add(SideAction("notes", "Show notes", SidekitIcon.Docs, onClick = onShowNotes))
        }
        add(SideAction("nowPlaying", "Now playing", SidekitIcon.MusicNote2, onClick = onNowPlaying))
    }
    SidekitScaffold(
        title = collection.title,
        modifier = Modifier.dismissRowActionsOnVerticalKey(rowActions),
        actions = actions,
        options = options
    ) { padding ->
        val listState = rememberLazyListState()
        rowActions.dismissOnScroll(listState)
        val playFocus = remember { FocusRequester() }
        val gutters = rememberRowGutterState()
        val playIds = remember(collection.items) { collection.items.mapTo(mutableSetOf()) { it.id } }
        LaunchedEffect(collection.id) { runCatching { playFocus.requestFocus() } }
        val latest = collection.items.firstOrNull() as? SpotifyEpisode
        val latestProgress = latest?.let { episodeProgress[it.id] }
        val playLabel = if (latestProgress != null && !latestProgress.completed && latestProgress.positionMs >= 30_000L) {
            "Continue latest episode"
        } else {
            "Play latest episode"
        }
        SidekitList(padding, state = listState) {
            var rowIndex = 0
            item(key = "tiles") {
                SidekitItemRow(Modifier.listFade(listState, rowIndex)) {
                    ContextPlayTile(
                        playIds,
                        playback.current?.id,
                        playback.isPlaying,
                        onPlay,
                        onPlayPause,
                        Modifier.weight(1f).focusRequester(playFocus),
                        playLabel = playLabel,
                        pauseLabel = "Pause episode"
                    )
                    ArtworkTile(
                        collection.artRes,
                        "${collection.title} artwork",
                        Modifier.weight(1f),
                        collection.artUrl,
                        onClick = onArtwork
                    )
                    SpotifyIconTile(
                        if (savedCollection) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                        if (savedCollection) "Saved" else "Save",
                        onSave,
                        tone = if (savedCollection) SpotifyTileTone.Library else SpotifyTileTone.Neutral
                    )
                }
            }
            rowIndex += 1
            item(key = "meta") {
                SidekitText(
                    collection.subtitle,
                    Modifier.padding(start = 18.dp, end = 18.dp).listFade(listState, rowIndex),
                    style = SidekitTheme.typography.bodySmall,
                    color = colors.surfaceA6,
                    maxLines = 1
                )
            }
            rowIndex += 1
            if (showNotes.isNotBlank()) {
                val notesIndex = rowIndex
                item(key = "showNotes") {
                    SidekitText(
                        showNotes,
                        Modifier
                            .padding(horizontal = 18.dp)
                            .padding(top = 4.dp)
                            .listFade(listState, notesIndex),
                        style = SidekitTheme.typography.caption,
                        color = colors.surfaceA6,
                        maxLines = 2
                    )
                }
                rowIndex += 1
                val moreIndex = rowIndex
                item(key = "showNotesMore") {
                    TerminalRow(
                        "Read full show notes",
                        Modifier.listFade(listState, moreIndex),
                        onClick = onShowNotes
                    )
                }
                rowIndex += 1
            }
            val base = rowIndex
            // Keyed by episode id, not position: the list is newest-first, so a newly published
            // episode prepends and every index shifts.
            itemsIndexed(
                collection.items,
                key = { _, item -> item.id },
                contentType = { _, item -> if (item is SpotifyTrack) "track" else "episode" }
            ) { index, item ->
                val key = item.id
                val modifier = Modifier.listFade(listState, base + index)
                if (item is SpotifyEpisode) {
                    EpisodeRow(
                        key,
                        item,
                        episodeProgress[item.id],
                        gutters,
                        rowActions,
                        modifier,
                        onAddToQueue = onAddItemToQueue,
                        onNotes = { onEpisodeNotes(item) },
                        onClick = { onItem(index) }
                    )
                }
            }
            rowIndex += collection.items.size
            if (collection.items.isEmpty()) {
                item(key = "emptyEpisodes") {
                    EmptyMessage(
                        "No episodes yet",
                        Modifier.listFade(listState, rowIndex),
                        hint = "This show has not published an episode"
                    )
                }
                rowIndex += 1
            }
            if (collection.hasMore) {
                val moreIndex = rowIndex
                item(key = "more") {
                    PagingFooter(loadingMore, onLoadMore, Modifier.listFade(listState, moreIndex))
                }
            }
        }
    }
}

/**
 * An episode row: artwork beside the title and real identity, then the notes and the resume line
 * across the full card width, so neither is squeezed into the column next to the artwork. Its
 * single gutter trigger opens the contextual bar with queue and notes.
 */
@Composable
private fun EpisodeRow(
    key: Any,
    episode: SpotifyEpisode,
    progress: SpotifyEpisodeProgress?,
    focus: RowGutterState,
    bar: RowActionBarState,
    modifier: Modifier = Modifier,
    onAddToQueue: (SpotifyMediaItem) -> Unit,
    onNotes: () -> Unit,
    onClick: () -> Unit
) {
    val colors = SidekitTheme.colors
    val squareFocus = rememberGutterSquareFocus(key, focus)
    val notes = remember(episode.description) { plainPodcastText(episode.description) }
    val resume = progress?.takeIf { !it.completed }
    val selected = bar.selection?.key == key
    val rowFocus = remember { FocusRequester() }
    CloseRowActionsWhenDisposed(selected, bar)
    GutterRow(key, focus, squares = 1, modifier = modifier, content = {
        // Free-height card rather than SidekitTile: a podcast row needs the title, its identity,
        // full-width notes and a progress line, which cannot fit the fixed single-item height.
        Box(
            Modifier
                .fillMaxWidth()
                .focusRequester(rowFocus)
                .onFocusChanged { state ->
                    if (state.isFocused) focus.rowFocused(key) else focus.rowUnfocused(key)
                }
                .then(
                    if (selected) {
                        Modifier.border(2.dp, SidekitTheme.colors.light, SidekitTheme.shapes.item)
                    } else {
                        Modifier
                    }
                )
                .clip(SidekitTheme.shapes.item)
                .background(if (episode.playable) colors.surfaceA3 else colors.surfaceA2)
                .clickable(
                    onClickLabel = "Play episode ${episode.title}",
                    role = Role.Button,
                    onClick = {
                        focus.touchDismissed(key)
                        onClick()
                    }
                )
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 15.dp,
                        top = 11.dp,
                        end = 15.dp,
                        bottom = if (resume != null) 15.dp else 11.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    SpotifyCover(
                        episode.artRes,
                        Modifier.size(80.dp),
                        shape = SidekitTheme.shapes.artwork,
                        artUrl = episode.artUrl
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SidekitText(episode.title, style = SidekitTheme.typography.bodySmall, maxLines = 2)
                        SidekitText(
                            episodeMetaLine(episode, progress, compactProgress = true),
                            style = SidekitTheme.typography.caption,
                            color = colors.surfaceA6,
                            maxLines = 1
                        )
                    }
                }
                if (notes.isNotBlank()) {
                    SidekitText(
                        notes,
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClickLabel = "Read full episode notes",
                                role = Role.Button,
                                onClick = onNotes
                            ),
                        style = SidekitTheme.typography.caption,
                        color = colors.surfaceA6,
                        maxLines = 2
                    )
                }
            }
            if (resume != null) {
                // Full-bleed resume line: no inset, no spacing, clipped by the card's own radius so
                // it disappears into the corners instead of reading as a scrubbable control.
                EpisodeProgressBar(
                    resume.fraction(),
                    Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }) {
        RowActionSquare(
            color = SidekitTheme.colors.surfaceA4,
            label = "Actions for ${episode.title}",
            content = { MeatballIcon() },
            modifier = squareFocus,
            onClick = {
                focus.touchDismissed(key)
                rowFocus.requestFocus()
                bar.show(
                    RowActionSelection(
                        key = key,
                        title = episode.title,
                        focusRequester = rowFocus,
                        actions = buildList {
                            add(
                                RowAction(
                                    key = "addToQueue",
                                    label = "Add to queue",
                                    contentDescription = "Add ${episode.title} to queue",
                                    icon = SidekitIcon.PlaylistAdd,
                                    tone = ActionTone.Primary,
                                    onClick = { onAddToQueue(episode) }
                                )
                            )
                            if (notes.isNotBlank()) {
                                add(
                                    RowAction(
                                        key = "notes",
                                        label = "Notes",
                                        contentDescription = "Read notes for ${episode.title}",
                                        icon = SidekitIcon.Docs,
                                        tone = ActionTone.Primary,
                                        onClick = onNotes
                                    )
                                )
                            }
                        }
                    )
                )
            }
        )
    }
}

/** A thin local resume line under the episode notes; the seek bar owns the playing screen. */
@Composable
private fun EpisodeProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
    ) {
        Box(Modifier.fillMaxSize().background(SidekitTheme.colors.surfaceA5))
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(SidekitTheme.colors.emphasisA1)
        )
    }
}

/** Full show or episode notes, kept off the row so a long description can be read at leisure. */
@Composable
fun NotesScreen(
    title: String,
    body: String,
    label: String,
    onBack: () -> Unit
) {
    val listState = rememberLazyListState()
    // Show notes are plain text with paragraph breaks; one lazy row per paragraph keeps a long
    // description from being measured in a single pass. A restored stack has no body, which is
    // what the hint covers instead of an empty screen.
    val paragraphs = remember(body) {
        body.split("\n\n").map(String::trim).filter(String::isNotEmpty)
    }
    SidekitScaffold(
        title = label,
        actions = spotifyBack("Back", onBack)
    ) { padding ->
        SidekitList(padding, state = listState) {
            item(key = "title") {
                SidekitCard(Modifier.fillMaxWidth().listFade(listState, 0)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SidekitText(
                            title.ifBlank { label },
                            style = SidekitTheme.typography.headline,
                            maxLines = 3
                        )
                    }
                }
            }
            if (paragraphs.isEmpty()) {
                item(key = "missing") {
                    SidekitText(
                        "These notes are no longer available. Open the show again to read them.",
                        Modifier.padding(horizontal = 18.dp).listFade(listState, 1),
                        style = SidekitTheme.typography.bodySmall,
                        color = SidekitTheme.colors.surfaceA6,
                        maxLines = 3
                    )
                }
            }
            itemsIndexed(
                paragraphs,
                key = { index, _ -> "p$index" },
                contentType = { _, _ -> "paragraph" }
            ) { index, paragraph ->
                SidekitText(
                    paragraph,
                    Modifier
                        .padding(horizontal = 18.dp)
                        .padding(top = if (index == 0) 4.dp else 14.dp)
                        .listFade(listState, index + 1),
                    style = SidekitTheme.typography.bodySmall,
                    color = SidekitTheme.colors.surfaceA6,
                    maxLines = Int.MAX_VALUE
                )
            }
            item(key = "end") {
                // The action bar floats over the list; this lets the last paragraph scroll clear
                // of it instead of ending underneath the Back button.
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}
