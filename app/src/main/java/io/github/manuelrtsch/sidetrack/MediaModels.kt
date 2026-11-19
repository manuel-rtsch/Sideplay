package io.github.manuelrtsch.sidetrack

/**
 * Stable identity of a music service. The value is persisted, so it must not change once shipped.
 */
@JvmInline
value class ServiceId(val value: String) {
    companion object {
        val Spotify = ServiceId("spotify")
        val Navidrome = ServiceId("navidrome")

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

        /** Navidrome has albums, playlists and artists, but no shows, radio or DSP settings. */
        val Navidrome = ServiceCapabilities(
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
