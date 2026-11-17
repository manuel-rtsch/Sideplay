package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.EpisodeProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class PodcastTextTest {
    @Test
    fun stripsMarkupAndKeepsParagraphs() {
        val raw = "<p>First line.</p>\n\n<p>Second line &amp; a <b>bold</b> word.</p>"
        assertEquals("First line.\n\nSecond line & a bold word.", plainPodcastText(raw))
    }

    @Test
    fun wrapsAndIndentationCollapseIntoOneLine() {
        val raw = "<div>First  part\n   continues here.</div>"
        assertEquals("First part continues here.", plainPodcastText(raw))
    }

    @Test
    fun decodesCommonAndNumericEntities() {
        assertEquals("Tom & Jerry — “live”…", plainPodcastText("Tom &amp; Jerry &mdash; &ldquo;live&rdquo;&hellip;"))
        assertEquals("A + B", plainPodcastText("A &#43; B"))
    }

    @Test
    fun unknownEntityIsLeftAlone() {
        assertEquals("&unknown; stays", plainPodcastText("&unknown; stays"))
    }

    @Test
    fun blankInputBecomesEmpty() {
        assertEquals("", plainPodcastText("  \n\t "))
        assertEquals("", plainPodcastText("<p> </p>"))
    }

    @Test
    fun runsOfBlankLinesCollapseToOneBreak() {
        assertEquals("One.\n\nTwo.", plainPodcastText("One.<br><br><br><br>Two."))
    }

    @Test
    fun episodeMetaUsesRealNumberNotPosition() {
        val episode = Episode(
            id = "e1",
            title = "The Last Bus",
            show = "The Long Way Home",
            showId = "show",
            published = "2025-04-09",
            artRes = 0,
            durationMs = 2_529_000L,
            episodeNumber = 42,
            explicit = true
        )
        assertEquals("Ep. 42 · 9 Apr 2025 · 42:09 · E", episodeMetaLine(episode))
    }

    @Test
    fun episodeMetaShowsLocalProgressInsteadOfDuration() {
        val episode = Episode(
            id = "e1",
            title = "The Last Bus",
            show = "The Long Way Home",
            showId = "show",
            published = "2025-04-09",
            artRes = 0,
            durationMs = 2_529_000L,
            episodeNumber = 42
        )
        val progress = EpisodeProgress("e1", 1_200_000L, 2_529_000L, 0L, completed = false)

        assertEquals("Ep. 42 · 9 Apr 2025 · 23m left", episodeMetaLine(episode, progress))
        assertEquals("Ep. 42 · 9 Apr 2025 · Played", episodeMetaLine(episode, progress.copy(completed = true)))
    }
}
