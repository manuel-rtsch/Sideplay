package io.github.manuelrtsch.sidetrack.auth

import android.content.Context
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.HttpSubsonicTransport
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.SubsonicApi
import io.github.manuelrtsch.sidetrack.data.SubsonicCredentials
import io.github.manuelrtsch.sidetrack.data.SubsonicFamily
import io.github.manuelrtsch.sidetrack.data.SubsonicTransport
import io.github.manuelrtsch.sidetrack.data.normalized
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import org.json.JSONObject

/**
 * Every Subsonic-family server needs the password to mint a fresh token for each request, so it is
 * kept in the same encrypted, atomic store as the Spotify session. It never enters logs or the UI
 * state. The store name comes from the family entry, so two servers keep two sessions.
 */
class SubsonicCredentialStore(
    context: Context,
    name: String = SubsonicFamily.Navidrome.credentialStoreName,
    private val store: JsonStore = GuardedEncryptedStore(context, name)
) {
    fun read(): SubsonicCredentials? {
        val value = runCatching { store.read() }.getOrDefault(JSONObject())
        val server = value.optString(KEY_SERVER).takeIf { it.isNotEmpty() } ?: return null
        val username = value.optString(KEY_USERNAME).takeIf { it.isNotEmpty() } ?: return null
        val password = value.optString(KEY_PASSWORD).takeIf { it.isNotEmpty() } ?: return null
        return SubsonicCredentials(server, username, password)
    }

    fun save(credentials: SubsonicCredentials) {
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
 * Server URL + username/password sign-in for one family member. The password is verified with a
 * real `ping` before it is persisted, so a typo lands on the form instead of becoming a stored
 * session that cannot load. Any family entry answers any Subsonic server: the entry names the
 * product on the picker, it does not restrict what the credentials may connect to.
 */
class SubsonicAuth(
    private val store: SubsonicCredentialStore,
    private val family: SubsonicFamily = SubsonicFamily.Navidrome,
    private val transport: SubsonicTransport? = null
) : ServiceAuth {
    /**
     * The credentials for this process. A device that cannot persist the store still keeps the
     * signed-in session until the app closes, matching the Spotify keystore-failure behaviour.
     */
    @Volatile
    private var current: SubsonicCredentials? = null

    fun hasCredentials(): Boolean = credentials() != null

    fun credentials(): SubsonicCredentials? = current ?: runCatching { store.read() }.getOrNull()?.also { current = it }

    fun api(): SubsonicApi? = credentials()?.let {
        SubsonicApi(it, family, transport ?: HttpSubsonicTransport())
    }

    override suspend fun signIn(input: AuthInput): ServiceAccount {
        val password = input as? AuthInput.Password
            ?: throw ServiceException(ServiceFailure.NotConnected, "${family.displayName} needs a server, username and password")
        val credentials = SubsonicCredentials(password.serverUrl, password.username, password.password).normalized()
        if (credentials.username.isEmpty() || credentials.password.isEmpty()) {
            throw ServiceException(ServiceFailure.NotConnected, "Enter your ${family.displayName} username and password")
        }
        SubsonicApi(credentials, family, transport ?: HttpSubsonicTransport()).ping()
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

    private fun account(credentials: SubsonicCredentials) = ServiceAccount(
        service = family.id,
        userId = credentials.username,
        displayName = family.displayName,
        serverUrl = credentials.serverUrl
    )
}
