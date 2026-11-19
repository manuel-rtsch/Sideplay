package io.github.manuelrtsch.sidetrack.auth

import android.content.Context
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.jellyfin.BuildConfig
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.io.IOException
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.SecureConnectionException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.json.JSONObject

/** A signed-in Jellyfin session: the token, and who it belongs to. */
data class JellyfinCredentials(
    val serverUrl: String,
    val accessToken: String,
    val authorizationHeader: String,
    val userId: String,
    val userName: String
) {
    /** The header every request for this account carries: audio, artwork and the API client. */
    fun headers(): Map<String, String> = mapOf(HEADER_AUTHORIZATION to authorizationHeader)

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
    }
}

/**
 * Trims a typed server address into the base URL the SDK expects.
 *
 * Deliberately plain (decision D9): the scheme is added when missing, a trailing slash is dropped,
 * and a missing port becomes Jellyfin's default. This is what makes a bare `192.168.1.20` or
 * `music.example.org` reach a default install, which listens on 8096 and nothing else.
 *
 * Only `http` gets the default filled in. A `https://` address keeps its implicit 443: 8920 is
 * Jellyfin's HTTPS port only when the certificate is configured inside Jellyfin itself, while a
 * proxied server — the public demo included — answers on 443, so appending 8920 would turn a
 * working address into a dead one.
 */
fun normalizeJellyfinServerUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val parsed = runCatching { URL(withScheme) }.getOrNull()
    if (parsed == null || parsed.host.isNullOrEmpty() || (parsed.protocol != "http" && parsed.protocol != "https")) {
        throw ServiceException(ServiceFailure.NotConnected, "Enter the Jellyfin server address")
    }
    if (parsed.protocol != "http" || parsed.port != -1) return withScheme
    // Insert the port at the end of the authority so a typed base path survives: a proxied server
    // can sit under one (the demo's `/stable`), and that path belongs after the port.
    val authorityStart = withScheme.indexOf("://") + 3
    val authorityEnd = withScheme.indexOf('/', authorityStart).let { if (it == -1) withScheme.length else it }
    return withScheme.substring(0, authorityEnd) + ":" + DEFAULT_HTTP_PORT + withScheme.substring(authorityEnd)
}

/** The port a default Jellyfin install listens on; see [normalizeJellyfinServerUrl]. */
private const val DEFAULT_HTTP_PORT = 8096

/**
 * Jellyfin signs in with a password once and keeps the resulting token, unlike Navidrome, which
 * needs the password for every request. So the store holds the token — never the password — in
 * the same encrypted, write-through store as the other providers' sessions.
 */
class JellyfinCredentialStore(private val store: JsonStore) {
    /** Production wiring: the encrypted, write-through store under a provider-specific name. */
    constructor(context: Context, name: String = "sidetracks_jellyfin") :
        this(GuardedEncryptedStore(context, name))
    fun read(): JellyfinCredentials? {
        val value = runCatching { store.read() }.getOrDefault(JSONObject())
        val server = value.optString(KEY_SERVER).takeIf { it.isNotEmpty() } ?: return null
        val token = value.optString(KEY_TOKEN).takeIf { it.isNotEmpty() } ?: return null
        val header = value.optString(KEY_HEADER).takeIf { it.isNotEmpty() } ?: return null
        val userId = value.optString(KEY_USER_ID).takeIf { it.isNotEmpty() } ?: return null
        val userName = value.optString(KEY_USER_NAME).takeIf { it.isNotEmpty() } ?: return null
        return JellyfinCredentials(server, token, header, userId, userName)
    }

    fun save(credentials: JellyfinCredentials) {
        val value = JSONObject()
            .put(KEY_SERVER, credentials.serverUrl)
            .put(KEY_TOKEN, credentials.accessToken)
            .put(KEY_HEADER, credentials.authorizationHeader)
            .put(KEY_USER_ID, credentials.userId)
            .put(KEY_USER_NAME, credentials.userName)
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
        const val KEY_TOKEN = "access_token"
        const val KEY_HEADER = "authorization_header"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_NAME = "user_name"

        const val SAVE_FAILED =
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time."
    }
}

/**
 * The one call the adapter needs from the SDK during sign-in. Keeping it an interface means the
 * auth tests need no server, no `Context` and no Robolectric.
 */
fun interface JellyfinAuthenticator {
    /** Authenticates and returns the session, or throws [ServiceException]. */
    suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinCredentials
}

/**
 * The SDK-backed authenticator. One `createJellyfin` per instance; an `ApiClient` per server.
 *
 * The authorization header is built by the SDK's own [AuthorizationHeaderBuilder] so the device id
 * (derived from `Settings.Secure.ANDROID_ID`) matches the one the API client sends. Jellyfin keys a
 * session on it, so a hand-rolled header would create a second session per request.
 */
class SdkJellyfinAuthenticator(private val context: Context) : JellyfinAuthenticator {
    /**
     * The SDK's suspend API is generated for multiplatform use and runs OkHttp synchronously on the
     * calling thread, so it has to be moved off the main one: the sign-in screen calls this from
     * `viewModelScope`, and Android throws [android.os.NetworkOnMainThreadException] once the
     * response body is read.
     */
    override suspend fun signIn(serverUrl: String, username: String, password: String): JellyfinCredentials =
        withContext(Dispatchers.IO) { signInBlocking(serverUrl, username, password) }

    private suspend fun signInBlocking(serverUrl: String, username: String, password: String): JellyfinCredentials {
        val jellyfin = createJellyfin {
            context = this@SdkJellyfinAuthenticator.context.applicationContext
            clientInfo = ClientInfo(name = CLIENT_NAME, version = BuildConfig.VERSION_NAME)
        }
        val api = try {
            jellyfin.createApi(baseUrl = serverUrl)
        } catch (error: Throwable) {
            throw jellyfinFailure(error)
        }
        val result = try {
            api.authenticationApi.authenticateUserByName(username, password).content
        } catch (error: Throwable) {
            throw jellyfinFailure(error)
        }
        val token = result.accessToken
            ?: throw ServiceException(ServiceFailure.AuthenticationExpired, "Jellyfin did not return a session")
        val user = result.user
        // Positional on purpose: the SDK names every argument "client…"/"device…" and can rename
        // them between minor releases without this line noticing.
        val header = AuthorizationHeaderBuilder.buildHeader(
            api.clientInfo.name,
            api.clientInfo.version,
            api.deviceInfo.name,
            api.deviceInfo.id,
            token
        )
        return JellyfinCredentials(
            serverUrl = serverUrl,
            accessToken = token,
            authorizationHeader = header,
            userId = user?.id?.toString().orEmpty(),
            userName = user?.name ?: username
        )
    }

    private companion object {
        const val CLIENT_NAME = "Sidetrack"
    }
}

/** Password sign-in against a Jellyfin server, with the token kept instead of the password. */
class JellyfinAuth(
    private val store: JellyfinCredentialStore,
    private val authenticator: JellyfinAuthenticator
) : ServiceAuth {
    /**
     * The session for this process. A device that cannot persist the store still stays signed in
     * until the app closes, the same behaviour Navidrome and Spotify have.
     */
    @Volatile
    private var current: JellyfinCredentials? = null

    fun hasCredentials(): Boolean = credentials() != null

    fun credentials(): JellyfinCredentials? = current ?: runCatching { store.read() }.getOrNull()?.also { current = it }

    /** Installs the account's header for artwork if a session is already stored. */
    fun installArtworkHeaders() {
        credentials()?.let { ArtworkAccess.install(it.headers()) }
    }

    override suspend fun signIn(input: AuthInput): ServiceAccount {
        val password = input as? AuthInput.Password
            ?: throw ServiceException(ServiceFailure.NotConnected, "Jellyfin needs a server, username and password")
        val serverUrl = normalizeJellyfinServerUrl(password.serverUrl)
        // The password is optional on purpose: a public demo server can have accounts without one,
        // and Jellyfin answers an empty password the same way it answers any other.
        if (password.username.isBlank()) {
            throw ServiceException(ServiceFailure.NotConnected, "Enter your Jellyfin username")
        }
        val credentials = authenticator.signIn(serverUrl, password.username.trim(), password.password)
        current = credentials
        store.save(credentials)
        ArtworkAccess.install(credentials.headers())
        return account(credentials)
    }

    override suspend fun restore(): ServiceAccount? = credentials()?.let {
        ArtworkAccess.install(it.headers())
        account(it)
    }

    override suspend fun signOut() = clear()

    /** Synchronous clear for the runtime's non-suspending sign-out path. */
    fun clear() {
        current = null
        store.clear()
        ArtworkAccess.clear()
    }

    private fun account(credentials: JellyfinCredentials) = ServiceAccount(
        service = ServiceId.Jellyfin,
        userId = credentials.userId.ifEmpty { credentials.userName },
        displayName = credentials.userName,
        serverUrl = credentials.serverUrl
    )
}

/**
 * Maps an SDK failure onto the neutral category the UI explains. Kept out of the database and
 * store code so the mapping can be unit-tested without a server (decision D14: the dev container
 * is where the real responses get checked).
 */
internal fun jellyfinFailure(error: Throwable): ServiceException = when (error) {
    is ServiceException -> error

    is InvalidStatusException -> when (error.status) {
        401, 403 -> ServiceException(
            ServiceFailure.AuthenticationExpired,
            "Jellyfin rejected that username or password"
        )

        404 -> ServiceException(ServiceFailure.NotConnected, "No Jellyfin server answered at that address")

        in 500..599 -> ServiceException(ServiceFailure.Unavailable, "The Jellyfin server is not available right now")

        else -> ServiceException(ServiceFailure.Unavailable, "Jellyfin sign-in failed (HTTP ${error.status})")
    }

    is TimeoutException -> ServiceException(ServiceFailure.Network, "The Jellyfin server did not answer in time")

    is SecureConnectionException -> ServiceException(
        ServiceFailure.Network,
        "The Jellyfin server's certificate could not be verified"
    )

    is ApiClientException -> ServiceException(
        ServiceFailure.Unavailable,
        "Could not talk to that Jellyfin server. Sidetrack needs Jellyfin 12.0 or newer."
    )

    is IOException -> ServiceException(ServiceFailure.Network, "Could not reach the Jellyfin server")

    else -> ServiceException(ServiceFailure.Unknown, "Jellyfin sign-in failed", error)
}
