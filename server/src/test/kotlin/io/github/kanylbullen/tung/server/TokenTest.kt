package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TokenTest {

    @Test
    fun createListAndRevoke() = tungTest {
        val session = client.register()

        val created = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "my script", "scope": "read"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val createdBody = created.json()
        val readToken = createdBody["token"]!!.str
        val readTokenId = createdBody["id"]!!.str
        assertTrue(readToken.startsWith("tung_"))
        assertEquals("read", createdBody["scope"]!!.str)

        val list = client.get("/api/v1/tokens") { bearerAuth(session) }
        assertEquals(HttpStatusCode.OK, list.status)
        val listText = list.bodyAsText()
        assertFalse(readToken in listText, "the plaintext is shown once, never listed")
        val tokens = json(listText)["tokens"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("session", "my script"), tokens.map { it["name"]!!.str }.toSet())
        assertTrue(tokens.single { it["name"]!!.str == "session" }["current"]!!.jsonPrimitive.boolean)
        assertFalse(tokens.single { it["name"]!!.str == "my script" }["current"]!!.jsonPrimitive.boolean)

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(readToken) }.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/tokens/$readTokenId") { bearerAuth(session) }.status)
        val revoked = client.get("/api/v1/me") { bearerAuth(readToken) }
        assertEquals(HttpStatusCode.Unauthorized, revoked.status, "a revoked token is rejected")
        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/tokens/$readTokenId") { bearerAuth(session) }.status)

        val after = client.get("/api/v1/tokens") { bearerAuth(session) }.json()["tokens"]!!.jsonArray
        assertEquals(listOf("session"), after.map { it.jsonObject["name"]!!.str })
    }

    @Test
    fun aReadTokenCannotWrite() = tungTest {
        val session = client.register()
        val readToken = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "reader", "scope": "read"}""")
        }.json()["token"]!!.str

        assertEquals(HttpStatusCode.OK, client.get("/api/v1/workouts") { bearerAuth(readToken) }.status)
        val write = client.putWorkout(readToken, id(), """{"started_at": 1000}""")
        assertEquals(HttpStatusCode.Forbidden, write.status)
        assertEquals("insufficient_scope", write.errorCode())
        val mint = client.post("/api/v1/tokens") {
            bearerAuth(readToken)
            jsonBody("""{"name": "escalate", "scope": "write"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, mint.status)
    }

    @Test
    fun aUserCannotRevokeAnotherUsersToken() = tungTest {
        val alice = client.register()
        val bob = client.register()
        val aliceTokenId = client.get("/api/v1/tokens") { bearerAuth(alice) }.json()["tokens"]!!.jsonArray.single().jsonObject["id"]!!.str

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/v1/tokens/$aliceTokenId") { bearerAuth(bob) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearerAuth(alice) }.status)
    }

    @Test
    fun tokenInputIsValidated() = tungTest {
        val session = client.register()
        val badScope = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "x", "scope": "admin"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badScope.status)
        assertEquals("invalid_scope", badScope.errorCode())
        val noName = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "  ", "scope": "read"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, noName.status)
    }
}
