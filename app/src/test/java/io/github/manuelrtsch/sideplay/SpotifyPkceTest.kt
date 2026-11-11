package io.github.manuelrtsch.sideplay

import io.github.manuelrtsch.sideplay.auth.SpotifyPkce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyPkceTest {
    @Test fun `challenge matches the RFC 7636 example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyPkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
    }

    @Test fun `verifiers are URL safe and long enough`() {
        val verifier = SpotifyPkce.verifier()

        assertTrue(verifier.length in 43..128)
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun `authorization URL contains the PKCE fields`() {
        val url = SpotifyPkce.authorizationUrl(
            clientId = "client",
            redirectUri = "http://127.0.0.1:4321/login",
            challenge = "challenge",
            state = "state",
            scopes = "streaming user-read-private"
        )

        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("code_challenge=challenge"))
        assertTrue(url.contains("state=state"))
        assertTrue(url.contains("scope=streaming+user-read-private"))
    }

    @Test fun `callback accepts only the matching state`() {
        assertEquals(
            SpotifyPkce.Callback.Code("abc"),
            SpotifyPkce.parseCallback("/login?code=abc&state=expected", "expected")
        )
        assertTrue(
            SpotifyPkce.parseCallback("/login?code=abc&state=wrong", "expected") is SpotifyPkce.Callback.Failure
        )
    }

    @Test fun `denied callback becomes a readable failure`() {
        assertEquals(
            SpotifyPkce.Callback.Failure("Sign-in was cancelled"),
            SpotifyPkce.parseCallback("/login?error=access_denied&state=expected", "expected")
        )
    }

    @Test fun `favicon and other requests are ignored`() {
        assertFalse(SpotifyPkce.isCallback("/favicon.ico"))
        assertEquals(SpotifyPkce.Callback.Ignored, SpotifyPkce.parseCallback("/favicon.ico", "expected"))
    }
}
