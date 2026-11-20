package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hrln.interactive.sidekit.design.components.SidekitItemRow
import hrln.interactive.sidekit.design.components.SidekitList
import hrln.interactive.sidekit.design.components.SidekitScaffold
import hrln.interactive.sidekit.design.components.SidekitText
import hrln.interactive.sidekit.design.components.listFade
import hrln.interactive.sidekit.design.icons.SidekitIcon
import hrln.interactive.sidekit.design.theme.SidekitTheme
import io.github.manuelrtsch.sidetrack.ui.InlineLinkArrowSize
import io.github.manuelrtsch.sidetrack.ui.LinkArrow

@Composable
fun CollectionScreen(
    collection: Collection,
    saved: SavedUiState,
    playback: PlaybackUiState,
    chrome: ChromeUiState,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayPause: () -> Unit,
    onItem: (Int) -> Unit,
    onSave: () -> Unit,
    onArtist: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddItemToQueue: (MediaItem) -> Unit,
    onSaveTrack: (Track) -> Unit,
    onEnsureSavedTrack: (Track) -> Unit,
    onNowPlaying: () -> Unit,
    onArtwork: () -> Unit,
    onLoadMore: () -> Unit,
    loadingMore: Boolean,
    onDismissNotice: () -> Unit,
    onDismissNowPlaying: () -> Unit,
    /** Generated pages such as Song radio re-use the collection body but cannot be saved. */
    showSave: Boolean = true,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onRemoveFromPlaylist: ((Track, Int) -> Unit)? = null
) {
    val colors = SidekitTheme.colors
    // A playlist page replaces Add to playlist with Remove from playlist: the page is already
    // the target, and the two-action row bar stays labelled instead of becoming icon-only.
    val isPlaylist = collection.type == CollectionType.Playlist
    val canRemoveItems = isPlaylist && collection.playlistActions?.canRemoveItems == true
    val rows = rememberTrackRows(
        saved,
        onSaveTrack,
        onEnsureSavedTrack,
        onAddItemToQueue,
        onAddToPlaylist = if (isPlaylist) null else onAddToPlaylist,
        onRemoveFromPlaylist = if (canRemoveItems) onRemoveFromPlaylist else null
    )
    val savedCollection = collection.key.encode() in saved.savedCollectionKeys
    val back = remember { sidetrackBack("Back", onBack).first() }
    val actions = rowActionBarActions(
        rows.bar,
        sidetrackBar(
            chrome,
            onDismissNotice,
            onNowPlaying,
            onDismissNowPlaying,
            back
        )
    )
    // The header keeps the three primary actions: play, artwork and save. The artist lives in the
    // metadata line below it, so collections keep the same play / image / save-or-follow layout
    // without a third Options entry.
    val options = sidetrackOptions(
        collection.items.firstOrNull()?.let { addToQueueOption(onClick = onAddToQueue) },
        onNowPlaying = onNowPlaying
    )
    val artist = if (collection.type == CollectionType.Album) {
        collection.artistId.mediaIdOrNull()?.let { collection.subtitle.substringBefore(" · ").takeIf(String::isNotBlank) }
    } else {
        null
    }
    val details = artist?.let { collection.subtitle.removePrefix(it).trimStart(' ', '·', ' ') }.orEmpty()
    SidekitScaffold(
        title = collection.title,
        modifier = Modifier.dismissRowActionsOnVerticalKey(rows.bar),
        actions = actions,
        options = options
    ) { padding ->
        val listState = rememberLazyListState()
        rows.bar.dismissOnScroll(listState)
        val playFocus = remember { FocusRequester() }
        val moreFocus = rememberLoadMoreFocus()
        val playIds = remember(collection.items) { collection.items.mapTo(mutableSetOf()) { it.id } }
        LaunchedEffect(collection.type, collection.id) {
            moreFocus.reset()
            runCatching { playFocus.requestFocus() }
        }
        LoadMoreFocus(
            moreFocus,
            listState,
            collection.items.size,
            loadingMore,
            firstItemListIndex = 2
        )
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
                        Modifier.weight(1f).focusRequester(playFocus)
                    )
                    ArtworkTile(
                        collection.artRes,
                        "${collection.title} artwork",
                        Modifier.weight(1f),
                        collection.artUrl,
                        onClick = onArtwork
                    )
                    if (showSave) {
                        IconTile(
                            if (savedCollection) SidekitIcon.Bookmark else SidekitIcon.BookmarkBorder,
                            if (savedCollection) "Saved" else "Save",
                            onSave,
                            tone = if (savedCollection) TileTone.Library else TileTone.Neutral
                        )
                    }
                }
            }
            rowIndex += 1
            val metaIndex = rowIndex
            item(key = "meta") {
                if (artist != null) {
                    CollectionArtistLine(
                        artist,
                        details,
                        Modifier.padding(start = 18.dp, end = 18.dp).listFade(listState, metaIndex),
                        onArtist
                    )
                } else {
                    SidekitText(
                        collection.subtitle,
                        Modifier.padding(start = 18.dp, end = 18.dp).listFade(listState, metaIndex),
                        style = SidekitTheme.typography.bodySmall,
                        color = colors.surfaceA6,
                        maxLines = 1
                    )
                }
            }
            rowIndex += 1
            val base = rowIndex
            // Albums and shows share one cover, so a position number is the useful left column.
            // Playlist tracks have their own artwork, which is more informative than a list index.
            val showTrackArt = collection.type == CollectionType.Playlist
            itemsIndexed(
                collection.items,
                key = { index, item -> "$index:${item.id}" },
                contentType = { _, item -> mediaContentType(item) }
            ) { index, item ->
                val key = "$index:${item.id}"
                val modifier = Modifier.listFade(listState, base + index)
                if (item is Track) {
                    TrackRow(
                        key,
                        item,
                        rows,
                        modifier,
                        index = if (showTrackArt) null else index,
                        entryIndex = index,
                        showArt = showTrackArt,
                        focusRequester = moreFocus.requesterFor(index)
                    ) { onItem(index) }
                } else {
                    GutterMediaRow(
                        key,
                        item,
                        rows.focus,
                        modifier,
                        index = if (showTrackArt) null else index,
                        showArt = showTrackArt,
                        secondary = if (item is Episode) formatPublishedDate(item.published) else item.creator,
                        focusRequester = moreFocus.requesterFor(index),
                        onAddToQueue = onAddItemToQueue,
                        onClick = { onItem(index) }
                    )
                }
            }
            rowIndex += collection.items.size
            if (collection.hasMore) {
                val moreIndex = rowIndex
                item(key = "more") {
                    LoadMoreRow(
                        if (collection.type == CollectionType.Show) "Load more episodes" else "Load more tracks",
                        loadingMore,
                        onLoadMore = {
                            moreFocus.onLoadMore(collection.items.size)
                            onLoadMore()
                        },
                        Modifier.listFade(listState, moreIndex),
                        focusState = moreFocus
                    )
                }
            }
        }
    }
}

/**
 * The album's artist and the rest of its metadata on one line. The whole line opens the artist,
 * so the link is a full-width D-pad target instead of a tiny text span.
 */
@Composable
private fun CollectionArtistLine(
    artist: String,
    details: String,
    modifier: Modifier,
    onArtist: () -> Unit
) {
    val colors = SidekitTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(SidekitTheme.shapes.control)
            .clickable(onClickLabel = "Open artist", role = Role.Button, onClick = onArtist)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SidekitText(
            artist,
            Modifier.weight(if (details.isBlank()) 1f else 0.42f),
            style = SidekitTheme.typography.bodySmall,
            maxLines = 1
        )
        if (details.isNotBlank()) {
            SidekitText(
                details,
                Modifier.weight(0.58f),
                style = SidekitTheme.typography.label,
                color = colors.surfaceA6,
                maxLines = 1,
                textAlign = TextAlign.End
            )
        }
        LinkArrow(Modifier.padding(start = 8.dp), size = InlineLinkArrowSize)
    }
}
