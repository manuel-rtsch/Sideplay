package io.github.manuelrtsch.sidetrack.data

/**
 * Request headers the signed-in service needs on every artwork request.
 *
 * Artwork is drawn deep inside shared UI — rows, the now-playing backdrop, the media notification
 * — where no runtime is in scope, and the app runs one service at a time. The provider therefore
 * installs its headers once when it signs in and clears them on sign-out.
 *
 * Empty for providers whose image URLs carry their own credentials (Navidrome's Subsonic token,
 * Spotify's CDN). Jellyfin authenticates with a long-lived token sent as a header instead, and
 * this is what keeps that token out of the URL, the log and the artwork cache key (decision D10 in
 * `docs/jellyfin-integration.md`).
 */
object ArtworkAccess {
    @Volatile
    private var headers: Map<String, String> = emptyMap()

    fun install(value: Map<String, String>) {
        headers = value.toMap()
    }

    fun clear() {
        headers = emptyMap()
    }

    fun current(): Map<String, String> = headers
}
