package io.github.manuelrtsch.sideplay.auth

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Swappable so token parsing and refresh logic can be tested without the network. */
interface SpotifyTokenClient {
    suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): SpotifyCredentials

    suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials
}

/**
 * Minimal form-encoded client for Spotify's token endpoint. Authorization-code exchange is the
 * only place the PKCE verifier leaves the device; refresh tokens are rotated when Spotify returns
 * a new one.
 */
class HttpSpotifyTokenClient : SpotifyTokenClient {
    override suspend fun exchange(
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): SpotifyCredentials = request(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "client_id" to clientId,
            "code_verifier" to verifier
        ),
        previousRefresh = null
    )

    override suspend fun refresh(clientId: String, refreshToken: String): SpotifyCredentials = request(
        mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to clientId
        ),
        previousRefresh = refreshToken
    )

    private suspend fun request(form: Map<String, String>, previousRefresh: String?): SpotifyCredentials = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" }
        val connection = (URL(TOKEN_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IOException("Spotify returned an unreadable token response (HTTP $status)")
            }
            if (status !in 200..299) {
                throw IOException(json.optString("error_description").ifEmpty { "Spotify token request failed (HTTP $status)" })
            }
            val access = json.optString("access_token")
            if (access.isEmpty()) throw IOException("Spotify did not return an access token")
            val expiresIn = json.optLong("expires_in", 0L)
            SpotifyCredentials(
                accessToken = access,
                refreshToken = json.optString("refresh_token").takeIf { it.isNotEmpty() } ?: previousRefresh,
                expiresAtMs = System.currentTimeMillis() + expiresIn.coerceAtLeast(0L) * 1_000L,
                scope = json.optString("scope"),
                tokenType = json.optString("token_type", "Bearer")
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        const val TIMEOUT_MS = 15_000
    }
}
