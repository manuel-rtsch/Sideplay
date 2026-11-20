package io.github.manuelrtsch.sidetrack

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** A non-focusable label that makes the tail and shuffle state visible at a glance. */
@Composable
internal fun QueueSectionHeader(count: Int, shuffled: Boolean, modifier: Modifier = Modifier) {
    val label = buildString {
        append("Up next · ")
        append(count)
        append(if (count == 1) " track" else " tracks")
        if (shuffled && count > 1) append(" · Shuffled")
    }
    // Same heading as Home, Library, search and album/playlist sections: uppercase, small, muted.
    SectionLabel(label, modifier.fillMaxWidth())
}

/** Stable keys for the upcoming list: track identity plus an occurrence rank for duplicates. */
internal fun entryKeys(items: List<MediaItem>): List<String> {
    val seen = HashMap<String, Int>(items.size)
    return items.map { item ->
        val rank = seen[item.id] ?: 0
        seen[item.id] = rank + 1
        "u${item.id}#$rank"
    }
}

/** What the bar's Undo will put back. */
internal sealed interface QueueUndo {
    class Cleared(val items: List<MediaItem>) : QueueUndo

    class Removed(val index: Int, val item: MediaItem) : QueueUndo
}
