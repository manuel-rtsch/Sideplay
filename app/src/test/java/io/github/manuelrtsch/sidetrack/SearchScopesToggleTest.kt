package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Test

/** Search always keeps at least one category selected. */
class SearchScopesToggleTest {
    @Test
    fun `turning off the last category is refused`() {
        val scopes = setOf(SearchScope.Shows)

        assertEquals(scopes, toggledSearchScopes(scopes, SearchScope.Shows))
    }

    @Test
    fun `categories can be turned off while another one remains`() {
        val scopes = SearchScope.entries.toSet()

        assertEquals(scopes - SearchScope.Shows, toggledSearchScopes(scopes, SearchScope.Shows))
    }

    @Test
    fun `a turned-off category can be turned back on`() {
        val scopes = setOf(SearchScope.Tracks)

        assertEquals(
            setOf(SearchScope.Tracks, SearchScope.Albums),
            toggledSearchScopes(scopes, SearchScope.Albums)
        )
    }
}
