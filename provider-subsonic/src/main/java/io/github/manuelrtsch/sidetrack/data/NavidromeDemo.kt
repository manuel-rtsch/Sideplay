package io.github.manuelrtsch.sidetrack.data

/**
 * The public Navidrome demo server.
 *
 * [SubsonicFamily.Navidrome] offers these values as the sign-in form's placeholders and submits
 * them when the form is left untouched, so the app can be tried end-to-end without a server of
 * one's own. The server is shared and its library is not the user's, so the copy on the form says
 * so.
 */
object NavidromeDemo {
    const val SERVER_URL = "https://demo.navidrome.org"
    const val USERNAME = "demo"
    const val PASSWORD = "demo"
}
