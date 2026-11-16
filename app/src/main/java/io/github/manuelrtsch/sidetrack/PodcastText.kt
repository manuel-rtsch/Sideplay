package io.github.manuelrtsch.sidetrack

import io.github.manuelrtsch.sidetrack.data.SpotifyEpisodeProgress
import io.github.manuelrtsch.sidetrack.playback.formatDuration

/** Block-level tags become a paragraph break so the reading screen can lay the text out lazily. */
private val podcastBlockTag = Regex("(?i)<(br|/p|/div|/li|/h[1-6])\\s*/?>")
private val podcastTag = Regex("<[^>]*>")
private val podcastParagraphBreak = Regex("\\n{3,}")
private val podcastLineBreak = Regex("(?<!\\n)\\n(?!\\n)")
private val podcastSpaces = Regex("[\\t\\u00A0 ]+")
private val podcastNumericEntity = Regex("&#(x?[0-9a-fA-F]+);")

/**
 * Spotify show notes arrive as HTML on both catalog paths. Compose renders text, not markup, so
 * strip the markup, decode the common entities and collapse each line into readable text while
 * keeping the paragraph breaks, which the reading screen turns into separate rows. Unknown entities
 * are left alone instead of being replaced with a misleading character.
 */
fun plainPodcastText(value: String): String {
    if (value.isBlank()) return ""
    var text = podcastBlockTag.replace(value, "\n\n")
    text = podcastTag.replace(text, "")
    text = podcastNumericEntity.replace(text) { match ->
        val raw = match.groupValues[1]
        val codePoint = if (raw.startsWith("x", ignoreCase = true)) {
            raw.drop(1).toIntOrNull(16)
        } else {
            raw.toIntOrNull()
        }
        codePoint?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
    }
    text = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&ndash;", "–")
        .replace("&mdash;", "—")
        .replace("&hellip;", "…")
        .replace("&rsquo;", "’")
        .replace("&lsquo;", "‘")
        .replace("&ldquo;", "“")
        .replace("&rdquo;", "”")
    text = text.replace("\r\n", "\n").replace('\r', '\n')
    // A newline in HTML source is wrapping, not a line break; only the breaks the block tags left
    // behind are paragraphs.
    text = podcastLineBreak.replace(text, " ")
    text = podcastSpaces.replace(text, " ")
    text = podcastParagraphBreak.replace(text, "\n\n")
    return text.lines().joinToString("\n") { it.trim() }.trim()
}

/**
 * The one-line identity of an episode: its real publishing number when Spotify provides it, the
 * publication date and the running time. Never the row's position, because the list is newest-first
 * and that would call the latest episode "1".
 */
fun episodeMetaLine(
    episode: SpotifyEpisode,
    progress: SpotifyEpisodeProgress? = null,
    compactProgress: Boolean = false
): String = buildList {
    episode.episodeNumber?.takeIf { it > 0 }?.let { add("Ep. $it") }
    formatPublishedDate(episode.published)
        .takeIf { it.isNotBlank() && it != "Podcast episode" }
        ?.let(::add)
    val progressLabel = progress?.let { episodeProgressLabel(it, compactProgress) }
    if (progressLabel != null) {
        add(progressLabel)
    } else {
        formatDuration(episode.durationMs).takeIf { it.isNotBlank() }?.let(::add)
    }
    if (episode.explicit) add("E")
}.joinToString(" · ")

/** "Played" or the rounded time left, once there is enough progress to be worth reporting. */
fun episodeProgressLabel(progress: SpotifyEpisodeProgress, compact: Boolean = false): String? {
    if (progress.completed) return "Played"
    if (progress.durationMs <= 0L || progress.positionMs < 30_000L) return null
    val remaining = (progress.durationMs - progress.positionMs).coerceAtLeast(0L)
    if (remaining == 0L) return "Played"
    val minutes = ((remaining + 59_999L) / 60_000L).coerceAtLeast(1L)
    return if (compact) "${minutes}m" else "${minutes}m left"
}
