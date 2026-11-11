package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.R
import io.github.manuelrtsch.sideplay.data.SpotifyCatalog

/**
 * The screenshot suite's account. Everything here is local, fixed data shaped like the gateway
 * response: the UI can be reviewed without a Spotify login, a native library, or a network.
 */
object MockSpotifyDataSource : SpotifyCatalog {
    private fun t(
        id: String,
        title: String,
        artist: String,
        artistId: String,
        albumId: String,
        art: Int,
        seconds: Int,
        explicit: Boolean = false,
        playable: Boolean = true
    ) = SpotifyTrack(id, title, artist, artistId, albumId, art, seconds * 1000L, explicit, playable)

    private val nightSignals = listOf(
        t("ns1", "Afterglow", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 227),
        t("ns2", "Static Bloom", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 194),
        t("ns3", "Midnight Radio", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 243),
        t("ns4", "Paper Moon", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 211),
        t("ns5", "Low Tide", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 258, explicit = true),
        t("ns6", "Neon Rooms", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 202),
        t("ns7", "Slow Motion", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 236),
        t("ns8", "Satellite", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 188),
        t("ns9", "Wide Awake", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 219),
        t("ns10", "Last Light", "Aurora Vale", "aurora", "night_signals", R.drawable.cover_night_signals, 271)
    )

    private val glassHours = listOf(
        t("gh1", "Glass Hours", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 245),
        t("gh2", "Amber", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 201),
        t("gh3", "Held Open", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 230),
        t("gh4", "The Quiet Part", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 218),
        t("gh5", "Half a Mile", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 256),
        t("gh6", "Window Seat", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 192),
        t("gh7", "Soft Landing", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 274),
        t("gh8", "Glass Hours (Reprise)", "Aurora Vale", "aurora", "glass_hours", R.drawable.cover_glass_hours, 133)
    )

    private val coastline = listOf(
        t("co1", "Coastline", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 232),
        t("co2", "Saltwater", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 207),
        t("co3", "Blue Hour", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 248),
        t("co4", "North Wind", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 196),
        t("co5", "Open Water", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 263),
        t("co6", "Every Harbour", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 224),
        t("co7", "Drift", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 181),
        t("co8", "Coming Home", "Mira Sol", "mira", "coastline", R.drawable.cover_coastline, 286)
    )

    private val roomsWeLeft = listOf(
        t("rw1", "Rooms We Left", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 239),
        t("rw2", "Hallway Light", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 204),
        t("rw3", "Furniture", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 217, explicit = true),
        t("rw4", "A Door, Ajar", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 251),
        t("rw5", "Everything We Kept", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 268),
        t("rw6", "Keys", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 185),
        t("rw7", "After the Lease", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 233),
        t("rw8", "Empty House", "The Static Coast", "static", "rooms_we_left", R.drawable.cover_rooms_we_left, 297)
    )

    private val goldenHour = listOf(
        t("go1", "Golden Hour", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 221),
        t("go2", "Marigold", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 198),
        t("go3", "Long Shadows", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 242),
        t("go4", "Honey", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 209),
        t("go5", "Porch Song", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 257),
        t("go6", "Warm Front", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 188),
        t("go7", "Before the Streetlights", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 236),
        t("go8", "Still Daylight", "Ivy & Ash", "ivy", "golden_hour", R.drawable.cover_golden_hour, 265)
    )

    private val softFocus = listOf(
        t("sf1", "Soft Focus", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 214),
        t("sf2", "Pastel Static", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 199),
        t("sf3", "Lavender", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 238),
        t("sf4", "Low Resolution", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 226),
        t("sf5", "Silver Lining", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 247),
        t("sf6", "A Little Blur", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 183),
        t("sf7", "Daylight Savings", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 252),
        t("sf8", "Out of Frame", "Lumen Park", "lumen", "soft_focus", R.drawable.cover_soft_focus, 269)
    )

    val artists: List<SpotifyArtist> = listOf(
        SpotifyArtist("aurora", "Aurora Vale", R.drawable.cover_aurora_vale, listOf("night_signals", "glass_hours")),
        SpotifyArtist("mira", "Mira Sol", R.drawable.cover_mira_sol, listOf("coastline")),
        SpotifyArtist("static", "The Static Coast", R.drawable.cover_static_coast, listOf("rooms_we_left")),
        SpotifyArtist("ivy", "Ivy & Ash", R.drawable.cover_ivy_ash, listOf("golden_hour")),
        SpotifyArtist("lumen", "Lumen Park", R.drawable.cover_lumen_park, listOf("soft_focus"))
    )

    val albums: List<SpotifyAlbum> = listOf(
        SpotifyAlbum("night_signals", "Night Signals", "Aurora Vale", "aurora", 2024, R.drawable.cover_night_signals, nightSignals),
        SpotifyAlbum("glass_hours", "Glass Hours", "Aurora Vale", "aurora", 2022, R.drawable.cover_glass_hours, glassHours),
        SpotifyAlbum("coastline", "Coastline", "Mira Sol", "mira", 2023, R.drawable.cover_coastline, coastline),
        SpotifyAlbum("rooms_we_left", "Rooms We Left", "The Static Coast", "static", 2024, R.drawable.cover_rooms_we_left, roomsWeLeft),
        SpotifyAlbum("golden_hour", "Golden Hour", "Ivy & Ash", "ivy", 2021, R.drawable.cover_golden_hour, goldenHour),
        SpotifyAlbum("soft_focus", "Soft Focus", "Lumen Park", "lumen", 2025, R.drawable.cover_soft_focus, softFocus)
    )

    val playlists: List<SpotifyPlaylist> = listOf(
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

    val shows: List<SpotifyShow> = listOf(
        SpotifyShow(
            "long_way_home",
            "The Long Way Home",
            "Northline Audio",
            R.drawable.cover_long_way_home,
            listOf(
                SpotifyEpisode("lw1", "The Road Out of Town", "The Long Way Home", "long_way_home", "12 Mar 2025", R.drawable.cover_long_way_home, 2_442_000L),
                SpotifyEpisode("lw2", "A Room with One Window", "The Long Way Home", "long_way_home", "19 Mar 2025", R.drawable.cover_long_way_home, 2_118_000L),
                SpotifyEpisode("lw3", "The Ferry at Dusk", "The Long Way Home", "long_way_home", "26 Mar 2025", R.drawable.cover_long_way_home, 2_676_000L),
                SpotifyEpisode("lw4", "Notes from the Coast Road", "The Long Way Home", "long_way_home", "2 Apr 2025", R.drawable.cover_long_way_home, 2_307_000L),
                SpotifyEpisode("lw5", "The Last Bus", "The Long Way Home", "long_way_home", "9 Apr 2025", R.drawable.cover_long_way_home, 2_529_000L)
            )
        ),
        SpotifyShow(
            "small_hours",
            "Small Hours",
            "Northline Audio",
            R.drawable.cover_small_hours,
            listOf(
                SpotifyEpisode("sh1", "3:14 AM", "Small Hours", "small_hours", "4 Feb 2025", R.drawable.cover_small_hours, 1_842_000L),
                SpotifyEpisode("sh2", "The Night Shift", "Small Hours", "small_hours", "11 Feb 2025", R.drawable.cover_small_hours, 1_976_000L),
                SpotifyEpisode("sh3", "A Film We Nearly Made", "Small Hours", "small_hours", "18 Feb 2025", R.drawable.cover_small_hours, 2_035_000L),
                SpotifyEpisode("sh4", "Room Tone", "Small Hours", "small_hours", "25 Feb 2025", R.drawable.cover_small_hours, 1_788_000L)
            )
        )
    )

    /** The default Liked Songs set, already saved in the mock account. */
    val liked: List<SpotifyTrack> = listOf(
        nightSignals[0], nightSignals[3], coastline[0], coastline[2], roomsWeLeft[1],
        goldenHour[0], goldenHour[5], softFocus[2], glassHours[3], nightSignals[7]
    )

    val recentlyPlayed: List<SpotifyTrack> = listOf(
        nightSignals[0], coastline[2], roomsWeLeft[1], goldenHour[0], softFocus[2],
        nightSignals[5], glassHours[1], coastline[6], roomsWeLeft[5]
    )

    fun album(id: String): SpotifyAlbum? = albums.firstOrNull { it.id == id }
    fun playlist(id: String): SpotifyPlaylist? = playlists.firstOrNull { it.id == id }
    fun show(id: String): SpotifyShow? = shows.firstOrNull { it.id == id }
    override suspend fun artist(id: String): SpotifyArtist? = artists.firstOrNull { it.id == id }
    fun track(id: String): SpotifyTrack? = albums.flatMap { it.tracks }.firstOrNull { it.id == id }

    override suspend fun collection(
        type: SpotifyCollectionType,
        id: String,
        offset: Int,
        limit: Int
    ): SpotifyCollection? = when (type) {
        SpotifyCollectionType.Album -> album(id)?.let {
            val items = it.tracks.drop(offset).take(limit)
            SpotifyCollection(
                type, id, it.title, "${it.artist} · ${it.year} · ${it.tracks.size} tracks", it.artRes, items, it.artistId,
                totalItems = it.tracks.size, hasMore = offset + items.size < it.tracks.size
            )
        }

        SpotifyCollectionType.Playlist -> playlist(id)?.let {
            val items = it.tracks.drop(offset).take(limit)
            SpotifyCollection(
                type, id, it.name, "${it.owner} · ${it.tracks.size} tracks", it.artRes, items, it.ownerId,
                totalItems = it.tracks.size, hasMore = offset + items.size < it.tracks.size
            )
        }

        SpotifyCollectionType.Show -> show(id)?.let {
            val items = it.episodes.drop(offset).take(limit)
            SpotifyCollection(
                type,
                id,
                it.name,
                "${it.publisher} · ${it.episodes.size} episodes",
                it.artRes,
                items,
                totalItems = it.episodes.size,
                hasMore = offset + items.size < it.episodes.size
            )
        }
    }

    override suspend fun topTracks(artist: SpotifyArtist, offset: Int, limit: Int): SpotifyPage<SpotifyTrack> {
        val all = albums.filter { it.id in artist.albumIds }.flatMap { it.tracks }
        return SpotifyPage.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    fun topTracks(artist: SpotifyArtist, limit: Int): List<SpotifyTrack> = albums.filter { it.id in artist.albumIds }.flatMap { it.tracks }.take(limit)

    override suspend fun albumsBy(artist: SpotifyArtist, offset: Int, limit: Int): SpotifyPage<SpotifyAlbum> {
        val all = artist.albumIds.mapNotNull(::album)
        return SpotifyPage.of(all.drop(offset).take(limit), offset, limit, all.size)
    }

    override suspend fun library(filter: LibraryFilter, offset: Int, limit: Int): SpotifyPage<Any> {
        val all: List<Any> = when (filter) {
            LibraryFilter.LikedSongs -> liked
            LibraryFilter.Playlists -> playlists
            LibraryFilter.Albums -> albums
            LibraryFilter.Artists -> artists
            LibraryFilter.Shows -> shows
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
        val allTracks = albums.flatMap { it.tracks }
        fun <T> page(items: List<T>): List<T> = items.drop(offset).take(limit)
        return SpotifySearchResults(
            tracks = if (SearchScope.Tracks in scopes) page(allTracks.filter { match(it.title, it.artist) }) else emptyList(),
            artists = if (SearchScope.Artists in scopes) page(artists.filter { match(it.name) }) else emptyList(),
            albums = if (SearchScope.Albums in scopes) page(albums.filter { match(it.title, it.artist) }) else emptyList(),
            playlists = if (SearchScope.Playlists in scopes) page(playlists.filter { match(it.name, it.owner) }) else emptyList(),
            shows = if (SearchScope.Shows in scopes) page(shows.filter { match(it.name, it.publisher) }) else emptyList()
        )
    }
}
