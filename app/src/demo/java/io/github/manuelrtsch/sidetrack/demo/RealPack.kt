package io.github.manuelrtsch.sidetrack.demo

import io.github.manuelrtsch.sidetrack.Album
import io.github.manuelrtsch.sidetrack.Artist
import io.github.manuelrtsch.sidetrack.Collection
import io.github.manuelrtsch.sidetrack.CollectionType
import io.github.manuelrtsch.sidetrack.DemoContent
import io.github.manuelrtsch.sidetrack.Episode
import io.github.manuelrtsch.sidetrack.LibraryFilter
import io.github.manuelrtsch.sidetrack.MediaItem
import io.github.manuelrtsch.sidetrack.Page
import io.github.manuelrtsch.sidetrack.Playlist
import io.github.manuelrtsch.sidetrack.R
import io.github.manuelrtsch.sidetrack.SearchResults
import io.github.manuelrtsch.sidetrack.SearchScope
import io.github.manuelrtsch.sidetrack.Show
import io.github.manuelrtsch.sidetrack.Track
import io.github.manuelrtsch.sidetrack.data.HistoryRepository
import io.github.manuelrtsch.sidetrack.data.QueueRepository
import io.github.manuelrtsch.sidetrack.data.SpotifyCatalog
import io.github.manuelrtsch.sidetrack.data.SpotifyLibraryWriter

/**
 * The demo build's account: a fixed catalog of real, widely known songs that are not chart
 * material, so a promo video shows something recognisable instead of invented artists.
 *
 * Nothing here is streamed on purpose: every item plays [STOCK_URI] — a stock library-music track
 * (the one the previous pack used behind "Afterglow") — because the recordings are muted and the
 * audio is irrelevant. Artwork is rather more visible than sound, so each record carries its
 * official cover, bundled under `src/demo/res` and therefore shown instantly and offline. Only the
 * playlists and shows, which are still invented, use the generated art. [FictionalPack] remains the
 * fully fictional backup.
 *
 * The pinned search query in `flows/suite/02` and `flows/promo/02` is `bowie`, so the artist, his
 * two albums and the show below all match it and every search section is filled.
 */
object RealPack : DemoContent, SpotifyCatalog, SpotifyLibraryWriter {

    /** Single playback source for every item. Never heard: the videos are muted. */
    private const val STOCK_URI = "spotify:track:2GrbfjJz9M1kbplDKslSor"

    private fun t(
        id: String,
        title: String,
        artist: String,
        artistId: String,
        albumId: String,
        art: Int,
        seconds: Int,
        explicit: Boolean = false
    ) = Track(
        id = id,
        title = title,
        artist = artist,
        artistId = artistId,
        albumId = albumId,
        artRes = art,
        durationMs = seconds * 1000L,
        explicit = explicit,
        playable = true,
        uri = STOCK_URI
    )

    private fun e(
        id: String,
        title: String,
        show: String,
        showId: String,
        published: String,
        art: Int,
        durationMs: Long
    ) = Episode(id, title, show, showId, published, art, durationMs, uri = STOCK_URI)

    private val heroes = listOf(
        t("bw1", "Beauty and the Beast", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 212),
        t("bw2", "Joe the Lion", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 185),
        t("bw3", "Heroes", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 367),
        t("bw4", "Sons of the Silent Age", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 195),
        t("bw5", "V-2 Schneider", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 190),
        t("bw6", "Neuköln", "David Bowie", "bowie", "heroes", R.drawable.album_heroes, 274)
    )

    private val low = listOf(
        t("bl1", "Speed of Life", "David Bowie", "bowie", "low", R.drawable.album_low, 166),
        t("bl2", "Breaking Glass", "David Bowie", "bowie", "low", R.drawable.album_low, 111),
        t("bl3", "What in the World", "David Bowie", "bowie", "low", R.drawable.album_low, 143),
        t("bl4", "Sound and Vision", "David Bowie", "bowie", "low", R.drawable.album_low, 183),
        t("bl5", "Always Crashing in the Same Car", "David Bowie", "bowie", "low", R.drawable.album_low, 216),
        t("bl6", "Warszawa", "David Bowie", "bowie", "low", R.drawable.album_low, 377)
    )

    private val spell = listOf(
        t("ns1", "I Put a Spell on You", "Nina Simone", "nina", "spell", R.drawable.album_spell, 155),
        t("ns2", "Tomorrow Is My Turn", "Nina Simone", "nina", "spell", R.drawable.album_spell, 225),
        t("ns3", "Ne Me Quitte Pas", "Nina Simone", "nina", "spell", R.drawable.album_spell, 215),
        t("ns4", "Feeling Good", "Nina Simone", "nina", "spell", R.drawable.album_spell, 173),
        t("ns5", "Wild Is the Wind", "Nina Simone", "nina", "spell", R.drawable.album_spell, 206),
        t("ns6", "Don't Let Me Be Misunderstood", "Nina Simone", "nina", "spell", R.drawable.album_spell, 164)
    )

    private val mezzanine = listOf(
        t("ma1", "Angel", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 378),
        t("ma2", "Risingson", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 298),
        t("ma3", "Teardrop", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 329),
        t("ma4", "Inertia Creeps", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 356),
        t("ma5", "Exchange", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 251),
        t("ma6", "Man Next Door", "Massive Attack", "massive", "mezzanine", R.drawable.album_mezzanine, 356)
    )

    private val zombie = listOf(
        t("fk1", "Zombie", "Fela Kuti", "fela", "zombie", R.drawable.album_zombie, 745),
        t("fk2", "Mister Follow Follow", "Fela Kuti", "fela", "zombie", R.drawable.album_zombie, 103),
        t("fk3", "Observation No Crime", "Fela Kuti", "fela", "zombie", R.drawable.album_zombie, 495)
    )

    private val bvsc = listOf(
        t("bv1", "Chan Chan", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 256),
        t("bv2", "De Camino a la Vereda", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 300),
        t("bv3", "El Cuarto de Tula", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 448),
        t("bv4", "Dos Gardenias", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 184),
        t("bv5", "¿Y Tú Qué Has Hecho?", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 194),
        t("bv6", "Veinte Años", "Buena Vista Social Club", "bvsc", "bvsc", R.drawable.album_bvsc, 211)
    )

    private val artistList = listOf(
        Artist("bowie", "David Bowie", R.drawable.album_heroes, listOf("heroes", "low")),
        Artist("nina", "Nina Simone", R.drawable.album_spell, listOf("spell")),
        Artist("massive", "Massive Attack", R.drawable.album_mezzanine, listOf("mezzanine")),
        Artist("fela", "Fela Kuti", R.drawable.album_zombie, listOf("zombie")),
        Artist("bvsc", "Buena Vista Social Club", R.drawable.album_bvsc, listOf("bvsc")),
        // A sixth artist, credited through the two albums he co-wrote with Bowie.
        Artist("eno", "Brian Eno", R.drawable.album_low, listOf("low", "heroes"))
    )

    private val albumList = listOf(
        Album("heroes", "Heroes", "David Bowie", "bowie", 1977, R.drawable.album_heroes, heroes),
        Album("low", "Low", "David Bowie", "bowie", 1977, R.drawable.album_low, low),
        Album("spell", "I Put a Spell on You", "Nina Simone", "nina", 1965, R.drawable.album_spell, spell),
        Album("mezzanine", "Mezzanine", "Massive Attack", "massive", 1998, R.drawable.album_mezzanine, mezzanine),
        Album("zombie", "Zombie", "Fela Kuti", "fela", 1976, R.drawable.album_zombie, zombie),
        Album("bvsc", "Buena Vista Social Club", "Buena Vista Social Club", "bvsc", 1997, R.drawable.album_bvsc, bvsc)
    )

    private val playlistList = listOf(
        Playlist(
            "late_night",
            "Late Night Drive",
            "You",
            null,
            R.drawable.cover_late_night_drive,
            listOf(mezzanine[0], mezzanine[3], low[4], bvsc[4], heroes[5], mezzanine[2], spell[2], low[2])
        ),
        Playlist(
            "sunday_morning",
            "Sunday Morning",
            "You",
            null,
            R.drawable.cover_sunday_morning,
            listOf(bvsc[3], spell[1], heroes[0], low[3], bvsc[5], spell[4], heroes[4])
        ),
        Playlist(
            "focus",
            "Focus / No Words",
            "You",
            null,
            R.drawable.cover_focus_no_words,
            listOf(low[5], heroes[5], low[0], zombie[2], mezzanine[4], low[1], bvsc[2])
        ),
        Playlist(
            "kitchen_radio",
            "Kitchen Radio",
            "You",
            null,
            R.drawable.cover_kitchen_radio,
            listOf(bvsc[0], heroes[2], spell[3], zombie[0], mezzanine[2], heroes[1], bvsc[1])
        ),
        Playlist(
            "after_hours",
            "After Hours",
            "You",
            null,
            R.drawable.cover_after_hours,
            listOf(mezzanine[1], spell[2], low[1], zombie[1], heroes[3], bvsc[5])
        ),
        Playlist(
            "slow_sundays",
            "Slow Sundays",
            "You",
            null,
            R.drawable.cover_slow_sundays,
            listOf(bvsc[3], spell[0], heroes[4], low[2], mezzanine[4], bvsc[1])
        )
    )

    private val showList = listOf(
        Show(
            "bowie_life",
            "Bowie: A Life",
            "Northline Audio",
            R.drawable.cover_long_way_home,
            listOf(
                e("bi1", "The Boy from Brixton", "Bowie: A Life", "bowie_life", "12 Mar 2025", R.drawable.cover_long_way_home, 2_472_000L),
                e("bi2", "Ziggy and After", "Bowie: A Life", "bowie_life", "19 Mar 2025", R.drawable.cover_long_way_home, 2_334_000L),
                e("bi3", "Berlin, 1977", "Bowie: A Life", "bowie_life", "26 Mar 2025", R.drawable.cover_long_way_home, 2_646_000L),
                e("bi4", "The Thin White Duke", "Bowie: A Life", "bowie_life", "2 Apr 2025", R.drawable.cover_long_way_home, 2_367_000L),
                e("bi5", "Blackstar", "Bowie: A Life", "bowie_life", "9 Apr 2025", R.drawable.cover_long_way_home, 2_762_000L)
            )
        ),
        Show(
            "small_hours",
            "Small Hours",
            "Northline Audio",
            R.drawable.cover_small_hours,
            listOf(
                e("sh1", "3:14 AM", "Small Hours", "small_hours", "4 Feb 2025", R.drawable.cover_small_hours, 1_842_000L),
                e("sh2", "The Night Shift", "Small Hours", "small_hours", "11 Feb 2025", R.drawable.cover_small_hours, 1_976_000L),
                e("sh3", "A Film We Nearly Made", "Small Hours", "small_hours", "18 Feb 2025", R.drawable.cover_small_hours, 2_035_000L),
                e("sh4", "Room Tone", "Small Hours", "small_hours", "25 Feb 2025", R.drawable.cover_small_hours, 1_788_000L)
            )
        ),
        Show(
            "between_takes",
            "Between Takes",
            "Northline Audio",
            R.drawable.cover_between_takes,
            listOf(
                e("bt1", "The First Read", "Between Takes", "between_takes", "6 May 2025", R.drawable.cover_between_takes, 2_208_000L),
                e("bt2", "Notes in the Margin", "Between Takes", "between_takes", "13 May 2025", R.drawable.cover_between_takes, 2_431_000L),
                e("bt3", "The Cut We Kept", "Between Takes", "between_takes", "20 May 2025", R.drawable.cover_between_takes, 2_115_000L)
            )
        ),
        Show(
            "night_bus",
            "The Night Bus",
            "Northline Audio",
            R.drawable.cover_the_night_bus,
            listOf(
                e("nb1", "Two Streets Over", "The Night Bus", "night_bus", "3 Jun 2025", R.drawable.cover_the_night_bus, 1_964_000L),
                e("nb2", "Last Stop", "The Night Bus", "night_bus", "10 Jun 2025", R.drawable.cover_the_night_bus, 2_142_000L),
                e("nb3", "Home Before Light", "The Night Bus", "night_bus", "17 Jun 2025", R.drawable.cover_the_night_bus, 1_878_000L)
            )
        ),
        Show(
            "signal_lost",
            "Signal Lost",
            "Station Nine",
            R.drawable.cover_signal_lost,
            listOf(
                e("sl1", "Carrier Wave", "Signal Lost", "signal_lost", "1 Apr 2025", R.drawable.cover_signal_lost, 2_311_000L),
                e("sl2", "Static Between Stations", "Signal Lost", "signal_lost", "8 Apr 2025", R.drawable.cover_signal_lost, 2_560_000L),
                e("sl3", "The Long Reply", "Signal Lost", "signal_lost", "15 Apr 2025", R.drawable.cover_signal_lost, 2_077_000L)
            )
        ),
        Show(
            "open_studio",
            "Open Studio",
            "Station Nine",
            R.drawable.cover_open_studio,
            listOf(
                e("os1", "First Draft", "Open Studio", "open_studio", "2 Jul 2025", R.drawable.cover_open_studio, 2_485_000L),
                e("os2", "Margins", "Open Studio", "open_studio", "9 Jul 2025", R.drawable.cover_open_studio, 2_196_000L),
                e("os3", "The Edit", "Open Studio", "open_studio", "16 Jul 2025", R.drawable.cover_open_studio, 2_374_000L)
            )
        )
    )

    /** Ten saved tracks, spread across the five artists. */
    private val likedTracks: List<Track> = listOf(
        heroes[2], spell[0], spell[3], mezzanine[2], mezzanine[0],
        bvsc[0], bvsc[3], low[3], zombie[0], heroes[0]
    )

    /** Newest first. Seeded into history so Home and the History screen have rows. */
    private val recents: List<Track> = listOf(
        heroes[3], mezzanine[2], spell[5], bvsc[1], low[1],
        zombie[1], heroes[5], mezzanine[4], bvsc[5]
    )

    // Library writes are in-memory. They reset with the process, which is exactly what a
    // repeatable recording needs: every take starts with the same labels.
    private val savedTracks: MutableSet<String> = likedTracks.map { it.id }.toMutableSet()
    private val savedCollections: MutableSet<String> = (
        albumList.map { "album:${it.id}" } +
            playlistList.map { "playlist:${it.id}" } +
            showList.map { "show:${it.id}" }
        ).toMutableSet()
    private val followedArtists: MutableSet<String> = artistList.map { it.id }.toMutableSet()

    override fun catalog(): SpotifyCatalog = this

    /**
     * Rewrites history and the queue at every process start. Both files are local playback state,
     * not account data, and a recording must always begin from the same rows even when the app was
     * used normally before. A warm restart within one take keeps whatever the take did.
     */
    override fun seed(history: HistoryRepository, queue: QueueRepository) {
        history.clear()
        // record() prepends, so insert oldest first to end up newest first.
        val now = System.currentTimeMillis()
        recents.asReversed().forEachIndexed { index, item ->
            history.record(item, now - (recents.size - index) * 60_000L)
        }
        // A track is current and the next two are tracks too: the transport flow needs Save and
        // Seek, which episodes do not have.
        queue.stop()
        queue.play(recents.take(3), 0)
    }

    override suspend fun isTrackSaved(id: String): Boolean = synchronized(savedTracks) { id in savedTracks }

    override suspend fun isSaved(type: CollectionType, id: String): Boolean = synchronized(savedCollections) { "${type.key}:$id" in savedCollections }

    override suspend fun isArtistFollowed(id: String): Boolean = synchronized(followedArtists) { id in followedArtists }

    override suspend fun setTrackSaved(id: String, saved: Boolean) {
        synchronized(savedTracks) { if (saved) savedTracks.add(id) else savedTracks.remove(id) }
    }

    override suspend fun setSaved(type: CollectionType, id: String, saved: Boolean) {
        val key = "${type.key}:$id"
        synchronized(savedCollections) { if (saved) savedCollections.add(key) else savedCollections.remove(key) }
    }

    override suspend fun setArtistFollowed(id: String, followed: Boolean) {
        synchronized(followedArtists) { if (followed) followedArtists.add(id) else followedArtists.remove(id) }
    }

    override suspend fun artist(id: String): Artist? = artistList.firstOrNull { it.id == id }

    override suspend fun collection(
        type: CollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): Collection? = when (type) {
        CollectionType.Album -> albumList.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.title, "${it.artist} · ${it.year} · ${it.tracks.size} tracks", it.artRes, it.tracks, it.artistId, offset, limit)
        }

        CollectionType.Playlist -> playlistList.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.name, "${it.owner} · ${it.tracks.size} tracks", it.artRes, it.tracks, it.ownerId, offset, limit)
        }

        CollectionType.Show -> showList.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.name, "${it.publisher} · ${it.episodes.size} episodes", it.artRes, it.episodes, null, offset, limit)
        }
    }

    private fun page(
        type: CollectionType,
        id: String,
        title: String,
        subtitle: String,
        artRes: Int,
        all: List<MediaItem>,
        artistId: String?,
        offset: Int,
        limit: Int
    ): Collection {
        val items = all.drop(offset).take(limit)
        return Collection(
            type, id, title, subtitle, artRes, items, artistId,
            totalItems = all.size, hasMore = offset + items.size < all.size
        )
    }

    override suspend fun albumsBy(artist: Artist, offset: Int, limit: Int): Page<Album> {
        val all = artist.albumIds.mapNotNull { id -> albumList.firstOrNull { it.id == id } }
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun topTracks(artist: Artist, offset: Int, limit: Int): Page<Track> {
        val all = albumList.filter { it.id in artist.albumIds }.flatMap { it.tracks }
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun songRadio(trackId: String, limit: Int): List<Track> {
        val all = albumList.flatMap { it.tracks }
        val seed = all.firstOrNull { it.id == trackId }
        val sameArtist = all.filter { seed != null && it.artistId == seed.artistId }
        return (sameArtist + all).distinctBy { it.id }.filter { it.id != trackId }.take(limit)
    }

    override suspend fun artistRadio(artistId: String, limit: Int): List<Track> {
        val artist = artistList.firstOrNull { it.id == artistId } ?: return emptyList()
        val own = albumList.filter { it.id in artist.albumIds }.flatMap { it.tracks }
        val rest = albumList.filterNot { it.id in artist.albumIds }.flatMap { it.tracks }
        return (own + rest).distinctBy { it.id }.take(limit)
    }

    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): Page<Any> {
        val all: List<Any> = when (filter) {
            LibraryFilter.LikedSongs -> likedTracks
            LibraryFilter.Playlists -> playlistList
            LibraryFilter.Albums -> albumList
            LibraryFilter.Artists -> artistList
            LibraryFilter.Shows -> showList
        }
        return Page.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SearchResults {
        val q = query.trim()
        if (q.length < 2) return SearchResults()
        fun match(vararg text: String) = text.any { it.contains(q, ignoreCase = true) }
        fun <T> pageOf(items: List<T>): List<T> = items.drop(offset).take(limit)
        val allTracks = albumList.flatMap { it.tracks }
        return SearchResults(
            tracks = if (SearchScope.Tracks in scopes) pageOf(allTracks.filter { match(it.title, it.artist) }) else emptyList(),
            artists = if (SearchScope.Artists in scopes) pageOf(artistList.filter { match(it.name) }) else emptyList(),
            albums = if (SearchScope.Albums in scopes) pageOf(albumList.filter { match(it.title, it.artist) }) else emptyList(),
            playlists = if (SearchScope.Playlists in scopes) pageOf(playlistList.filter { match(it.name, it.owner) }) else emptyList(),
            shows = if (SearchScope.Shows in scopes) pageOf(showList.filter { match(it.name, it.publisher) }) else emptyList()
        )
    }
}

private val CollectionType.key: String
    get() = when (this) {
        CollectionType.Album -> "album"
        CollectionType.Playlist -> "playlist"
        CollectionType.Show -> "show"
    }
