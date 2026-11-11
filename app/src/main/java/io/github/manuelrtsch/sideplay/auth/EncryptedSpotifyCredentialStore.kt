package io.github.manuelrtsch.sideplay.auth

import android.content.Context
import hrln.interactive.sidekit.sdk.crypto.EncryptedFileStore
import org.json.JSONObject

/**
 * Token storage backed by the SDK's [EncryptedFileStore]: an atomic app-private file encrypted with a
 * non-exportable Android Keystore key. Tokens must never go into plain SharedPreferences.
 */
class EncryptedSpotifyCredentialStore(
    context: Context,
    // Legacy name kept so existing dev installs keep their Spotify session.
    name: String = "sidetracks_spotify"
) : SpotifyCredentialStore {
    private val settings = EncryptedFileStore(context.applicationContext, name)

    override fun read(): SpotifyCredentials? {
        val value = settings.read()
        val access = value.optString(KEY_ACCESS).takeIf { it.isNotEmpty() } ?: return null
        return SpotifyCredentials(
            accessToken = access,
            refreshToken = value.optString(KEY_REFRESH).takeIf { it.isNotEmpty() },
            expiresAtMs = value.optLong(KEY_EXPIRES_AT, 0L),
            scope = value.optString(KEY_SCOPE),
            tokenType = value.optString(KEY_TOKEN_TYPE, "Bearer")
        )
    }

    override fun save(credentials: SpotifyCredentials) {
        settings.write(
            JSONObject()
                .put(KEY_ACCESS, credentials.accessToken)
                .put(KEY_REFRESH, credentials.refreshToken ?: "")
                .put(KEY_EXPIRES_AT, credentials.expiresAtMs)
                .put(KEY_SCOPE, credentials.scope)
                .put(KEY_TOKEN_TYPE, credentials.tokenType)
        )
    }

    override fun clear() = settings.clear()

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_SCOPE = "scope"
        const val KEY_TOKEN_TYPE = "token_type"
    }
}
