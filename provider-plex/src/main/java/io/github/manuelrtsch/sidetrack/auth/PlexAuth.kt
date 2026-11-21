package io.github.manuelrtsch.sidetrack.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.manuelrtsch.sidetrack.ServiceId
import io.github.manuelrtsch.sidetrack.data.ArtworkAccess
import io.github.manuelrtsch.sidetrack.data.AuthInput
import io.github.manuelrtsch.sidetrack.data.HttpPlexApi
import io.github.manuelrtsch.sidetrack.data.PlexApi
import io.github.manuelrtsch.sidetrack.data.ServiceAccount
import io.github.manuelrtsch.sidetrack.data.ServiceAuth
import io.github.manuelrtsch.sidetrack.data.ServiceException
import io.github.manuelrtsch.sidetrack.data.ServiceFailure
import io.github.manuelrtsch.sidetrack.data.normalizePlexServerUrl
import io.github.manuelrtsch.sidetrack.data.stringOrNull
import io.github.manuelrtsch.sidetrack.plex.BuildConfig
import io.github.manuelrtsch.sidetrack.storage.EncryptedStoreException
import io.github.manuelrtsch.sidetrack.storage.GuardedEncryptedStore
import io.github.manuelrtsch.sidetrack.storage.JsonStore
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A signed-in Plex account and the one server the session uses. */
data class PlexCredentials(
    val serverUrl: String,
    val token: String,
    val clientIdentifier: String,
    val userName: String,
    val machineIdentifier: String? = null
) {
    /** The headers every Plex request carries: API, audio and artwork. */
    fun headers(): Map<String, String> = mapOf(
        "X-Plex-Token" to token,
        "X-Plex-Client-Identifier" to clientIdentifier,
        "Accept" to "application/json"
    )
}

/**
 * Plex's token and the client identity are kept in the same encrypted, write-through store as the
 * other providers' sessions. The Plex password never reaches Sidetrack: sign-in is a PIN exchange
 * with plex.tv.
 */
class PlexCredentialStore(private val store: JsonStore) {
    constructor(context: Context, name: String = "sidetracks_plex") :
        this(GuardedEncryptedStore(context, name))

    fun read(): PlexCredentials? {
        val value = runCatching { store.read() }.getOrDefault(JSONObject())
        val server = value.optString(KEY_SERVER).takeIf { it.isNotEmpty() } ?: return null
        val token = value.optString(KEY_TOKEN).takeIf { it.isNotEmpty() } ?: return null
        val client = value.optString(KEY_CLIENT).takeIf { it.isNotEmpty() } ?: return null
        val user = value.optString(KEY_USER).takeIf { it.isNotEmpty() }.orEmpty()
        return PlexCredentials(
            serverUrl = server,
            token = token,
            clientIdentifier = client,
            userName = user,
            machineIdentifier = value.optString(KEY_MACHINE).takeIf { it.isNotEmpty() }
        )
    }

    fun save(credentials: PlexCredentials) {
        val value = JSONObject()
            .put(KEY_SERVER, credentials.serverUrl)
            .put(KEY_TOKEN, credentials.token)
            .put(KEY_CLIENT, credentials.clientIdentifier)
            .put(KEY_USER, credentials.userName)
            .put(KEY_MACHINE, credentials.machineIdentifier.orEmpty())
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
        const val KEY_CLIENT = "client_identifier"
        const val KEY_USER = "user_name"
        const val KEY_MACHINE = "machine_identifier"

        const val SAVE_FAILED =
            "Signed in, but this device could not store the session securely. " +
                "You will need to sign in again next time."
    }
}

/** A PIN created by plex.tv for this client. */
data class PlexPin(val id: Long, val code: String, val expiresAt: Long? = null)

/** One server connection Plex reports for the signed-in account. */
data class PlexConnection(
    val uri: String,
    val address: String,
    val port: Int,
    val protocol: String,
    val local: Boolean,
    val relay: Boolean
)

/** A server resource from plex.tv. [accessToken] is the server-scoped token when Plex reports one. */
data class PlexResource(
    val name: String,
    val clientIdentifier: String,
    val accessToken: String?,
    val provides: String,
    val owned: Boolean,
    val connections: List<PlexConnection>
)

data class PlexServerIdentity(val machineIdentifier: String, val claimed: Boolean, val version: String)

data class PlexAccountInfo(val username: String)

/**
 * The plex.tv calls the sign-in flow needs. Keeping them behind an interface lets the auth tests
 * run without a network, a Plex account or Android keystore plumbing.
 */
interface PlexAccountClient {
    suspend fun createPin(clientIdentifier: String): PlexPin
    suspend fun pinToken(clientIdentifier: String, pinId: Long): String?
    suspend fun resources(clientIdentifier: String, token: String): List<PlexResource>
    suspend fun identity(serverUrl: String, token: String, clientIdentifier: String): PlexServerIdentity?
    suspend fun account(clientIdentifier: String, token: String): PlexAccountInfo?
}

/**
 * Plex sign-in through a PIN, the flow Plex's own clients use.
 *
 * 1. `POST /api/v2/pins` on plex.tv creates an id and a short code.
 * 2. The browser opens `app.plex.tv/auth` with the code already in the fragment.
 * 3. The user approves the client; the adapter polls the pin until plex.tv returns an auth token.
 * 4. The account's server resources are probed and the first reachable, claimed music server is
 *    selected.
 *
 * No password is ever entered into Sidetrack, and the token is stored encrypted.
 */
class PlexAuth(
    private val store: PlexCredentialStore,
    private val clientIdentifier: String = UUID.randomUUID().toString(),
    private val client: PlexAccountClient = PlexAccountHttpClient(clientIdentifier),
    private val pollIntervalMs: Long = 1_500L,
    private val timeoutMs: Long = 180_000L
) : ServiceAuth {
    @Volatile
    private var current: PlexCredentials? = null

    fun hasCredentials(): Boolean = credentials() != null

    fun credentials(): PlexCredentials? = current ?: runCatching { store.read() }.getOrNull()?.also {
        current = it
        runCatching { ArtworkAccess.install(HttpPlexApi(it).headers) }
    }

    fun api(): PlexApi? = credentials()?.let { HttpPlexApi(it) }

    override suspend fun signIn(input: AuthInput): ServiceAccount {
        val browser = input as? AuthInput.Browser
            ?: throw ServiceException(ServiceFailure.NotConnected, "Plex needs a browser sign-in")
        val pin = client.createPin(clientIdentifier)
        browser.openBrowser(authUrl(pin))
        val token = pollForToken(pin)
        val discovered = discover(token)
        val credentials = PlexCredentials(
            serverUrl = discovered.serverUrl,
            token = discovered.token,
            clientIdentifier = clientIdentifier,
            userName = discovered.userName,
            machineIdentifier = discovered.machineIdentifier
        )
        current = credentials
        store.save(credentials)
        ArtworkAccess.install(HttpPlexApi(credentials).headers)
        return account(credentials)
    }

    override suspend fun restore(): ServiceAccount? = credentials()?.let { account(it) }

    override suspend fun signOut() = clear()

    fun clear() {
        current = null
        store.clear()
        ArtworkAccess.clear()
    }

    private suspend fun pollForToken(pin: PlexPin): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val token = client.pinToken(clientIdentifier, pin.id)
            if (!token.isNullOrBlank()) return token
            delay(pollIntervalMs)
        }
        throw ServiceException(
            ServiceFailure.AuthenticationExpired,
            "Plex sign-in timed out. Open the link again and approve Sidetrack."
        )
    }

    private suspend fun discover(accountToken: String): Discovered {
        val resources = client.resources(clientIdentifier, accountToken)
            .filter { resource -> resource.provides.split(',').any { it.trim() == "server" } }
        if (resources.isEmpty()) {
            throw ServiceException(
                ServiceFailure.NotConnected,
                "No Plex Media Server is linked to this Plex account"
            )
        }
        val failures = mutableListOf<String>()
        for (resource in resources.sortedBy { if (it.owned) 0 else 1 }) {
            val token = resource.accessToken?.takeIf { it.isNotBlank() } ?: accountToken
            val candidates = buildList {
                // Prefer plain HTTP to a local address. Plex's `plex.direct` certificate is trusted
                // by Plex clients, not necessarily by Android's trust store.
                resource.connections.filterNot { it.relay }.forEach { connection ->
                    if (connection.address.isNotBlank() && connection.port > 0) {
                        // Plain HTTP first: it works on every Plex install, while the plex.direct
                        // certificate is not in Android's trust store on every device.
                        add("http://${connection.address}:${connection.port}")
                        add("${connection.protocol.ifBlank { "http" }}://${connection.address}:${connection.port}")
                    }
                    add(connection.uri)
                }
                resource.connections.filter { it.relay }.forEach { add(it.uri) }
            }.filter { it.isNotBlank() }.distinct()
            for (candidate in candidates) {
                val normalized = runCatching { normalizePlexServerUrl(candidate) }.getOrNull() ?: continue
                val identity = runCatching { client.identity(normalized, token, clientIdentifier) }
                    .onFailure { failures += "${it::class.java.simpleName}: ${it.message}" }
                    .getOrNull() ?: continue
                if (!identity.claimed) {
                    failures += "$normalized is not claimed"
                    continue
                }
                val account = runCatching { client.account(clientIdentifier, token) }.getOrNull()
                return Discovered(
                    serverUrl = normalized,
                    token = token,
                    userName = account?.username ?: resource.name,
                    machineIdentifier = identity.machineIdentifier
                )
            }
        }
        Log.w("SidetrackPlex", "No reachable Plex server. ${failures.joinToString("; ")}")
        throw ServiceException(
            ServiceFailure.Network,
            "Couldn't reach a claimed Plex Media Server on this network"
        )
    }

    private fun account(credentials: PlexCredentials) = ServiceAccount(
        service = ServiceId.Plex,
        userId = credentials.userName,
        displayName = "Plex",
        serverUrl = credentials.serverUrl
    )

    private fun authUrl(pin: PlexPin): String = buildString {
        append("https://app.plex.tv/auth#?clientID=")
        append(encode(clientIdentifier))
        append("&code=")
        append(encode(pin.code))
        append("&context%5Bdevice%5D%5Bproduct%5D=")
        append(encode("Sidetrack"))
        append("&context%5Bdevice%5D%5Bversion%5D=")
        append(encode(BuildConfig.VERSION_NAME))
        append("&context%5Bdevice%5D%5Bplatform%5D=Android")
        append("&context%5Bdevice%5D%5Bdevice%5D=Sidephone")
    }

    private data class Discovered(
        val serverUrl: String,
        val token: String,
        val userName: String,
        val machineIdentifier: String
    )

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

/**
 * Plex's client identity for this install. Plex keys a PIN and a server session on it, so it must
 * not change between attempts. Android's per-install id is stable and needs no storage.
 */
@SuppressLint("HardwareIds") // A stable per-install Plex client id; hashed before it leaves the app.
fun plexClientIdentifier(context: Context): String {
    val androidId = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()
    if (androidId.isBlank()) return "sidetrack-${UUID.randomUUID()}"
    return "sidetrack-${UUID.nameUUIDFromBytes((androidId + context.packageName).toByteArray())}"
}

/**
 * The plex.tv HTTP client.
 *
 * These calls are deliberately separate from [HttpPlexApi]: plex.tv needs the client identifier
 * but no server token, while a media server needs the token and not the PIN endpoints.
 */
class PlexAccountHttpClient(
    private val clientIdentifier: String,
    private val timeoutMs: Int = 15_000
) : PlexAccountClient {
    override suspend fun createPin(clientIdentifier: String): PlexPin {
        val body = request("POST", "https://plex.tv/api/v2/pins?strong=true")
        val json = runCatching { JSONObject(body) }.getOrElse {
            throw ServiceException(ServiceFailure.Unknown, "plex.tv returned an unreadable PIN", it)
        }
        return PlexPin(
            id = json.optLong("id"),
            code = json.stringOrNull("code").orEmpty(),
            expiresAt = json.optLong("expiresAt").takeIf { it > 0 }
        )
    }

    override suspend fun pinToken(clientIdentifier: String, pinId: Long): String? {
        val body = request("GET", "https://plex.tv/api/v2/pins/$pinId")
        return runCatching { JSONObject(body).stringOrNull("authToken") }.getOrNull()
    }

    override suspend fun resources(clientIdentifier: String, token: String): List<PlexResource> {
        val body = request(
            "GET",
            "https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1",
            token
        )
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val connections = item.optJSONArray("connections")?.let { entries ->
                (0 until entries.length()).mapNotNull { entryIndex ->
                    val entry = entries.optJSONObject(entryIndex) ?: return@mapNotNull null
                    PlexConnection(
                        uri = entry.stringOrNull("uri").orEmpty(),
                        address = entry.stringOrNull("address").orEmpty(),
                        port = entry.optInt("port"),
                        protocol = entry.stringOrNull("protocol").orEmpty().ifBlank { "http" },
                        local = entry.optBoolean("local", false),
                        relay = entry.optBoolean("relay", false)
                    )
                }
            }.orEmpty()
            PlexResource(
                name = item.stringOrNull("name").orEmpty(),
                clientIdentifier = item.stringOrNull("clientIdentifier").orEmpty(),
                accessToken = item.stringOrNull("accessToken"),
                provides = item.stringOrNull("provides").orEmpty(),
                owned = item.optBoolean("owned", false),
                connections = connections
            )
        }
    }

    override suspend fun identity(serverUrl: String, token: String, clientIdentifier: String): PlexServerIdentity? {
        val body = request("GET", "$serverUrl/identity", token)
        val container = runCatching { JSONObject(body).getJSONObject("MediaContainer") }.getOrNull() ?: return null
        return PlexServerIdentity(
            machineIdentifier = container.stringOrNull("machineIdentifier").orEmpty(),
            claimed = container.optBoolean("claimed", false),
            version = container.stringOrNull("version").orEmpty()
        )
    }

    override suspend fun account(clientIdentifier: String, token: String): PlexAccountInfo? {
        val body = request("GET", "https://plex.tv/api/v2/user", token)
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val username = json.stringOrNull("username")
            ?: json.stringOrNull("email")
            ?: return null
        return PlexAccountInfo(username)
    }

    private suspend fun request(method: String, url: String, token: String? = null): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Plex-Client-Identifier", clientIdentifier)
            setRequestProperty("X-Plex-Product", "Sidetrack")
            setRequestProperty("X-Plex-Version", BuildConfig.VERSION_NAME)
            setRequestProperty("X-Plex-Platform", "Android")
            setRequestProperty("X-Plex-Device", "Sidephone")
            if (token != null) setRequestProperty("X-Plex-Token", token)
            if (method == "POST") doOutput = true
        }
        try {
            if (method == "POST") connection.outputStream.use { it.write(ByteArray(0)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                Log.w("SidetrackPlex", "plex.tv HTTP $status ${url.substringBefore('?')}")
                throw ServiceException(failureFor(status), "plex.tv returned HTTP $status")
            }
            body
        } catch (e: ServiceException) {
            throw e
        } catch (e: IOException) {
            throw ServiceException(ServiceFailure.Network, "Couldn't reach plex.tv", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun failureFor(status: Int): ServiceFailure = when (status) {
        401, 403 -> ServiceFailure.AuthenticationExpired
        404 -> ServiceFailure.TrackUnavailable
        429 -> ServiceFailure.RateLimited
        in 500..599 -> ServiceFailure.Unavailable
        else -> ServiceFailure.Unknown
    }
}
