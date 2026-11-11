package io.github.manuelrtsch.sideplay.auth

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Pure OAuth PKCE helpers. Kept free of Android types so the security-sensitive parts can be
 * verified by ordinary JVM tests.
 */
object SpotifyPkce {
    private const val REDIRECT_PATH = "/login"

    fun verifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(64)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun state(random: SecureRandom = SecureRandom()): String = verifier(random)

    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        challenge: String,
        state: String,
        scopes: String
    ): String = buildString {
        append("https://accounts.spotify.com/authorize")
        append("?client_id=").append(encode(clientId))
        append("&response_type=code")
        append("&redirect_uri=").append(encode(redirectUri))
        append("&scope=").append(encode(scopes))
        append("&state=").append(encode(state))
        append("&code_challenge_method=S256")
        append("&code_challenge=").append(encode(challenge))
    }

    fun isCallback(target: String): Boolean = target.substringBefore('?').substringBefore('#') == REDIRECT_PATH

    /** Parses the loopback request target (`/login?code=…`) after validating the CSRF state. */
    fun parseCallback(target: String, expectedState: String): Callback {
        if (!isCallback(target)) return Callback.Ignored
        val query = target.substringAfter('?', "").substringBefore('#')
        val params = query.split('&')
            .filter { it.isNotEmpty() }
            .associate {
                val key = decode(it.substringBefore('='))
                val value = decode(it.substringAfter('=', ""))
                key to value
            }

        params["error"]?.let { error ->
            return Callback.Failure(
                if (error == "access_denied") "Sign-in was cancelled" else "Spotify rejected the sign-in: $error"
            )
        }
        if (params["state"] != expectedState) {
            return Callback.Failure("Sign-in could not be verified — please try again")
        }
        val code = params["code"].orEmpty()
        return if (code.isEmpty()) {
            Callback.Failure("Spotify did not return an authorization code")
        } else {
            Callback.Code(code)
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    sealed interface Callback {
        data class Code(val code: String) : Callback
        data class Failure(val message: String) : Callback
        data object Ignored : Callback
    }
}
