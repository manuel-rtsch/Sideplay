package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.collection
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
    fun jsonNullsAndBlanksAreNotRouteIds() {
        assertNull("null".mediaIdOrNull())
        assertNull("".mediaIdOrNull())
        assertNull("   ".mediaIdOrNull())
        assertNull(null.mediaIdOrNull())
        assertEquals("h33fR99rX82mBeIzNvbG0BN", "h33fR99rX82mBeIzNvbG0BN".mediaIdOrNull())
    }

    @Test
    fun jsonNullArtworkUrlsNeverBecomeRouteText() {
        val album = JSONObject("""{"id":"abc","title":"Album","artUrl":null,"items":[]}""")
        // org.json's trap: a JSON null arrives as the literal string "null".
        assertEquals("null", album.optString("artUrl"))
        assertNull(album.collection(CollectionType.Album)?.artUrl)

        val withArt = JSONObject(
            """{"id":"abc","title":"Album","artUrl":"https://example.test/a.jpg","items":[]}"""
        )
        assertEquals("https://example.test/a.jpg", withArt.collection(CollectionType.Album)?.artUrl)
    }

    @Test
    fun jsonNullCollectionIdsNeverBecomeRoutes() {
        val album = JSONObject("""{"id":"abc","title":"Album","artistId":null,"items":[]}""")
        // org.json's trap: a JSON null arrives as the literal string "null".
        assertEquals("null", album.optString("artistId"))
        assertNull(album.collection(CollectionType.Album)?.artistId)

        // A catalog entry whose own id is JSON null cannot be opened at all.
        val broken = JSONObject("""{"id":null,"title":"Album","items":[]}""")
        assertNull(broken.collection(CollectionType.Album))
    }
}
