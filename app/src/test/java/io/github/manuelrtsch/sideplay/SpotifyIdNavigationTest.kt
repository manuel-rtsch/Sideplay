package io.github.manuelrtsch.sideplay

import androidx.navigation.NavType
import io.github.manuelrtsch.sideplay.data.collection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Navigation's StringType turns the literal "null" into a real null, and a non-nullable route
 * argument that holds null makes `NavDestination.addInDefaultArgs` throw while the app is running:
 *
 *     IllegalArgumentException: Wrong argument type for 'id' in argument savedState. string expected.
 *
 * org.json's `optString` produces exactly that literal for Spotify payloads with `"id": null`, so
 * every id has to be normalised before it is used as a route argument.
 */
@RunWith(RobolectricTestRunner::class)
class SpotifyIdNavigationTest {

    @Test
    fun navigationParsesTheLiteralNullAsAbsent() {
        assertNull(NavType.StringType.parseValue("null"))
        assertEquals("h33fR99rX82mBeIzNvbG0BN", NavType.StringType.parseValue("h33fR99rX82mBeIzNvbG0BN"))
    }

    @Test
    fun jsonNullsAndBlanksAreNotRouteIds() {
        assertNull("null".spotifyIdOrNull())
        assertNull("".spotifyIdOrNull())
        assertNull("   ".spotifyIdOrNull())
        assertNull(null.spotifyIdOrNull())
        assertEquals("h33fR99rX82mBeIzNvbG0BN", "h33fR99rX82mBeIzNvbG0BN".spotifyIdOrNull())
    }

    @Test
    fun jsonNullCollectionIdsNeverBecomeRoutes() {
        val album = JSONObject("""{"id":"abc","title":"Album","artistId":null,"items":[]}""")
        // org.json's trap: a JSON null arrives as the literal string "null".
        assertEquals("null", album.optString("artistId"))
        assertNull(album.collection(SpotifyCollectionType.Album)?.artistId)

        // A catalog entry whose own id is JSON null cannot be opened at all.
        val broken = JSONObject("""{"id":null,"title":"Album","items":[]}""")
        assertNull(broken.collection(SpotifyCollectionType.Album))
    }
}
