package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.stringOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlexJsonTest {
    @Test fun `explicit json null is not the string null`() {
        val json = JSONObject().put("authToken", JSONObject.NULL)
        assertNull(json.stringOrNull("authToken"))
    }

    @Test fun `a missing key is null`() {
        assertNull(JSONObject().stringOrNull("authToken"))
    }

    @Test fun `a real value survives`() {
        assertEquals("token-1", JSONObject().put("authToken", "token-1").stringOrNull("authToken"))
    }
}
