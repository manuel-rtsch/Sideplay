package io.github.manuelrtsch.sidetrack

import androidx.test.ext.junit.runners.AndroidJUnit4
import hrln.interactive.sidekit.design.action.NoticeKind
import io.github.manuelrtsch.sidetrack.data.MediaCatalog
import io.github.manuelrtsch.sidetrack.data.MediaLibraryWriter
import io.github.manuelrtsch.sidetrack.playback.PlaybackSource
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistControllerTest {

    private val first = track("first")
    private val second = track("second")
    private val third = track("third")

    @Test
    fun `picker keeps only playlists that accept items`() = runTest {
        val editable = playlist("editable", PlaylistActions.All)
        val readOnly = playlist("readonly", PlaylistActions.None)
        val catalog = FakeCatalog(playlistPages = mapOf(0 to listOf(readOnly, editable)))
        val store = store()
        val controller = controller(catalog, store, this)

        controller.ensureChoices()
        advanceUntilIdle()

        assertEquals(listOf(editable), store.value.playlistChoices)
        assertTrue(store.value.playlistChoicesLoaded)
    }

    @Test
    fun `picker pages by catalog rows even when a page has no editable playlist`() = runTest {
        val readOnly = playlist("readonly", PlaylistActions.None)
        val editable = playlist("editable", PlaylistActions.All)
        val secondEditable = playlist("later", PlaylistActions.All)
        val catalog = FakeCatalog(
            playlistPages = mapOf(
                0 to listOf(readOnly, editable),
                2 to listOf(secondEditable)
            )
        )
        val store = store()
        val controller = controller(catalog, store, this)

        controller.ensureChoices()
        advanceUntilIdle()
        assertEquals(listOf(editable), store.value.playlistChoices)
        assertEquals(listOf(0), catalog.requestedOffsets)

        controller.loadMoreChoices()
        advanceUntilIdle()

        assertEquals(listOf(0, 2), catalog.requestedOffsets)
        assertEquals(listOf(editable, secondEditable), store.value.playlistChoices)
    }

    @Test
    fun `add reports the index and invalidates the target collection`() = runTest {
        val target = playlist("target", PlaylistActions.All)
        val cacheKey = target.key.encode()
        val stale = collection("target", listOf(first), PlaylistActions.All)
        val row = target.copy(trackCount = 1)
        val writer = FakeWriter(addIndex = 7)
        val store = store(
            collections = mapOf(cacheKey to stale),
            libraryPages = mapOf(LibraryFilter.Playlists to LibraryPage(listOf(row)))
        )
        val controller = controller(FakeCatalog(), store, this, writer = writer)

        val index = controller.add(target, second)

        assertEquals(7, index)
        assertEquals(target.key, writer.addedPlaylist)
        assertEquals(second, writer.addedItem)
        assertTrue(cacheKey !in store.value.collections)
        assertEquals(2, store.value.playlistRow("target").trackCount)
        assertEquals("Added ${second.title} to ${target.name}", store.value.notice)
        assertEquals(NoticeKind.Success, store.value.noticeKind)
    }

    @Test
    fun `remove drops exactly the acted-on occurrence from the cached page`() = runTest {
        val target = playlist("target", PlaylistActions.All)
        val cacheKey = target.key.encode()
        val cached = collection("target", listOf(first, second, third), PlaylistActions.All)
        val row = target.copy(trackCount = 3)
        val writer = FakeWriter()
        val store = store(
            collections = mapOf(cacheKey to cached),
            libraryPages = mapOf(LibraryFilter.Playlists to LibraryPage(listOf(row)))
        )
        val controller = controller(FakeCatalog(), store, this, writer = writer)

        controller.remove(cached, second, 1)
        advanceUntilIdle()

        val updated = store.value.collections.getValue(cacheKey)
        assertEquals(listOf(first, third), updated.items)
        assertEquals(2, updated.totalItems)
        assertEquals(2, store.value.playlistRow("target").trackCount)
        assertEquals(target.key, writer.removedPlaylist)
        assertEquals(second, writer.removedItem)
        assertEquals(1, writer.removedIndex)
    }

    @Test
    fun `create forwards the trimmed name and the seed in order`() = runTest {
        val writer = FakeWriter()
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        controller.create("  Trip  ", listOf(first.key, second.key))

        assertEquals("Trip", writer.createdName)
        assertEquals(listOf(first.key, second.key), writer.createdSeed)
    }

    @Test
    fun `read-only playlists are never written`() = runTest {
        val readOnly = playlist("readonly", PlaylistActions.None)
        val writer = FakeWriter()
        val controller = controller(FakeCatalog(), store(), this, writer = writer)

        assertEquals(null, controller.add(readOnly, first))
        assertEquals(0, writer.addCalls)
    }

    @Test
    fun `undo add decrements the patched count, not the stale argument`() = runTest {
        val target = playlist("target", PlaylistActions.All)
        val row = target.copy(trackCount = 4)
        val writer = FakeWriter(addIndex = 2)
        val store = store(libraryPages = mapOf(LibraryFilter.Playlists to LibraryPage(listOf(row))))
        val controller = controller(FakeCatalog(), store, this, writer = writer)

        assertEquals(2, controller.add(target, second))
        assertEquals(5, store.value.playlistRow("target").trackCount)

        controller.undoAdd(target, second, 2)

        assertEquals(4, store.value.playlistRow("target").trackCount)
    }

    private fun SidetrackUiState.playlistRow(id: String): Playlist = libraryPages.values
        .flatMap { it.items }
        .filterIsInstance<Playlist>()
        .first { it.id == id }

    private fun store(
        collections: Map<String, Collection> = emptyMap(),
        libraryPages: Map<LibraryFilter, LibraryPage> = emptyMap()
    ) = UiStateStore(
        SidetrackUiState(signedIn = true, collections = collections, libraryPages = libraryPages)
    )

    private fun controller(
        catalog: MediaCatalog,
        store: UiStateStore,
        scope: kotlinx.coroutines.CoroutineScope,
        writer: MediaLibraryWriter? = FakeWriter()
    ) = PlaylistController(
        store = store,
        catalog = catalog,
        writer = writer,
        capabilities = { ServiceCapabilities.All },
        onCreated = {},
        online = { true },
        scope = scope
    )

    private fun track(id: String) = Track(
        id = id,
        title = id.replaceFirstChar { it.uppercase() },
        artist = "Artist",
        artistId = "artist",
        albumId = "album",
        artRes = 0,
        durationMs = 180_000L
    )

    private fun playlist(id: String, actions: PlaylistActions) = Playlist(
        id = id,
        name = id.replaceFirstChar { it.uppercase() },
        owner = "You",
        ownerId = null,
        artRes = 0,
        tracks = emptyList(),
        actions = actions
    )

    private fun collection(id: String, items: List<Track>, actions: PlaylistActions) = Collection(
        type = CollectionType.Playlist,
        id = id,
        title = id.replaceFirstChar { it.uppercase() },
        subtitle = "You",
        artRes = 0,
        items = items,
        playlistActions = actions
    )

    private class FakeCatalog(
        private val playlistPages: Map<Int, List<Playlist>> = emptyMap()
    ) : MediaCatalog {
        override val capabilities: ServiceCapabilities = ServiceCapabilities.All
        val requestedOffsets = mutableListOf<Int>()

        override suspend fun library(section: LibrarySection, offset: Int, limit: Int): Page<LibraryEntry> {
            if (section != LibrarySection.Playlists) return Page()
            requestedOffsets += offset
            return Page(
                items = playlistPages[offset].orEmpty(),
                total = playlistPages.values.sumOf { it.size },
                hasMore = playlistPages.keys.any { it > offset }
            )
        }

        override suspend fun collection(key: MediaKey, offset: Int, limit: Int): Collection? = null
        override suspend fun artist(key: MediaKey): Artist? = null
        override suspend fun albums(artist: MediaKey, offset: Int, limit: Int): Page<Album> = Page()
        override suspend fun topTracks(artist: MediaKey, offset: Int, limit: Int): Page<Track> = Page()
        override suspend fun search(query: String, scopes: Set<SearchScope>, offset: Int, limit: Int): SearchResults = SearchResults()
        override suspend fun resolvePlaybackSource(item: MediaItem): PlaybackSource = PlaybackSource(id = item.key.encode(), uri = "")
    }

    private class FakeWriter(val addIndex: Int = 3) : MediaLibraryWriter {
        var addCalls = 0
        var addedPlaylist: MediaKey? = null
        var addedItem: MediaItem? = null
        var removedPlaylist: MediaKey? = null
        var removedItem: MediaItem? = null
        var removedIndex: Int? = null
        var createdName: String? = null
        var createdSeed: List<MediaKey>? = null

        override suspend fun isSaved(item: MediaKey): Boolean? = null
        override suspend fun isFollowed(artist: MediaKey): Boolean? = null
        override suspend fun setSaved(item: MediaKey, saved: Boolean) = Unit
        override suspend fun setFollowed(artist: MediaKey, followed: Boolean) = Unit

        override suspend fun addToPlaylist(playlist: MediaKey, item: MediaItem): Int? {
            addCalls++
            addedPlaylist = playlist
            addedItem = item
            return addIndex
        }

        override suspend fun removeFromPlaylist(playlist: MediaKey, item: MediaItem, index: Int?) {
            removedPlaylist = playlist
            removedItem = item
            removedIndex = index
        }

        override suspend fun createPlaylist(name: String, seed: List<MediaKey>): Playlist {
            createdName = name
            createdSeed = seed
            return Playlist(
                id = "created",
                name = name,
                owner = "You",
                ownerId = null,
                artRes = 0,
                tracks = emptyList(),
                actions = PlaylistActions.All
            )
        }
    }
}
