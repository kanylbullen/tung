package io.github.kanylbullen.tung.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `DELETE /api/v1/me`: the account and everything of it go, from a session token, with the email typed again. */
class DeleteAccountTest {

    private suspend fun HttpClient.deleteAccount(token: String, email: String): HttpResponse =
        delete("/api/v1/me") {
            bearerAuth(token)
            jsonBody("""{"email": "$email"}""")
        }

    @Test
    fun deletesTheAccountItsDataAndItsTokens() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        client.putWorkout(session, id(), """{"started_at": 1000, "ended_at": 2000}""")
        val apiToken = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "my script", "scope": "write"}""")
        }.json()["token"]!!.str
        val other = client.register()
        val othersWorkout = id()
        client.putWorkout(other, othersWorkout, """{"started_at": 3000, "ended_at": 4000}""")

        // Case and surrounding spaces do not matter.
        assertEquals(HttpStatusCode.NoContent, client.deleteAccount(session, "  ${email.uppercase()} ").status)

        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", session).status)
        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", apiToken).status)
        val login = client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "correct horse battery"}""") }
        assertEquals(HttpStatusCode.Unauthorized, login.status)

        // The address is free again, and a new account under it starts empty.
        val again = client.register(email)
        assertTrue(client.authedGet("/api/v1/workouts", again).json()["workouts"]!!.jsonArray.isEmpty())

        // Nobody else's data is touched.
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/workouts/$othersWorkout", other).status)
    }

    @Test
    fun onlyASessionTokenMayDeleteTheAccount() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val apiToken = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "my script", "scope": "write"}""")
        }.json()["token"]!!.str

        val refused = client.deleteAccount(apiToken, email)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("session_required", refused.errorCode())
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", session).status)
    }

    @Test
    fun anotherTokenCannotMintASessionToDeleteWith() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val apiToken = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "my script", "scope": "write"}""")
        }.json()["token"]!!.str
        for (name in listOf("session", " Session ", "watch", "mcp:claude")) {
            val minted = client.post("/api/v1/tokens") {
                bearerAuth(apiToken)
                jsonBody("""{"name": "$name", "scope": "write"}""")
            }
            assertEquals(HttpStatusCode.BadRequest, minted.status, name)
            assertEquals("reserved_name", minted.errorCode(), name)
        }
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", session).status)
    }

    @Test
    fun theEmailMustBeTheAccounts() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val other = uniqueEmail()
        client.register(other)

        val mismatch = client.deleteAccount(session, other)
        assertEquals(HttpStatusCode.BadRequest, mismatch.status)
        assertEquals("email_mismatch", mismatch.errorCode())
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", session).status)
    }

    @Test
    fun aReadTokenIsRefusedBeforeAnything() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val readToken = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "reader", "scope": "read"}""")
        }.json()["token"]!!.str

        val refused = client.deleteAccount(readToken, email)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("insufficient_scope", refused.errorCode())
    }
}
