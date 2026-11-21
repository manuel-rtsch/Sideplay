package io.github.manuelrtsch.sidetrack

/**
 * Stable identity of a music service. The value is persisted, so it must not change once shipped.
 */
@JvmInline
value class ServiceId(val value: String) {
    companion object {
        val Spotify = ServiceId("spotify")
        val Jellyfin = ServiceId("jellyfin")
        val Plex = ServiceId("plex")

        /**
         * The Subsonic family: one wire protocol, several server products. Each listed product
         * keeps its own id so its credentials, library identity and local queue/history stores
         * stay apart. [Subsonic] is the generic entry for any other Subsonic-compatible server.
         */
        val Navidrome = ServiceId("navidrome")
        val NextcloudMusic = ServiceId("nextcloud-music")
        val Subsonic = ServiceId("subsonic")

        /** A context-free fixture, used by previews and unit tests that have no signed-in service. */
        val Local = ServiceId("local")
    }
}

/** The kinds of object a [MediaKey] can point at. */
enum class MediaType { Track, Episode, Album, Playlist, Artist, Show }

/**
 * Stable identity across the queue, history, resume points, routes and persistence.
 *
 * A service id alone is not enough: two services can use the same opaque id, and a track and an
 * episode can share one too. [encode] is the on-disk and playback-source form; [decode] is its
 * inverse and returns null for anything this build cannot understand.
 */
data class MediaKey(
    val service: ServiceId,
    val type: MediaType,
    val id: String
) {
    fun encode(): String = "${service.value}:${type.name.lowercase()}:$id"

    companion object {
        fun decode(value: String): MediaKey? {
            val first = value.indexOf(':')
            if (first <= 0) return null
            val second = value.indexOf(':', first + 1)
            if (second <= first + 1) return null
            val service = ServiceId(value.substring(0, first))
            val type = MediaType.entries.firstOrNull { it.name.equals(value.substring(first + 1, second), ignoreCase = true) }
                ?: return null
            val id = value.substring(second + 1)
            if (id.isEmpty()) return null
            return MediaKey(service, type, id)
        }
    }
}

/** Anything a library page can contain. Playable items are [MediaItem]s; the rest are containers. */
sealed interface LibraryEntry {
    val key: MediaKey
}

/** Library sections a service may expose. The UI renders only the supported ones. */
enum class LibrarySection { Liked, Playlists, Albums, Artists, Shows }

/**
 * What the signed-in user may do to one playlist.
 *
 * Add and remove are separate because a service can allow one and refuse the other. A followed
 * Spotify playlist, for example, is read-only; a collaborative one accepts additions even though
 * the account does not own it. [unconfirmed] marks a service that cannot state permissions up
 * front (Jellyfin): the UI may offer the action, and the server's refusal is reported back.
 *
 * This is deliberately item-level. Whether the *service* can edit playlists at all is
 * [ServiceCapabilities.supportsPlaylistEdit]; whether a *particular* playlist can be changed is
 * this type. Deleting a playlist is not modelled: no provider has a delete transport yet, and
 * Spotify only has "remove from library" (unfollow), which is the Save toggle.
 */
data class PlaylistActions(
    val canAddItems: Boolean = false,
    val canRemoveItems: Boolean = false,
    val unconfirmed: Boolean = false
) {
    val canEditItems: Boolean get() = canAddItems || canRemoveItems

    companion object {
        /** The service says this playlist is read-only for the signed-in account. */
        val None = PlaylistActions()

        /** The service says the signed-in account may add and remove items. */
        val All = PlaylistActions(canAddItems = true, canRemoveItems = true)

        /** The service does not report permissions; offer editing and let the server decide. */
        val ServerDecides = PlaylistActions(canAddItems = true, canRemoveItems = true, unconfirmed = true)
    }
}

/**
 * The actions a playlist page may offer. A service that cannot edit playlists at all overrides a
 * playlist that reports itself editable, so the UI has one answer instead of two conditions.
 */
fun ServiceCapabilities.playlistActions(playlist: Playlist): PlaylistActions = if (supportsPlaylistEdit) playlist.actions else PlaylistActions.None

fun ServiceCapabilities.playlistActions(collection: Collection): PlaylistActions = if (!supportsPlaylistEdit) PlaylistActions.None else collection.playlistActions ?: PlaylistActions.None

/**
 * What a service can do. The UI asks these questions instead of asking which service is active.
 */
data class ServiceCapabilities(
    val librarySections: Set<LibrarySection>,
    val searchScopes: Set<SearchScope>,
    val supportsPodcasts: Boolean = false,
    val supportsRadio: Boolean = false,
    val supportsFollowArtist: Boolean = false,
    val supportsPlaylistEdit: Boolean = false,
    val supportsPlaylistCreate: Boolean = false,
    val supportsNormalization: Boolean = false,
    val supportsGapless: Boolean = false,
    val supportsQualitySelection: Boolean = false,
    val supportsProgressReporting: Boolean = false,
    val supportsScrobbling: Boolean = false
) {
    companion object {
        /** A service that supports every capability this build knows about. */
        val All = ServiceCapabilities(
            librarySections = LibrarySection.entries.toSet(),
            searchScopes = SearchScope.entries.toSet(),
            supportsPodcasts = true,
            supportsRadio = true,
            supportsFollowArtist = true,
            supportsPlaylistEdit = true,
            supportsNormalization = true,
            supportsGapless = true,
            supportsQualitySelection = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )

        /**
         * The Subsonic family — Navidrome, Nextcloud Music and Subsonic itself —
         * has albums, playlists, artists and similar-song radio. Podcast endpoints are 501 stubs
         * on Navidrome and absent elsewhere, loudness maps onto ReplayGain, and gapless is
         * engine-level, so no member of the family turns it on.
         */
        val Subsonic = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsRadio = true,
            supportsPlaylistEdit = true,
            supportsPlaylistCreate = true,
            supportsNormalization = true,
            supportsQualitySelection = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )

        /**
         * Jellyfin has albums, playlists, artists and instant mixes. Music libraries carry no
         * podcast type, the API has no loudness parameter, and gapless is engine-level — the same
         * reason the Subsonic family leaves it off. Follow maps onto the favorite-artist flag, because that
         * is the only artist-level "keep this" state the server has (decision D2).
         */
        val Jellyfin = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsRadio = true,
            supportsFollowArtist = true,
            supportsPlaylistEdit = true,
            supportsPlaylistCreate = true,
            supportsQualitySelection = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )

        /**
         * Plex exposes music libraries, playlists, artists and albums. It has no real radio
         * endpoint, no loudness parameter and no gapless handoff; playlist creation needs a
         * non-empty item URI, so the create form is left out. Liked/saved state is Plex's rating
         * convention (`userRating == 10`), and playback progress is reported through
         * `/:/timeline` and `/:/scrobble`.
         */
        val Plex = ServiceCapabilities(
            librarySections = setOf(
                LibrarySection.Liked,
                LibrarySection.Playlists,
                LibrarySection.Albums,
                LibrarySection.Artists
            ),
            searchScopes = setOf(
                SearchScope.Tracks,
                SearchScope.Artists,
                SearchScope.Albums,
                SearchScope.Playlists
            ),
            supportsPlaylistEdit = true,
            supportsProgressReporting = true,
            supportsScrobbling = true
        )
    }
}

/**
 * The library categories a service can fill. The row keeps the other buttons in place but mutes
 * them: a category the service cannot fill has nothing to show and no setting that would change
 * that, so it must not look selectable.
 */
val ServiceCapabilities.availableLibraryFilters: Set<LibraryFilter>
    get() = LibraryFilter.entries.filterTo(LinkedHashSet()) { it.section() in librarySections }

/** The search categories a service can answer. The scope list mutes the rest the same way. */
val ServiceCapabilities.availableSearchScopes: Set<SearchScope>
    get() = SearchScope.entries.filterTo(LinkedHashSet()) { it in searchScopes }
