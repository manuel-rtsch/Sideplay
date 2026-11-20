package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.parseJellyfinId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how a Jellyfin id string becomes a [UUID].
 *
 * Jellyfin 12 answers `POST /Playlists` with `{"Id":"8aeabe4a8c99b7b52f0389af0c91dd55"}` — the bare
 * 32-character GUID, without the dashes `UUID.fromString` insists on. Parsing only the dashed form
 * failed *after* the server had created the playlist, so the create screen reported an error while
 * the playlist existed.
 */
class JellyfinIdParsingTest {

    @Test fun `a dashed id parses as written`() {
        assertEquals(
            UUID.fromString("8aeabe4a-8c99-b7b5-2f03-89af0c91dd55"),
            parseJellyfinId("8aeabe4a-8c99-b7b5-2f03-89af0c91dd55")
        )
    }

    @Test fun `the bare GUID playlist creation answers with parses to the same id`() {
        assertEquals(
            UUID.fromString("8aeabe4a-8c99-b7b5-2f03-89af0c91dd55"),
            parseJellyfinId("8aeabe4a8c99b7b52f0389af0c91dd55")
        )
    }

    @Test fun `an uppercase bare GUID parses`() {
        assertEquals(
            UUID.fromString("8aeabe4a-8c99-b7b5-2f03-89af0c91dd55"),
            parseJellyfinId("8AEABE4A8C99B7B52F0389AF0C91DD55")
        )
    }

    @Test fun `something that is not an id stays unreadable`() {
        assertNull(parseJellyfinId("not-an-id"))
        assertNull(parseJellyfinId(""))
        assertNull(parseJellyfinId("8aeabe4a8c99b7b52f0389af0c91dd5"))
    }
}
