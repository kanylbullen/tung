package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthTest {

    @Test
    fun registerReturnsAWorkingSessionToken() = tungTest {
        val email = uniqueEmail()
        val response = client.post("/api/v1/auth/register") { jsonBody("""{"email": "  ${email.uppercase()} ", "password": "long enough"}""") }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.json()
        val token = body["token"]!!.str
        assertTrue(token.startsWith("tung_"), token)
        assertEquals(email, body["user"]!!.jsonObject["email"]!!.str, "stored trimmed and lower-cased")

        val me = client.get("/api/v1/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, me.status)
        assertEquals(email, me.json()["email"]!!.str)
    }

    @Test
    fun registeringTheSameEmailTwiceIsAConflict() = tungTest {
        val email = uniqueEmail()
        client.register(email)
        val again = client.post("/api/v1/auth/register") { jsonBody("""{"email": "$email", "password": "another one"}""") }
        assertEquals(HttpStatusCode.Conflict, again.status)
        assertEquals("email_taken", again.errorCode())
    }

    @Test
    fun registerValidatesEmailAndPassword() = tungTest {
        val shortPassword = client.post("/api/v1/auth/register") { jsonBody("""{"email": "${uniqueEmail()}", "password": "short"}""") }
        assertEquals(HttpStatusCode.BadRequest, shortPassword.status)
        assertEquals("weak_password", shortPassword.errorCode())

        val notAnEmail = client.post("/api/v1/auth/register") { jsonBody("""{"email": "nope", "password": "long enough"}""") }
        assertEquals(HttpStatusCode.BadRequest, notAnEmail.status)
        assertEquals("invalid_email", notAnEmail.errorCode())

        val malformed = client.post("/api/v1/auth/register") { jsonBody("""{"email": """) }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        assertEquals("invalid_request", malformed.errorCode())
    }

    @Test
    fun loginGivesANewTokenAndRejectsAWrongPassword() = tungTest {
        val email = uniqueEmail()
        val first = client.register(email, "the right password")

        val wrong = client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "the wrong password"}""") }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertEquals("invalid_credentials", wrong.errorCode())

        val unknown = client.post("/api/v1/auth/login") { jsonBody("""{"email": "${uniqueEmail()}", "password": "the right password"}""") }
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("invalid_credentials", unknown.errorCode())

        val ok = client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "the right password"}""") }
        assertEquals(HttpStatusCode.OK, ok.status)
        val second = ok.json()["token"]!!.str
        assertNotEquals(first, second)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(second) }.status)
    }

    @Test
    fun logoutRevokesOnlyTheTokenUsed() = tungTest {
        val email = uniqueEmail()
        val a = client.register(email, "the right password")
        val b = client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "the right password"}""") }.json()["token"]!!.str

        assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/auth/logout") { bearerAuth(a) }.status)

        val rejected = client.get("/api/v1/me") { bearerAuth(a) }
        assertEquals(HttpStatusCode.Unauthorized, rejected.status)
        assertEquals("unauthorized", rejected.errorCode())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(b) }.status)
    }

    @Test
    fun requestsWithoutAValidTokenAreUnauthorized() = tungTest {
        val none = client.get("/api/v1/me")
        assertEquals(HttpStatusCode.Unauthorized, none.status)
        assertEquals("unauthorized", none.errorCode())
        assertTrue(none.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Bearer"))

        val garbage = client.get("/api/v1/me") { bearerAuth("tung_not-a-real-token") }
        assertEquals(HttpStatusCode.Unauthorized, garbage.status)
        val wrongPrefix = client.get("/api/v1/me") { bearerAuth("something-else") }
        assertEquals(HttpStatusCode.Unauthorized, wrongPrefix.status)
    }

    @Test
    fun authEndpointsAreRateLimitedPerAddress() = tungTest(authRequestsPerMinute = 3) {
        repeat(3) {
            val response = client.post("/api/v1/auth/login") { jsonBody("""{"email": "${uniqueEmail()}", "password": "whatever123"}""") }
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
        }
        val limited = client.post("/api/v1/auth/register") { jsonBody("""{"email": "${uniqueEmail()}", "password": "whatever123"}""") }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", limited.errorCode())
    }

    @Test
    fun behindAProxyTheLimitIsPerForwardedClient() = tungTest(authRequestsPerMinute = 2, trustProxy = true) {
        suspend fun login(forwardedFor: String) = client.post("/api/v1/auth/login") {
            header("X-Forwarded-For", forwardedFor)
            jsonBody("""{"email": "${uniqueEmail()}", "password": "whatever123"}""")
        }.status

        assertEquals(HttpStatusCode.Unauthorized, login("203.0.113.7"))
        assertEquals(HttpStatusCode.Unauthorized, login("203.0.113.7"))
        assertEquals(HttpStatusCode.TooManyRequests, login("203.0.113.7"))
        // Another client behind the same proxy has its own budget...
        assertEquals(HttpStatusCode.Unauthorized, login("198.51.100.9"))
        // ...and a forged first entry does not buy a new one: the last hop counts.
        assertEquals(HttpStatusCode.TooManyRequests, login("192.0.2.1, 203.0.113.7"))
    }

    @Test
    fun aForwardedHeaderFromTheClientBuysNoNewBudget() = tungTest(authRequestsPerMinute = 2, trustProxy = true) {
        // The proxy appends the real client to X-Forwarded-For and passes the client's own `Forwarded` through.
        suspend fun login(forged: String) = client.post("/api/v1/auth/login") {
            header("X-Forwarded-For", "203.0.113.8")
            header("Forwarded", "for=$forged")
            jsonBody("""{"email": "${uniqueEmail()}", "password": "whatever123"}""")
        }.status

        assertEquals(HttpStatusCode.Unauthorized, login("10.0.0.1"))
        assertEquals(HttpStatusCode.Unauthorized, login("10.0.0.2"))
        assertEquals(HttpStatusCode.TooManyRequests, login("10.0.0.3"))
    }
}
