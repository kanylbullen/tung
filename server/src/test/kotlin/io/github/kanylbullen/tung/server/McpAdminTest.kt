package io.github.kanylbullen.tung.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The admins' feedback tools over MCP: the same list, screenshots and resolve as `/admin/feedback`, refused the same way. */
class McpAdminTest {

    private val admin = uniqueEmail()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(24) { it.toByte() }

    private fun adminTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application { module(TestDatabase.config().copy(adminEmails = setOf(admin))) }
        block()
    }

    /** One tools/call; the result object (content and isError). */
    private suspend fun HttpClient.call(token: String, name: String, arguments: String = "{}"): JsonObject {
        val response = post("/mcp") {
            bearerAuth(token)
            header(HttpHeaders.Accept, "application/json, text/event-stream")
            jsonBody("""{"jsonrpc": "2.0", "id": 5, "method": "tools/call", "params": {"name": "$name", "arguments": $arguments}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.json()["result"]!!.jsonObject
    }

    private val JsonObject.isError get() = this["isError"]?.jsonPrimitive?.boolean ?: false
    private val JsonObject.content get() = this["content"]!!.jsonArray.single().jsonObject
    private val JsonObject.text get() = content["text"]!!.str

    private suspend fun ApplicationTestBuilder.send(token: String, message: String, images: String = "[]"): String {
        val sent = client.post("/api/v1/feedback") {
            bearerAuth(token)
            jsonBody("""{"kind": "idea", "message": "$message", "platform": "android", "images": $images}""")
        }
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
        return sent.json()["id"]!!.str
    }

    @Test
    fun anAdminListsFetchesAScreenshotAndResolves() = adminTest {
        val adminToken = client.register(admin)
        val member = uniqueEmail()
        val memberToken = client.register(member)
        val message = "Keep the current set in the middle ${id()}"
        val feedbackId = send(memberToken, message, """["${Base64.getEncoder().encodeToString(png)}"]""")

        val listed = client.call(adminToken, "feedback")
        assertFalse(listed.isError, listed.text)
        val body = json(listed.text)
        val row = body["feedback"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.str == feedbackId }
        assertEquals(message, row["message"]!!.str)
        assertEquals(member, row["email"]!!.str)
        assertEquals(JsonNull, row["resolved_at"])
        assertTrue(body["open_count"]!!.jsonPrimitive.int >= 1)
        val imageId = row["images"]!!.jsonArray.single().jsonObject["id"]!!.str

        val shot = client.call(adminToken, "feedback_screenshot", """{"feedback_id": "$feedbackId", "image_id": "$imageId"}""")
        assertFalse(shot.isError)
        assertEquals("image", shot.content["type"]!!.str)
        assertEquals("image/png", shot.content["mimeType"]!!.str)
        assertContentEquals(png, Base64.getDecoder().decode(shot.content["data"]!!.str))

        val resolved = client.call(adminToken, "resolve_feedback", """{"feedback_id": "$feedbackId"}""")
        assertFalse(resolved.isError, resolved.text)
        val rest = client.get("/api/v1/admin/feedback") { bearerAuth(adminToken) }.json()["feedback"]!!.jsonArray.map { it.jsonObject }
        assertNotEquals(JsonNull, rest.single { it["id"]!!.str == feedbackId }["resolved_at"], "the same row the web app reads")

        val open = json(client.call(adminToken, "feedback").text)["feedback"]!!.jsonArray.map { it.jsonObject["id"]!!.str }
        assertFalse(feedbackId in open, "resolved ones are left out by default")
        val all = json(client.call(adminToken, "feedback", """{"include_resolved": true}""").text)["feedback"]!!.jsonArray
        assertTrue(all.any { it.jsonObject["id"]!!.str == feedbackId })

        val reopened = client.call(adminToken, "resolve_feedback", """{"feedback_id": "$feedbackId", "resolved": false}""")
        assertFalse(reopened.isError)
        val again = json(client.call(adminToken, "feedback").text)["feedback"]!!.jsonArray.map { it.jsonObject["id"]!!.str }
        assertTrue(feedbackId in again)
    }

    @Test
    fun anyoneElseIsRefusedAsTheRestRoutesRefuse() = adminTest {
        val memberToken = client.register()
        val feedbackId = send(memberToken, "Mine ${id()}")
        val listed = client.call(memberToken, "feedback")
        assertTrue(listed.isError)
        assertTrue(listed.text.contains("admins"), listed.text)
        val resolved = client.call(memberToken, "resolve_feedback", """{"feedback_id": "$feedbackId"}""")
        assertTrue(resolved.isError)

        val adminSession = client.register(admin)
        val readToken = client.post("/api/v1/tokens") {
            bearerAuth(adminSession)
            jsonBody("""{"name": "assistant", "scope": "read"}""")
        }.json()["token"]!!.str
        val read = client.call(readToken, "feedback")
        assertTrue(read.isError, "a read token is an assistant looking at training, not the admin list")
        assertTrue(read.text.contains("read-only"), read.text)
    }

    @Test
    fun anUnknownIdOrAMissingArgumentIsAToolError() = adminTest {
        val adminToken = client.register(admin)
        val unknown = client.call(adminToken, "resolve_feedback", """{"feedback_id": "${id()}"}""")
        assertTrue(unknown.isError)
        assertTrue(unknown.text.contains("not found", ignoreCase = true), unknown.text)
        val missing = client.call(adminToken, "feedback_screenshot", """{"feedback_id": "${id()}"}""")
        assertTrue(missing.isError)
        assertTrue(missing.text.contains("image_id"), missing.text)
    }
}
