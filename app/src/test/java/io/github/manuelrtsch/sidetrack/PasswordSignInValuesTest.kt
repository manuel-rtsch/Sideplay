package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.NavidromeDemo
import org.junit.Assert.assertEquals
import org.junit.Test

class PasswordSignInValuesTest {
    private val demo = PasswordSignInDefaults(
        NavidromeDemo.SERVER_URL,
        NavidromeDemo.USERNAME,
        NavidromeDemo.PASSWORD
    )

    @Test fun untouchedFormUsesTheDemo() {
        assertEquals(
            Triple(NavidromeDemo.SERVER_URL, NavidromeDemo.USERNAME, NavidromeDemo.PASSWORD),
            passwordSignInValues("", "", "", demo)
        )
    }

    @Test fun whitespaceOnlyFormCountsAsUntouched() {
        assertEquals(
            Triple(NavidromeDemo.SERVER_URL, NavidromeDemo.USERNAME, NavidromeDemo.PASSWORD),
            passwordSignInValues("   ", "\t", " ", demo)
        )
    }

    @Test fun partlyTypedFormIsNeverMixedWithTheDemo() {
        assertEquals(
            Triple("music.example.org", "", ""),
            passwordSignInValues("music.example.org", "", "", demo)
        )
        assertEquals(
            Triple("", "me", ""),
            passwordSignInValues("", "me", "", demo)
        )
    }

    @Test fun typedFieldsWinEvenWhenTheyMatchTheDemo() {
        assertEquals(
            Triple("https://music.example.org", "listener", "secret"),
            passwordSignInValues("https://music.example.org", "listener", "secret", demo)
        )
    }

    @Test fun withoutDefaultsTheFormIsSubmittedAsTyped() {
        assertEquals(
            Triple("", "", ""),
            passwordSignInValues("", "", "", null)
        )
    }
}
