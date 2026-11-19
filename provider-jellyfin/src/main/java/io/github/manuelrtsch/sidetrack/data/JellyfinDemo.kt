package io.github.manuelrtsch.sidetrack.data

/**
 * The public Jellyfin demo server.
 *
 * Sidetrack offers these values as the sign-in form's placeholders and submits them when the form
 * is left untouched, so the app can be tried end-to-end without a server of one's own. The demo's
 * account has no password, which is why Jellyfin sign-in treats it as optional; the address carries
 * no port and is reached over 443 through a reverse proxy, not on 8920.
 *
 * The server is shared and its library is not the user's, so the copy on the form says so.
 */
object JellyfinDemo {
    const val SERVER_URL = "https://demo.jellyfin.org/stable"
    const val USERNAME = "demo"
    const val PASSWORD = ""
}
