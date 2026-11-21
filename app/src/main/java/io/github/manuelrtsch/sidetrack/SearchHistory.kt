package io.github.manuelrtsch.sidetrack

/** How many search terms the screen keeps. Long enough to cover a session, short enough to scan. */
const val SEARCH_HISTORY_LIMIT = 20

/**
 * Puts [query] at the head of [existing], newest first.
 *
 * A repeat is moved to the top rather than added twice, and the match ignores case so "Night"
 * after "night" does not become two rows that read the same. A term shorter than the two letters
 * a search needs is not history: it would be a row that cannot do anything.
 */
internal fun updatedSearchHistory(
    existing: List<String>,
    query: String,
    limit: Int = SEARCH_HISTORY_LIMIT
): List<String> {
    val term = query.trim()
    if (term.length < 2) return existing
    return (listOf(term) + existing.filterNot { it.equals(term, ignoreCase = true) })
        .take(limit.coerceAtLeast(1))
}

/** Drops [query] from a history; an absent term leaves the list unchanged. */
internal fun removedSearchHistory(existing: List<String>, query: String): List<String> = existing.filterNot { it == query }
