package io.github.manuelrtsch.sidetrack

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.key
import io.github.manuelrtsch.sidetrack.ui.RowGutterState

@Composable
internal fun LibraryRow(
    item: Any,
    modifier: Modifier,
    bodyModifier: Modifier,
    rows: TrackRows,
    libraryGutters: RowGutterState,
    focusRequester: FocusRequester? = null,
    canRadio: Boolean,
    onArtistRadio: (Artist) -> Unit,
    onQueueAlbum: (Album) -> Unit,
    onQueuePlaylist: (Playlist) -> Unit,
    onAddLatestShowToQueue: (Show) -> Unit,
    onTrack: (Track) -> Unit,
    onPlaylist: (Playlist) -> Unit,
    onAlbum: (Album) -> Unit,
    onArtist: (Artist) -> Unit,
    onShow: (Show) -> Unit
) {
    // Every library row now wraps its focusable tile in a gutter, so each takes the load-more
    // focus requester directly instead of receiving it through an already-modified tile.
    when (item) {
        // Library rows carry no list index: a track can appear once per category, so its id is
        // already unique here.
        is Track -> TrackRow(
            item.id,
            item,
            rows,
            modifier,
            bodyModifier,
            focusRequester = focusRequester
        ) { onTrack(item) }

        is Playlist -> PlaylistGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            onAddToQueue = { onQueuePlaylist(item) },
            onClick = { onPlaylist(item) }
        )

        is Album -> AlbumGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            onAddToQueue = { onQueueAlbum(item) },
            onClick = { onAlbum(item) }
        )

        is Artist -> ArtistGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            canRadio = canRadio,
            onRadio = { onArtistRadio(item) },
            onClick = { onArtist(item) }
        )

        is Show -> ShowGutterRow(
            item.key.encode(),
            item,
            libraryGutters,
            rows.bar,
            modifier,
            bodyModifier,
            focusRequester = focusRequester,
            onAddLatestToQueue = { onAddLatestShowToQueue(item) },
            onClick = { onShow(item) }
        )

        else -> Unit
    }
}
