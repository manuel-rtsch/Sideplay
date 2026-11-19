package io.github.manuelrtsch.sidetrack.auth

import android.content.Context
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.HttpNavidromeTransport
import io.github.manuelrtsch.sidetrack.data.NavidromeApi
import io.github.manuelrtsch.sidetrack.data.NavidromeCredentials
import io.github.manuelrtsch.sidetrack.data.NavidromeTransport
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.normalized
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/**
 * Navidrome needs the password to mint a fresh Subsonic token for every request, so it is kept in
 * the same encrypted, atomic store as the Spotify session. It never enters logs or the UI state.
 */
class NavidromeCredentialStore(
    context: Context,
    name: String = "sidetracks_navidrome",
    private val store: JsonStore = GuardedEncryptedStore(context, name)
) {
    fun read(): NavidromeCredentials? {
        val value = runCatching { store.read() }.getOrDefault(JSONObject())
        val server = value.optString(KEY_SERVER).takeIf { it.isNotEmpty() } ?: return null
        val username = value.optString(KEY_USERNAME).takeIf { it.isNotEmpty() } ?: return null
        val password = value.optString(KEY_PASSWORD).takeIf { it.isNotEmpty() } ?: return null
        return NavidromeCredentials(server, username, password)
    }

    fun save(credentials: NavidromeCredentials) {
        val value = JSONObject()
            .put(KEY_SERVER, credentials.serverUrl)
            .put(KEY_USERNAME, credentials.username)
            .put(KEY_PASSWORD, credentials.password)
        try {
            store.write(value)
        } catch (e: EncryptedStoreException) {
            throw CredentialPersistenceException(SAVE_FAILED, e)
        } catch (e: Exception) {
            throw CredentialPersistenceException(SAVE_FAILED, e)
        }
    }

    fun clear() {
        runCatching { store.clear() }
    }

    private companion object {
        const val KEY_SERVER = "server_url"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"

        const val SAVE_FAILED =
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time."
    }
}

/**
 * Server URL + username/password sign-in. The password is verified with a real `ping` before it is
 * persisted, so a typo lands on the form instead of becoming a stored session that cannot load.
 */
class NavidromeAuth(
    private val store: NavidromeCredentialStore,
    private val transport: NavidromeTransport? = null
) : ServiceAuth {
    /**
     * The credentials for this process. A device that cannot persist the store still keeps the
     * signed-in session until the app closes, matching the Spotify keystore-failure behaviour.
     */
    @Volatile
    private var current: NavidromeCredentials? = null

    fun hasCredentials(): Boolean = credentials() != null

    fun credentials(): NavidromeCredentials? = current ?: runCatching { store.read() }.getOrNull()?.also { current = it }

    fun api(): NavidromeApi? = credentials()?.let { NavidromeApi(it, transport ?: HttpNavidromeTransport()) }

    override suspend fun signIn(input: AuthInput): ServiceAccount {
        val password = input as? AuthInput.Password
            ?: throw ServiceException(ServiceFailure.NotConnected, "Navidrome needs a server, username and password")
        val credentials = NavidromeCredentials(password.serverUrl, password.username, password.password).normalized()
        if (credentials.username.isEmpty() || credentials.password.isEmpty()) {
            throw ServiceException(ServiceFailure.NotConnected, "Enter your Navidrome username and password")
        }
        NavidromeApi(credentials, transport ?: HttpNavidromeTransport()).ping()
        current = credentials
        store.save(credentials)
        return account(credentials)
    }

    override suspend fun restore(): ServiceAccount? = credentials()?.let { account(it) }

    override suspend fun signOut() = clear()

    /** Synchronous clear for the runtime's non-suspending sign-out path. */
    fun clear() {
        current = null
        store.clear()
    }

    private fun account(credentials: NavidromeCredentials) = ServiceAccount(
        service = ServiceId.Navidrome,
        userId = credentials.username,
        displayName = "Navidrome",
        serverUrl = credentials.serverUrl
    )
}
