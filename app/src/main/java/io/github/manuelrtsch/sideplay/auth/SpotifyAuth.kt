package io.github.manuelrtsch.sideplay.auth

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Spotify's OAuth authorization-code flow with PKCE and a loopback callback. The app opens the
 * system browser; the browser returns to a short-lived `127.0.0.1` listener, so there is no deep
 * link and the user's password never enters Sidekit.
 *
 * The client id is Spotify's desktop client id, matching the one librespot presents at login5.
 * Keep the two in step: Spotify rejects stored credentials minted by a different client.
 */
class SpotifyAuth(
    private val store: SpotifyCredentialStore,
    private val tokens: SpotifyTokenClient = HttpSpotifyTokenClient(),
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val refreshLock = Mutex()

    fun hasCredentials(): Boolean = store.read() != null

    /**
     * Runs the browser round trip and stores the resulting tokens. Throws with a user-facing
     * message when Spotify denies the request, the state does not match, or the callback times out.
     */
    suspend fun signIn(openBrowser: suspend (String) -> Unit): SpotifyCredentials {
        val verifier = SpotifyPkce.verifier()
        val state = SpotifyPkce.state()

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = CALLBACK_TIMEOUT_MS
            val redirectUri = "http://127.0.0.1:${server.localPort}$REDIRECT_PATH"
            val url = SpotifyPkce.authorizationUrl(
                clientId = CLIENT_ID,
                redirectUri = redirectUri,
                challenge = SpotifyPkce.challenge(verifier),
                state = state,
                scopes = SCOPES
            )
            openBrowser(url)
            val code = awaitCode(server, state)
            val credentials = tokens.exchange(CLIENT_ID, code, verifier, redirectUri)
            store.save(credentials)
            return credentials
        }
    }

    /** Returns a usable access token, refreshing it first when it is inside the expiry window. */
    suspend fun accessToken(): String? = refreshLock.withLock {
        val credentials = store.read() ?: return@withLock null
        if (!credentials.isExpired(nowMs())) return@withLock credentials.accessToken
        val refreshToken = credentials.refreshToken ?: return@withLock null
        runCatching { tokens.refresh(CLIENT_ID, refreshToken) }
            .getOrNull()
            ?.let { it.copy(scope = it.scope.ifEmpty { credentials.scope }) }
            ?.also(store::save)
            ?.accessToken
    }

    fun clear() = store.clear()

    private suspend fun awaitCode(server: ServerSocket, expectedState: String): String {
        val closeOnCancel = coroutineContext[Job]?.invokeOnCompletion {
            runCatching { server.close() }
        }
        try {
            return withContext(Dispatchers.IO) {
                while (true) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketTimeoutException) {
                        throw IOException("Sign-in timed out — please try again", e)
                    } catch (e: IOException) {
                        coroutineContext.ensureActive()
                        throw e
                    }
                    socket.use {
                        when (val result = readCallback(it, expectedState)) {
                            is SpotifyPkce.Callback.Code -> return@withContext result.code
                            is SpotifyPkce.Callback.Failure -> throw IOException(result.message)
                            SpotifyPkce.Callback.Ignored -> Unit
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        } catch (e: CancellationException) {
            throw e
        } finally {
            closeOnCancel?.dispose()
        }
    }

    private fun readCallback(socket: Socket, expectedState: String): SpotifyPkce.Callback {
        val requestLine = socket.getInputStream().bufferedReader().readLine() ?: return SpotifyPkce.Callback.Ignored
        val target = requestLine.split(' ').getOrNull(1) ?: return SpotifyPkce.Callback.Ignored
        val result = SpotifyPkce.parseCallback(target, expectedState)
        val message = when (result) {
            is SpotifyPkce.Callback.Code -> "Signed in to Sideplay. You can close this page."
            is SpotifyPkce.Callback.Failure -> "Sign-in did not complete. You can close this page."
            SpotifyPkce.Callback.Ignored -> "Waiting for Spotify…"
        }
        respond(socket, message)
        return result
    }

    private fun respond(socket: Socket, message: String) {
        val body = """
            <!doctype html><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Sideplay</title>
            <body style="font-family:sans-serif;background:#111;color:#eee;padding:2rem">
            <p>$message</p>
        """.trimIndent()
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        runCatching {
            socket.getOutputStream().apply {
                write(headers.toByteArray(Charsets.US_ASCII))
                write(bytes)
                flush()
            }
        }
    }

    private companion object {
        /**
         * Spotify's desktop ("keymaster") client id. This is the id librespot's default
         * SessionConfig presents, and the loopback path is registered for it.
         */
        const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
        const val REDIRECT_PATH = "/login"
        const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1_000

        const val SCOPES =
            "app-remote-control playlist-modify playlist-modify-private " +
                "playlist-modify-public playlist-read playlist-read-collaborative " +
                "playlist-read-private streaming ugc-image-upload user-follow-modify " +
                "user-follow-read user-library-modify user-library-read user-modify " +
                "user-modify-playback-state user-modify-private user-personalized " +
                "user-read-birthdate user-read-currently-playing user-read-email " +
                "user-read-play-history user-read-playback-position " +
                "user-read-playback-state user-read-private " +
                "user-top-read"
    }
}
