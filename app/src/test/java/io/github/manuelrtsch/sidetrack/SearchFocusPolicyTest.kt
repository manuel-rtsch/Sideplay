package io.github.manuelrtsch.sidetrack

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the landing screen opens with an empty field and history below it, the keyboard would cover
 * the very rows it is offering; when a term is already there, the field is the next thing to use.
 */
class SearchFocusPolicyTest {
    @Test
    fun `an empty field on a fresh visit leaves the history visible`() {
        assertFalse(searchFieldAutoFocus(query = "", keypadTypes = false, dismissed = false))
    }

    @Test
    fun `a term already typed is worth refining`() {
        assertTrue(searchFieldAutoFocus(query = "bowie", keypadTypes = false, dismissed = false))
    }

    @Test
    fun `a keypad that types letters is the input, so it gets the field`() {
        assertTrue(searchFieldAutoFocus(query = "", keypadTypes = true, dismissed = false))
    }

    @Test
    fun `a field the user put away stays away for the visit`() {
        assertFalse(searchFieldAutoFocus(query = "bowie", keypadTypes = true, dismissed = true))
    }

    @Test
    fun `dismissed and empty is still down`() {
        assertFalse(searchFieldAutoFocus(query = "", keypadTypes = false, dismissed = true))
    }
}
