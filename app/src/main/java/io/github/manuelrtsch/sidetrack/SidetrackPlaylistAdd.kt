package io.github.manuelrtsch.sidetrack

import java.util.LinkedHashMap

/**
 * The track behind the Add-to-playlist screen.
 *
 * The picker needs the whole [Track], not only its id: Spotify's writer appends by URI. A route
 * argument would put a complete track into the back stack's saved state, so the route carries a
 * short key and the object stays process-local. A restored stack without an entry shows a plain
 * "track is no longer available" hint instead of a stale target.
 */
internal object SidetrackPlaylistAdd {
    private const val CAPACITY = 8

    private var sequence = 0
    private val entries = object : LinkedHashMap<String, Track>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Track>?): Boolean = size > CAPACITY
    }

    fun remember(track: Track): String {
        val key = "p${++sequence}"
        entries[key] = track
        return key
    }

    fun get(key: String?): Track? = key?.let(entries::get)
}
