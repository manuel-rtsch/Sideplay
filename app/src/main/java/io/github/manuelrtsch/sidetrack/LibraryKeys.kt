package io.github.manuelrtsch.sidetrack

// The small identity helpers shared by every library-shaped list: how a row is labelled for the
// lazy list and how a mixed item is classified. Kept away from the screens so the same keys are
// used by the library, its collections and the artist page.

internal fun mediaContentType(item: Any): String = when (item) {
    is Track -> "track"
    is Episode -> "episode"
    else -> "media"
}

internal fun libraryKey(item: Any): String = when (item) {
    is Track -> "t${item.id}"
    is Playlist -> "p${item.id}"
    is Album -> "a${item.id}"
    is Artist -> "r${item.id}"
    is Show -> "s${item.id}"
    else -> item.toString()
}
