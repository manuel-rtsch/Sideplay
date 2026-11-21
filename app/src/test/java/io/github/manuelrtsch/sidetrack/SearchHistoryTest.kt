package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rules that keep the search history short, ordered and free of rows that cannot search. */
class SearchHistoryTest {
    @Test
    fun `a new term becomes the newest entry`() {
        assertEquals(
            listOf("aurora", "night", "midnight"),
            updatedSearchHistory(listOf("night", "midnight"), "aurora")
        )
    }

    @Test
    fun `a repeated term moves to the top instead of appearing twice`() {
        assertEquals(
            listOf("midnight", "night", "aurora"),
            updatedSearchHistory(listOf("night", "midnight", "aurora"), "midnight")
        )
    }

    @Test
    fun `a repeated term wins its newest casing`() {
        assertEquals(
            listOf("Night", "aurora"),
            updatedSearchHistory(listOf("night", "aurora"), "Night")
        )
    }

    @Test
    fun `a term shorter than two letters is not history`() {
        assertEquals(listOf("night"), updatedSearchHistory(listOf("night"), " a "))
    }

    @Test
    fun `the history is capped at the newest entries`() {
        assertEquals(
            listOf("delta", "alpha"),
            updatedSearchHistory(listOf("alpha", "beta", "gamma"), "delta", limit = 2)
        )
    }

    @Test
    fun `removing a term leaves the rest in order`() {
        assertEquals(
            listOf("night", "midnight"),
            removedSearchHistory(listOf("night", "aurora", "midnight"), "aurora")
        )
    }
}
