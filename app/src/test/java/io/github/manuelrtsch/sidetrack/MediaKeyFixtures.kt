package io.github.manuelrtsch.sidetrack

/** Test-only shorthand for a Spotify key; the production model has no provider-specific factory. */
fun MediaKey.Companion.spotify(type: MediaType, id: String): MediaKey = MediaKey(ServiceId.Spotify, type, id)
