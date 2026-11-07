package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.web.sync.AuthFailure
import io.github.kanylbullen.tung.web.sync.AuthResult
import io.github.kanylbullen.tung.web.sync.HttpReply
import io.github.kanylbullen.tung.web.sync.HttpUnreachable
import io.github.kanylbullen.tung.web.sync.SignInMethods
import io.github.kanylbullen.tung.web.sync.WebAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Sign in with Google on the web: the client id from the server, the ID token for a session, and each refusal by name. */
class WebAuthGoogleTest {

    private fun methods(reply: () -> HttpReply) = WebAuth(get = { url ->
        assertEquals("https://s/api/v1/auth/methods", url)
        reply()
    })

    @Test
    fun theClientIdComesFromTheServer() = runTest {
        assertEquals(
            "123.apps.googleusercontent.com",
            methods { HttpReply(200, """{"password":true,"google":{"client_id":"123.apps.googleusercontent.com"}}""") }.googleClientId("https://s"),
        )
        assertNull(methods { HttpReply(200, """{"password":true,"google":null}""") }.googleClientId("https://s"), "no Google there")
        assertNull(methods { HttpReply(404, "") }.googleClientId("https://s"), "a server from before the route")
        assertNull(methods { throw HttpUnreachable(null) }.googleClientId("https://s"))
    }

    @Test
    fun theIdTokenBecomesASessionAndEachRefusalHasItsName() = runTest {
        suspend fun answer(reply: () -> HttpReply): AuthResult {
            var sent = ""
            val auth = WebAuth(post = { url, body, _ ->
                assertEquals("https://s/api/v1/auth/google", url)
                sent = body
                reply()
            })
            val result = auth.signInWithGoogle("https://s", "eyJ.token")
            assertTrue(sent.contains("\"id_token\":\"eyJ.token\""), sent)
            return result
        }
        assertEquals(
            AuthResult.Success("tung_g", "x@example.org", userId = "u"),
            answer { HttpReply(200, """{"token":"tung_g","user":{"id":"u","email":"x@example.org","created_at":1,"has_password":false,"google_linked":true}}""") },
        )
        fun error(status: Int, code: String) = HttpReply(status, """{"error":{"code":"$code","message":"…"}}""")
        assertEquals(AuthResult.Failed(AuthFailure.GOOGLE_REJECTED), answer { error(401, "invalid_google_token") })
        assertEquals(AuthResult.Failed(AuthFailure.GOOGLE_EMAIL_UNVERIFIED), answer { error(401, "email_not_verified") })
        assertEquals(AuthResult.Failed(AuthFailure.GOOGLE_ACCOUNT_MISMATCH), answer { error(409, "google_account_mismatch") })
        assertEquals(AuthResult.Failed(AuthFailure.GOOGLE_NOT_OFFERED), answer { error(404, "not_configured") })
        assertEquals(AuthResult.Failed(AuthFailure.RATE_LIMITED), answer { HttpReply(429, "") })
        assertEquals(AuthResult.Failed(AuthFailure.SERVER), answer { HttpReply(200, """{"token":"tung_g"}""") }, "a session with no user names no account")
        assertEquals(AuthResult.Failed(AuthFailure.NETWORK), answer { throw HttpUnreachable(null) })
    }

    @Test
    fun theLanguageGoesWithTheTokenForTheWelcomeEmail() = runTest {
        var sent = ""
        WebAuth(post = { _, body, _ -> sent = body; HttpReply(429, "") }).signInWithGoogle("https://s", "eyJ.token", "sv")
        assertTrue(sent.contains("\"language\":\"sv\""), sent)
    }
}

/** Sign in with Apple on the web: the Services ID from the server, and the ID token for a session as Google's. */
class WebAuthAppleTest {

    @Test
    fun theServicesIdComesFromTheServerBesideGoogles() = runTest {
        val auth = WebAuth(get = { HttpReply(200, """{"password":true,"google":{"client_id":"g"},"apple":{"client_id":"fun.xuper.tung.web"}}""") })
        assertEquals(SignInMethods("g", "fun.xuper.tung.web"), auth.methods("https://s"))
        val older = WebAuth(get = { HttpReply(200, """{"password":true,"google":null}""") })
        assertEquals(SignInMethods(null, null), older.methods("https://s"), "a server from before Apple")
    }

    @Test
    fun theIdTokenBecomesASessionAndEachRefusalHasItsName() = runTest {
        suspend fun answer(reply: () -> HttpReply): AuthResult {
            var sent = ""
            val auth = WebAuth(post = { url, body, _ ->
                assertEquals("https://s/api/v1/auth/apple", url)
                sent = body
                reply()
            })
            val result = auth.signInWithApple("https://s", "eyJ.apple", "sv")
            assertTrue(sent.contains("\"id_token\":\"eyJ.apple\"") && sent.contains("\"language\":\"sv\""), sent)
            return result
        }
        assertEquals(
            AuthResult.Success("tung_a", "x@privaterelay.appleid.com", userId = "u"),
            answer { HttpReply(200, """{"token":"tung_a","user":{"id":"u","email":"x@privaterelay.appleid.com","created_at":1,"has_password":false,"google_linked":false,"apple_linked":true}}""") },
        )
        fun error(status: Int, code: String) = HttpReply(status, """{"error":{"code":"$code","message":"…"}}""")
        assertEquals(AuthResult.Failed(AuthFailure.APPLE_REJECTED), answer { error(401, "invalid_apple_token") })
        assertEquals(AuthResult.Failed(AuthFailure.APPLE_EMAIL_UNVERIFIED), answer { error(401, "email_not_verified") })
        assertEquals(AuthResult.Failed(AuthFailure.APPLE_ACCOUNT_MISMATCH), answer { error(409, "apple_account_mismatch") })
        assertEquals(AuthResult.Failed(AuthFailure.APPLE_NOT_OFFERED), answer { error(404, "not_configured") })
        assertEquals(AuthResult.Failed(AuthFailure.NETWORK), answer { throw HttpUnreachable(null) })
    }
}
