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
        val (title, hint) = when (result) {
            is SpotifyPkce.Callback.Code -> "Welcome to Sideplay" to
                "Signed in successfully.<br>You can now close this page."
            is SpotifyPkce.Callback.Failure -> "Sign-in did not finish" to "Return to Sideplay and try again."
            SpotifyPkce.Callback.Ignored -> "Waiting for Spotify…" to "Complete sign-in in the browser."
        }
        respond(socket, title, hint)
        return result
    }

    /**
     * The browser's landing page after the loopback round trip. It mirrors the app's settled status
     * screens: the still vinyl record, a body-small title in white, a caption hint, and one full-width
     * line button. Everything is inline so the single-shot listener only serves this one request.
     */
    private fun respond(socket: Socket, title: String, hint: String) {
        val body = """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>$title · Sideplay</title>
            <style>
              :root {
                color-scheme: dark;
                --surface-a1: #121212;
                --surface-a2: #282828;
                --surface-a3: #3f3f3f;
                --surface-a4: #575757;
                --surface-a5: #717171;
                --surface-a6: #8b8b8b;
                --success-a1: #22946e;
                --success-a3: #9ae8ce;
              }
              * { box-sizing: border-box; }
              html {
                display: flex;
                height: 100%;
                margin: 0;
                background: #000;
              }
              body {
                display: flex;
                align-items: center;
                justify-content: center;
                width: min(100vw, 480px);
                height: min(100svh, 640px);
                margin: auto;
                padding: clamp(12px, 4vh, 24px);
                overflow-y: auto;
                background: var(--surface-a1);
                color: #fff;
                font-family: Inter, system-ui, -apple-system, "Segoe UI", sans-serif;
                -webkit-font-smoothing: antialiased;
              }
              main {
                display: flex;
                flex-direction: column;
                align-items: center;
                width: 100%;
                margin: auto;
                max-width: 360px;
                text-align: center;
              }
              .record { display: block; width: clamp(88px, 22vh, 132px); height: auto; }
              h1 {
                margin: clamp(14px, 3.6vh, 26px) 0 0;
                font-size: clamp(26px, 5vh, 32px);
                line-height: clamp(32px, 6.1vh, 39px);
                font-weight: 400;
                letter-spacing: -0.01em;
              }
              p {
                margin: 4px 0 0;
                font-size: clamp(22px, 4.4vh, 28px);
                line-height: clamp(27px, 5.3vh, 34px);
                color: var(--surface-a6);
              }
              .open {
                display: flex;
                align-items: center;
                justify-content: center;
                gap: 10px;
                width: 100%;
                min-height: clamp(48px, 9.4vh, 60px);
                margin-top: clamp(16px, 3.8vh, 26px);
                padding: 10px 16px;
                border-radius: 36px;
                background: var(--surface-a3);
                color: #fff;
                font-size: clamp(24px, 4.7vh, 30px);
                line-height: clamp(30px, 5.8vh, 36px);
                text-decoration: none;
                transition: background-color 120ms ease;
              }
              .open:active { background: var(--surface-a4); }
              .open:focus-visible { outline: 2px solid var(--success-a3); outline-offset: 3px; }
              .open svg { flex: none; }
              @media (prefers-reduced-motion: reduce) { .open { transition: none; } }
            </style>
            </head>
            <body>
            <main>
              <svg class="record" viewBox="0 0 132 132" role="img" aria-label="$title">
                <circle cx="66" cy="66" r="64" fill="#282828" stroke="#575757" stroke-width="1"/>
                <circle cx="66" cy="66" r="38.1" fill="none" stroke="#3f3f3f" stroke-width="1"/>
                <circle cx="66" cy="66" r="42.9" fill="none" stroke="#3f3f3f" stroke-width="1"/>
                <circle cx="66" cy="66" r="47.7" fill="none" stroke="#3f3f3f" stroke-width="1"/>
                <circle cx="66" cy="66" r="52.5" fill="none" stroke="#3f3f3f" stroke-width="1"/>
                <circle cx="66" cy="66" r="57.3" fill="none" stroke="#3f3f3f" stroke-width="1"/>
                <circle cx="66" cy="66" r="21.8" fill="#22946e" stroke="#9ae8ce" stroke-width="1"/>
                <circle cx="66" cy="66" r="3.5" fill="#121212"/>
                <path d="M93.5 18.3 A55 55 0 0 1 120.2 75.6" fill="none" stroke="#fff" stroke-opacity="0.18" stroke-width="2" stroke-linecap="round"/>
              </svg>
              <h1>$title</h1>
              <p>$hint</p>
              <a class="open" href="sideplay://auth">
                Open Sideplay
                <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
                  <path d="M3 9h11M10 4l5 5-5 5" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                </svg>
              </a>
            </main>
            </body>
            </html>
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
