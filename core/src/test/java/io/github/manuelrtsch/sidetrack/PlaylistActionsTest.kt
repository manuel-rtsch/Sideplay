package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistActionsTest {
    private fun playlist(actions: PlaylistActions) = Playlist(
        id = "p1",
        name = "Mix",
        owner = "You",
        ownerId = null,
        artRes = 0,
        tracks = emptyList(),
        actions = actions
    )

    private fun collection(actions: PlaylistActions?) = Collection(
        type = CollectionType.Playlist,
        id = "p1",
        title = "Mix",
        subtitle = "You",
        artRes = 0,
        items = emptyList(),
        playlistActions = actions
    )

    @Test fun `editable is derived from the two item directions`() {
        assertFalse(PlaylistActions.None.canEditItems)
        assertTrue(PlaylistActions(canAddItems = true).canEditItems)
        assertTrue(PlaylistActions(canRemoveItems = true).canEditItems)
        assertTrue(PlaylistActions.All.canEditItems)
    }

    @Test fun `a service that cannot edit playlists overrides a permissive row`() {
        val caps = ServiceCapabilities.All.copy(supportsPlaylistEdit = false)
        val row = playlist(PlaylistActions.All)
        val page = collection(PlaylistActions.All)

        assertEquals(PlaylistActions.None, caps.playlistActions(row))
        assertEquals(PlaylistActions.None, caps.playlistActions(page))
    }

    @Test fun `a service that can edit playlists keeps the row's own limits`() {
        val caps = ServiceCapabilities.All.copy(supportsPlaylistEdit = true)
        val readOnly = playlist(PlaylistActions.None)

        assertEquals(PlaylistActions.None, caps.playlistActions(readOnly))
        assertFalse(caps.playlistActions(readOnly).canAddItems)
        assertFalse(caps.playlistActions(readOnly).canRemoveItems)
        assertEquals(PlaylistActions.All, caps.playlistActions(playlist(PlaylistActions.All)))
    }

    @Test fun `an absent collection permission is read-only, not permissive`() {
        val caps = ServiceCapabilities.All
        assertEquals(PlaylistActions.None, caps.playlistActions(collection(null)))
    }

    @Test fun `unconfirmed means the service did not state permissions`() {
        assertTrue(PlaylistActions.ServerDecides.canAddItems)
        assertTrue(PlaylistActions.ServerDecides.canRemoveItems)
        assertTrue(PlaylistActions.ServerDecides.unconfirmed)
        assertFalse(PlaylistActions.All.unconfirmed)
    }
}
