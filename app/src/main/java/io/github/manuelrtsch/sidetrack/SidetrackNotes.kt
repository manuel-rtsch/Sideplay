package io.github.manuelrtsch.sidetrack

import java.util.LinkedHashMap

/** One notes screen: the heading, the plain-text body and the action-bar label. */
internal data class SidetrackNote(
    val title: String,
    val body: String,
    val label: String
)

/**
 * The text behind the notes reading screen.
 *
 * Show and episode notes can run to several kilobytes, and a navigation route keeps its arguments
 * in the back stack, which Android serialises into the saved instance state. Handing the body to
 * the route would put that text into a Bundle on every save; the route carries a short key
 * instead. The text is process-local and bounded, so a restored stack without a matching entry
 * shows a plain "no longer available" hint rather than a stale body.
 */
internal object SidetrackNotes {
    private const val CAPACITY = 8

    private var sequence = 0
    private val entries = object : LinkedHashMap<String, SidetrackNote>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SidetrackNote>?): Boolean = size > CAPACITY
    }

    fun remember(title: String, body: String, label: String): String {
        val key = "n${++sequence}"
        entries[key] = SidetrackNote(title, body, label)
        return key
    }

    fun get(key: String?): SidetrackNote? = key?.let(entries::get)
}
