package io.github.manuelrtsch.sidetrack.auth

import android.content.Context
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/**
 * Token storage backed by the SDK's encrypted file store: an atomic app-private file encrypted with
 * a non-exportable Android Keystore key. Tokens must never go into plain SharedPreferences.
 *
 * Both calls are total. [read] never throws — the store is read while the UI composes, and an
 * invalidated keystore key (ROM flash, GSI install, device restore) must not become a launch
 * crash. [save] reports an unreadable device as [CredentialPersistenceException], which the
 * sign-in flow turns into "signed in, but you will have to sign in again next time".
 */
class EncryptedSpotifyCredentialStore(
    context: Context,
    // Legacy name kept so existing dev installs keep their Spotify session.
    name: String = "sidetracks_spotify",
    private val store: JsonStore = GuardedEncryptedStore(context, name)
) : SpotifyCredentialStore {

    override fun read(): SpotifyCredentials? {
        val value = runCatching { store.read() }.getOrDefault(JSONObject())
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
        val value = JSONObject()
            .put(KEY_ACCESS, credentials.accessToken)
            .put(KEY_REFRESH, credentials.refreshToken ?: "")
            .put(KEY_EXPIRES_AT, credentials.expiresAtMs)
            .put(KEY_SCOPE, credentials.scope)
            .put(KEY_TOKEN_TYPE, credentials.tokenType)
        try {
            store.write(value)
        } catch (e: EncryptedStoreException) {
            throw CredentialPersistenceException(SAVE_FAILED, e)
        } catch (e: Exception) {
            throw CredentialPersistenceException(SAVE_FAILED, e)
        }
    }

    override fun clear() {
        runCatching { store.clear() }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_ms"
        const val KEY_SCOPE = "scope"
        const val KEY_TOKEN_TYPE = "token_type"

        const val SAVE_FAILED =
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time."
    }
}
