package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.JellyfinKind
import io.github.manuelrtsch.sidetrack.data.PlaylistEntry
import io.github.manuelrtsch.sidetrack.data.jellyfinBitrateKbps
import io.github.manuelrtsch.sidetrack.data.jellyfinMusicProfile
import io.github.manuelrtsch.sidetrack.data.resolvePlaylistEntry
import io.github.manuelrtsch.sidetrack.data.toHint
import io.github.manuelrtsch.sidetrack.data.toItem
import kotlinx.coroutines.test.runTest
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.SearchHint
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the SDK-to-neutral mapping and the music device profile. No server and no `Context`: the
 * generated models are plain data classes, so a hand-written DTO is enough.
 */
class JellyfinApiTest {
    @Test fun `a track carries artist ids album ids artwork and duration in milliseconds`() {
        val item = BaseItemDto(
            id = uuid("11111111-1111-1111-1111-111111111111"),
            type = BaseItemKind.AUDIO,
            name = "Night Signals",
            albumId = uuid("22222222-2222-2222-2222-222222222222"),
            album = "Signals",
            albumArtist = "Aurora Vale",
            albumArtists = listOf(NameGuidPair(name = "Aurora Vale", id = uuid("33333333-3333-3333-3333-333333333333"))),
            artistItems = listOf(NameGuidPair(name = "Guest", id = uuid("44444444-4444-4444-4444-444444444444"))),
            runTimeTicks = 213_400_000L,
            productionYear = 2024,
            imageTags = mapOf(ImageType.PRIMARY to "tag-primary"),
            userData = UserItemDataDto(
                playbackPositionTicks = 12_000_000L,
                playCount = 3,
                isFavorite = true,
                played = true,
                key = "key",
                itemId = uuid("11111111-1111-1111-1111-111111111111")
            )
        ).toItem()

        assertEquals(JellyfinKind.Audio, item.kind)
        assertEquals("Night Signals", item.name)
        assertEquals("Aurora Vale", item.albumArtist)
        assertEquals("33333333-3333-3333-3333-333333333333", item.albumArtistId)
        assertEquals(listOf("44444444-4444-4444-4444-444444444444"), item.artistIds)
        assertEquals("22222222-2222-2222-2222-222222222222", item.albumId)
        assertEquals("tag-primary", item.imageTag)
        assertEquals(21_340L, item.runTimeTicks!! / 10_000L)
        assertEquals(1_200L, item.playbackPositionTicks!! / 10_000L)
        assertEquals(true, item.isFavorite)
    }

    @Test fun `a search hint keeps the artwork item id it points at`() {
        val hint = SearchHint(
            itemId = uuid("55555555-5555-5555-5555-555555555555"),
            id = uuid("55555555-5555-5555-5555-555555555555"),
            name = "Night Signals",
            type = BaseItemKind.AUDIO,
            albumId = uuid("22222222-2222-2222-2222-222222222222"),
            albumArtist = "Aurora Vale",
            artists = listOf("Aurora Vale"),
            runTimeTicks = 180_000_000L,
            primaryImageTag = "hint-tag",
            thumbImageItemId = "66666666-6666-6666-6666-666666666666"
        ).toHint()

        assertEquals(JellyfinKind.Audio, hint.kind)
        assertEquals("hint-tag", hint.imageTag)
        assertEquals("66666666-6666-6666-6666-666666666666", hint.imageItemId)
        assertEquals("22222222-2222-2222-2222-222222222222", hint.albumId)
    }

    @Test fun `the music profile advertises direct play and states a bitrate ceiling`() {
        val capped = jellyfinMusicProfile(96_000)
        assertEquals(96_000, capped.maxStreamingBitrate)
        assertEquals(96_000, capped.maxStaticMusicBitrate)
        assertEquals(true, capped.directPlayProfiles.any { it.container == "flac" })
        assertEquals(true, capped.directPlayProfiles.any { it.container == "mp3" })
        assertEquals("mp3", capped.transcodingProfiles.single().container)

        // "High" leaves the cap to the server, but the profile still states a ceiling (D4).
        val uncapped = jellyfinMusicProfile(null)
        assertEquals(1_411_200, uncapped.maxStreamingBitrate)
        assertNull(uncapped.musicStreamingTranscodingBitrate)
    }

    @Test fun `quality maps the way Navidrome's does`() {
        assertEquals(96, AudioQuality.Normal.jellyfinBitrateKbps())
        assertNull(AudioQuality.High.jellyfinBitrateKbps())
        assertEquals(320, AudioQuality.VeryHigh.jellyfinBitrateKbps())
    }

    /**
     * The removal scan used to stop after 500 entries and then remove by the *media item id*, which
     * removes nothing — or, on a collision, the wrong entry. A playlist longer than one page could
     * not be edited from its tail at all. This walks 1,200 entries with two pages of 200 in front.
     */
    @Test fun `a playlist entry past the first pages is still found`() = runTest {
        val entries = List(1_200) { PlaylistEntry(entryId = "entry-$it", itemId = "item-$it") }
        val requested = mutableListOf<Int>()

        val resolved = resolvePlaylistEntry(itemId = "item-1150", index = null, pageSize = 200) { start, limit ->
            requested += start
            entries.drop(start).take(limit)
        }

        assertEquals("entry-1150", resolved)
        // Six pages to reach it, then one empty page: a page that is exactly full cannot be told
        // from the last one, so the scan has to ask once more. Only an unnamed removal gets there —
        // a named row returns from the page that holds it.
        assertEquals(listOf(0, 200, 400, 600, 800, 1000, 1200), requested)
    }

    @Test fun `the row the caller named decides which duplicate is removed`() = runTest {
        val entries = listOf(
            PlaylistEntry("entry-a1", "item-a"),
            PlaylistEntry("entry-b", "item-b"),
            PlaylistEntry("entry-a2", "item-a")
        )

        assertEquals("entry-a1", resolvePlaylistEntry("item-a", index = 0, pageSize = 200) { _, _ -> entries })
        assertEquals("entry-a2", resolvePlaylistEntry("item-a", index = 2, pageSize = 200) { _, _ -> entries })
    }

    @Test fun `without an index the last match wins, because an append leaves its copy at the end`() = runTest {
        val entries = listOf(
            PlaylistEntry("entry-a1", "item-a"),
            PlaylistEntry("entry-b", "item-b"),
            PlaylistEntry("entry-a2", "item-a")
        )

        assertEquals("entry-a2", resolvePlaylistEntry("item-a", index = null, pageSize = 200) { _, _ -> entries })
    }

    @Test fun `a stale index is not trusted, and a missing item resolves to nothing`() = runTest {
        val entries = listOf(
            PlaylistEntry("entry-a1", "item-a"),
            PlaylistEntry("entry-b", "item-b")
        )

        // index 1 now holds item-b, so the named row no longer describes item-a.
        assertEquals("entry-a1", resolvePlaylistEntry("item-a", index = 1, pageSize = 200) { _, _ -> entries })
        assertNull(resolvePlaylistEntry("item-gone", index = null, pageSize = 200) { _, _ -> entries })
    }

    @Test fun `an unknown item kind is not mistaken for a music type`() {
        val item = BaseItemDto(
            id = uuid("77777777-7777-7777-7777-777777777777"),
            type = BaseItemKind.MOVIE,
            name = "A film"
        ).toItem()

        assertEquals(JellyfinKind.Other, item.kind)
        assertNotNull(item.id)
    }

    private fun uuid(value: String): UUID = UUID.fromString(value)
}
