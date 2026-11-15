package io.github.manuelrtsch.sideplay.demo

import io.github.manuelrtsch.sideplay.DemoContent
import io.github.manuelrtsch.sideplay.LibraryFilter
import io.github.manuelrtsch.sideplay.R
import io.github.manuelrtsch.sideplay.SearchScope
import io.github.manuelrtsch.sideplay.SpotifyAlbum
import io.github.manuelrtsch.sideplay.SpotifyArtist
import io.github.manuelrtsch.sideplay.SpotifyCollection
import io.github.manuelrtsch.sideplay.SpotifyCollectionType
import io.github.manuelrtsch.sideplay.SpotifyEpisode
import io.github.manuelrtsch.sideplay.SpotifyMediaItem
import io.github.manuelrtsch.sideplay.SpotifyPage
import io.github.manuelrtsch.sideplay.SpotifyPlaylist
import io.github.manuelrtsch.sideplay.SpotifySearchResults
import io.github.manuelrtsch.sideplay.SpotifyShow
import io.github.manuelrtsch.sideplay.SpotifyTrack
import io.github.manuelrtsch.sideplay.data.SpotifyCatalog
import io.github.manuelrtsch.sideplay.data.SpotifyHistoryRepository
import io.github.manuelrtsch.sideplay.data.SpotifyLibraryWriter
import io.github.manuelrtsch.sideplay.data.SpotifyQueueRepository

/**
 * The demo build's original account: a fully fictional pack with a real Spotify URI per item.
 *
 * Kept as the backup content set. The demo build serves [RealPack] by default; pass
 * `-PdemoPack=fictional` to build this one instead. It needs no real titles, so it stays useful
 * when the real pack has to be re-picked or when a video must not name any artist.
 *
 * Only the catalog is replaced. Playback still uses the signed-in account through librespot, so
 * every item points at a real, playable track. The audio is never heard in a recording (videos are
 * muted); the URIs exist so the transport, seek and end-of-track paths are the production ones.
 *
 * The pack is the screenshot suite's fixture in a device build. The recorded search uses the
 * pinned query "bowie", so the invented artist and a show carry that word and all four search
 * sections appear; otherwise every name is made up.
 * Everything here is neutral on purpose — no personal library, no real title in the video.
 */
object FictionalPack : DemoContent, SpotifyCatalog, SpotifyLibraryWriter {

    /**
     * Real tracks used only as playback sources, with their real durations so the progress bar and
     * the seek target stay truthful. Album covers, titles and artists are never taken from them.
     */
    private val donors: List<Pair<String, Long>> = listOf(
        "spotify:track:2GrbfjJz9M1kbplDKslSor" to 177_000L, // Allston Night Owl
        "spotify:track:3ehAmD8XuuiMtsbCZ11KLO" to 170_000L, // OneEightFour
        "spotify:track:42aLGgbb3mTnaOQlmTENQK" to 166_000L, // Copley Beat
        "spotify:track:3fqWXWJ87N4LT1672uckKV" to 133_000L, // Three Stories
        "spotify:track:4gsLnkfjfUh2SOS0oBAxVs" to 179_000L, // Boston Landing
        "spotify:track:1qWNN5RES6RU4MYsc94GUJ" to 138_000L, // Elmore Heights
        "spotify:track:78BDfJsWGCN1cLtm2GI2AG" to 146_000L, // Greylock
        "spotify:track:5JCk3XTYSDi64wQny585ZG" to 159_000L, // Kid Kodi
        "spotify:track:3HxvvXFOubtLQNfRdOuQBT" to 184_000L, // Pedalrider
        "spotify:track:0oVkdajWjheO7lEyjQfH2P" to 185_000L, // Walking Shoes
        "spotify:track:1W9iPwwvMKgfIabAhxLmgl" to 134_000L, // Rodney Skopes
        "spotify:track:1oukfqeqx27nKVOy7a6fsU" to 158_000L // The Yards
    )

    /** An eight-hour ambient track: an episode's real length must outlast any recording. */
    private val episodeDonor = "spotify:track:6NohJPZI27aOchSS6uWXQm"

    private var nextDonor = 0

    private fun donor(): Pair<String, Long> = donors[nextDonor++ % donors.size].also {
        if (nextDonor == Int.MAX_VALUE) nextDonor = 0
    }

    private fun t(
        id: String,
        title: String,
        artist: String,
        artistId: String,
        albumId: String,
        art: Int,
        explicit: Boolean = false
    ): SpotifyTrack {
        val (uri, durationMs) = donor()
        return SpotifyTrack(
            id = id,
            title = title,
            artist = artist,
            artistId = artistId,
            albumId = albumId,
            artRes = art,
            durationMs = durationMs,
            explicit = explicit,
            playable = true,
            uri = uri
        )
    }

    private fun e(
        id: String,
        title: String,
        show: String,
        showId: String,
        published: String,
        art: Int,
        durationMs: Long
    ) = SpotifyEpisode(id, title, show, showId, published, art, durationMs, uri = episodeDonor)

    private val nightSignals = listOf(
        t("ns1", "Afterglow", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns2", "Static Bloom", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns3", "Midnight Radio", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns4", "Paper Moon", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns5", "Low Tide", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals, explicit = true),
        t("ns6", "Neon Rooms", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns7", "Slow Motion", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns8", "Satellite", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns9", "Wide Awake", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals),
        t("ns10", "Last Light", TRIO, "aurora", "night_signals", R.drawable.cover_night_signals)
    )

    private val glassHours = listOf(
        t("gh1", "Glass Hours", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh2", "Amber", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh3", "Held Open", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh4", "The Quiet Part", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh5", "Half a Mile", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh6", "Window Seat", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh7", "Soft Landing", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours),
        t("gh8", "Glass Hours (Reprise)", TRIO, "aurora", "glass_hours", R.drawable.cover_glass_hours)
    )

    private val coastline = listOf(
        t("co1", "Coastline", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co2", "Saltwater", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co3", "Blue Hour", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co4", "North Wind", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co5", "Open Water", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co6", "Every Harbour", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co7", "Drift", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline),
        t("co8", "Coming Home", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline)
    )

    private val roomsWeLeft = listOf(
        t("rw1", "Rooms We Left", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
        t("rw2", "Hallway Light", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
        t("rw3", "Furniture", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, explicit = true),
        t("rw4", "A Door, Ajar", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
        t("rw5", "Everything We Kept", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
        t("rw6", "Keys", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
        t("rw7", "After the Lease", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left),
        t("rw8", "Empty House", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left)
    )

    private val goldenHour = listOf(
        t("go1", "Golden Hour", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go2", "Marigold", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go3", "Long Shadows", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go4", "Honey", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go5", "Porch Song", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go6", "Warm Front", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go7", "Before the Streetlights", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour),
        t("go8", "Still Daylight", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour)
    )

    private val softFocus = listOf(
        t("sf1", "Soft Focus", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf2", "Pastel Static", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf3", "Lavender", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf4", "Low Resolution", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf5", "Silver Lining", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf6", "A Little Blur", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf7", "Daylight Savings", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus),
        t("sf8", "Out of Frame", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus)
    )

    private val artistList = listOf(
        SpotifyArtist("aurora", TRIO, R.drawable.cover_aurora_vale, listOf("night_signals", "glass_hours")),
        SpotifyArtist("mira", "Mira Sol", R.drawable.cover_mira_sol, listOf("coastline")),
        SpotifyArtist("static", "The Static Coast", R.drawable.cover_static_coast, listOf("rooms_we_left")),
        SpotifyArtist("ivy", "Ivy & Ash", R.drawable.cover_ivy_ash, listOf("golden_hour")),
        SpotifyArtist("lumen", "Lumen Park", R.drawable.cover_lumen_park, listOf("soft_focus"))
    )

    private val albumList = listOf(
        SpotifyAlbum("night_signals", "Night Signals", TRIO, "aurora", 2024, R.drawable.cover_night_signals, nightSignals),
        SpotifyAlbum("glass_hours", "Glass Hours", TRIO, "aurora", 2022, R.drawable.cover_glass_hours, glassHours),
        SpotifyAlbum("coastline", "Coastline", "Mira Sol", "mira", 2023, R.drawable.cover_coastline, coastline),
        SpotifyAlbum("rooms_we_left", "Rooms We Left", "The Static Coast", "static", 2024, R.drawable.cover_rooms_we_left, roomsWeLeft),
        SpotifyAlbum("golden_hour", "Golden Hour", "Ivy & Ash", "ivy", 2021, R.drawable.cover_golden_hour, goldenHour),
        SpotifyAlbum("soft_focus", "Soft Focus", "Lumen Park", "lumen", 2025, R.drawable.cover_soft_focus, softFocus)
    )

    private val playlistList = listOf(
        SpotifyPlaylist(
            "late_night",
            "Late Night Drive",
            "You",
            null,
            R.drawable.cover_late_night_drive,
            listOf(nightSignals[0], nightSignals[5], coastline[2], roomsWeLeft[1], softFocus[3], nightSignals[7], glassHours[5], coastline[6])
        ),
        SpotifyPlaylist(
            "sunday_morning",
            "Sunday Morning",
            "You",
            null,
            R.drawable.cover_sunday_morning,
            listOf(goldenHour[0], goldenHour[3], glassHours[1], coastline[0], goldenHour[6], softFocus[4], glassHours[6])
        ),
        SpotifyPlaylist(
            "focus",
            "Focus / No Words",
            "You",
            null,
            R.drawable.cover_focus_no_words,
            listOf(softFocus[0], softFocus[1], softFocus[5], glassHours[7], coastline[6], roomsWeLeft[5], nightSignals[3])
        ),
        SpotifyPlaylist(
            "kitchen_radio",
            "Kitchen Radio",
            "You",
            null,
            R.drawable.cover_kitchen_radio,
            listOf(goldenHour[1], coastline[1], nightSignals[8], goldenHour[4], glassHours[2], coastline[4], roomsWeLeft[0])
        )
    )

    private val showList = listOf(
        SpotifyShow(
            "long_way_home",
            "Bowie Vale Sessions",
            "Northline Audio",
            R.drawable.cover_long_way_home,
            listOf(
                e("lw1", "The Road Out of Town", "Bowie Vale Sessions", "long_way_home", "12 Mar 2025", R.drawable.cover_long_way_home, 2_442_000L),
                e("lw2", "A Room with One Window", "Bowie Vale Sessions", "long_way_home", "19 Mar 2025", R.drawable.cover_long_way_home, 2_118_000L),
                e("lw3", "The Ferry at Dusk", "Bowie Vale Sessions", "long_way_home", "26 Mar 2025", R.drawable.cover_long_way_home, 2_676_000L),
                e("lw4", "Notes from the Coast Road", "Bowie Vale Sessions", "long_way_home", "2 Apr 2025", R.drawable.cover_long_way_home, 2_307_000L),
                e("lw5", "The Last Bus", "Bowie Vale Sessions", "long_way_home", "9 Apr 2025", R.drawable.cover_long_way_home, 2_529_000L)
            )
        ),
        SpotifyShow(
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
        )
    )

    private val likedTracks: List<SpotifyTrack> = listOf(
        nightSignals[0], nightSignals[3], coastline[0], coastline[2], roomsWeLeft[1],
        goldenHour[0], goldenHour[5], softFocus[2], glassHours[3], nightSignals[7]
    )

    /** Newest first. Seeded into history so Home and the History screen have rows. */
    private val recents: List<SpotifyTrack> = listOf(
        nightSignals[0], coastline[2], roomsWeLeft[1], goldenHour[0], softFocus[2],
        nightSignals[5], glassHours[1], coastline[6], roomsWeLeft[5]
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
    override fun seed(history: SpotifyHistoryRepository, queue: SpotifyQueueRepository) {
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

    override suspend fun isSaved(type: SpotifyCollectionType, id: String): Boolean = synchronized(savedCollections) { "${type.key}:$id" in savedCollections }

    override suspend fun isArtistFollowed(id: String): Boolean = synchronized(followedArtists) { id in followedArtists }

    override suspend fun setTrackSaved(id: String, saved: Boolean) {
        synchronized(savedTracks) { if (saved) savedTracks.add(id) else savedTracks.remove(id) }
    }

    override suspend fun setSaved(type: SpotifyCollectionType, id: String, saved: Boolean) {
        val key = "${type.key}:$id"
        synchronized(savedCollections) { if (saved) savedCollections.add(key) else savedCollections.remove(key) }
    }

    override suspend fun setArtistFollowed(id: String, followed: Boolean) {
        synchronized(followedArtists) { if (followed) followedArtists.add(id) else followedArtists.remove(id) }
    }

    override suspend fun artist(id: String): SpotifyArtist? = artistList.firstOrNull { it.id == id }

    override suspend fun collection(
        type: SpotifyCollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): SpotifyCollection? = when (type) {
        SpotifyCollectionType.Album -> albumList.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.title, "${it.artist} · ${it.year} · ${it.tracks.size} tracks", it.artRes, it.tracks, it.artistId, offset, limit)
        }

        SpotifyCollectionType.Playlist -> playlistList.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.name, "${it.owner} · ${it.tracks.size} tracks", it.artRes, it.tracks, it.ownerId, offset, limit)
        }

        SpotifyCollectionType.Show -> showList.firstOrNull { it.id == id }?.let {
            page(type, it.id, it.name, "${it.publisher} · ${it.episodes.size} episodes", it.artRes, it.episodes, null, offset, limit)
        }
    }

    private fun page(
        type: SpotifyCollectionType,
        id: String,
        title: String,
        subtitle: String,
        artRes: Int,
        all: List<SpotifyMediaItem>,
        artistId: String?,
        offset: Int,
        limit: Int
    ): SpotifyCollection {
        val items = all.drop(offset).take(limit)
        return SpotifyCollection(
            type, id, title, subtitle, artRes, items, artistId,
            totalItems = all.size, hasMore = offset + items.size < all.size
        )
    }

    override suspend fun albumsBy(artist: SpotifyArtist, offset: Int, limit: Int): SpotifyPage<SpotifyAlbum> {
        val all = artist.albumIds.mapNotNull { albumId -> albumList.firstOrNull { it.id == albumId } }
        return SpotifyPage.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun topTracks(artist: SpotifyArtist, offset: Int, limit: Int): SpotifyPage<SpotifyTrack> {
        val all = albumList.filter { it.id in artist.albumIds }.flatMap { it.tracks }
        return SpotifyPage.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): SpotifyPage<Any> {
        val all: List<Any> = when (filter) {
            LibraryFilter.LikedSongs -> likedTracks
            LibraryFilter.Playlists -> playlistList
            LibraryFilter.Albums -> albumList
            LibraryFilter.Artists -> artistList
            LibraryFilter.Shows -> showList
        }
        return SpotifyPage.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun search(
        query: String,
        scopes: Set<SearchScope>,
        offset: Int,
        limit: Int
    ): SpotifySearchResults {
        val q = query.trim()
        if (q.length < 2) return SpotifySearchResults()
        fun match(vararg text: String) = text.any { it.contains(q, ignoreCase = true) }
        fun <T> pageOf(items: List<T>): List<T> = items.drop(offset).take(limit)
        val allTracks = albumList.flatMap { it.tracks }
        return SpotifySearchResults(
            tracks = if (SearchScope.Tracks in scopes) pageOf(allTracks.filter { match(it.title, it.artist) }) else emptyList(),
            artists = if (SearchScope.Artists in scopes) pageOf(artistList.filter { match(it.name) }) else emptyList(),
            albums = if (SearchScope.Albums in scopes) pageOf(albumList.filter { match(it.title, it.artist) }) else emptyList(),
            playlists = if (SearchScope.Playlists in scopes) pageOf(playlistList.filter { match(it.name, it.owner) }) else emptyList(),
            shows = if (SearchScope.Shows in scopes) pageOf(showList.filter { match(it.name, it.publisher) }) else emptyList()
        )
    }

    /** The artist that carries the pinned search query. */
    private const val TRIO = "Bowie Vale"
}

private val SpotifyCollectionType.key: String
    get() = when (this) {
        SpotifyCollectionType.Album -> "album"
        SpotifyCollectionType.Playlist -> "playlist"
        SpotifyCollectionType.Show -> "show"
    }
