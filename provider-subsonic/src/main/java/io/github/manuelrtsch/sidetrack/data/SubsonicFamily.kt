package io.github.manuelrtsch.sidetrack.data

import io.github.manuelrtsch.sidetrack.ServiceId

/**
 * The server products Sidetrack lists separately but serves with one Subsonic adapter.
 *
 * Every entry speaks the same Subsonic/OpenSubsonic API at `/rest`, so the app can name the product
 * a user actually runs — Navidrome, Nextcloud's Music app, or Subsonic itself — without a
 * per-product client. [Subsonic] is also the answer for every other server in this family: the
 * entry is a label, not a filter, so picking it connects all the same. Listing entries apart
 * matters because the id decides which credentials and local queue/history stores a session uses:
 * signing into two servers keeps two libraries.
 *
 * [passwordDefaults] is the public demo an entry offers, if any. The demo is a Navidrome server,
 * so only that entry pre-fills it; the other entries leave the form literal.
 */
enum class SubsonicFamily(
    val id: ServiceId,
    val displayName: String,
    val passwordDefaults: PasswordDefaults? = null
) {
    Navidrome(
        id = ServiceId.Navidrome,
        displayName = "Navidrome",
        passwordDefaults = PasswordDefaults(
            serverUrl = NavidromeDemo.SERVER_URL,
            username = NavidromeDemo.USERNAME,
            password = NavidromeDemo.PASSWORD
        )
    ),
    NextcloudMusic(ServiceId.NextcloudMusic, "Nextcloud Music"),
    Subsonic(ServiceId.Subsonic, "Subsonic");

    /**
     * The encrypted credential store name. Stable per entry and unchanged for Navidrome, so an
     * install that signed in before the family existed still finds its session.
     */
    val credentialStoreName: String get() = "sidetracks_${id.value}"

    /** The playback-settings store name, derived the same way as [credentialStoreName]. */
    val settingsStoreName: String get() = "sidetracks_settings_${id.value}"

    /** One of this entry's local queue/history/resume files. */
    fun localStoreName(kind: String): String = "sidetracks-${id.value}-$kind.bin"

    companion object {
        /** The entry for [id], or null when this build does not list that service. */
        fun of(id: ServiceId): SubsonicFamily? = entries.firstOrNull { it.id == id }
    }
}
