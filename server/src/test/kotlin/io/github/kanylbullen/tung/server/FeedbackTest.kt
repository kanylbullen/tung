package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Feedback: any signed-in user sends a bug or an idea with screenshots; only the admins list, resolve and delete it. */
class FeedbackTest {

    private val admin = uniqueEmail()

    /** The smallest valid PNG signature with some bytes after it: the server only reads the signature. */
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32) { it.toByte() }
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(16)

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun feedbackTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application { module(TestDatabase.config().copy(adminEmails = setOf(admin))) }
        block()
    }

    private suspend fun ApplicationTestBuilder.send(token: String, body: String): HttpResponse =
        client.post("/api/v1/feedback") {
            bearerAuth(token)
            jsonBody(body)
        }

    @Test
    fun aUsersFeedbackWithScreenshotsReachesTheAdmin() = feedbackTest {
        val adminToken = client.register(admin)
        val member = uniqueEmail()
        val token = client.register(member)
        val message = "The rest timer stops when I lock the phone ${id()}"
        val sent = send(
            token,
            """{"kind": "bug", "message": "  $message  ", "platform": "android", "app_version": "0.1.0.360",
               "images": ["${b64(png)}", "data:image/jpeg;base64,${b64(jpeg)}"]}""",
        )
        assertEquals(HttpStatusCode.Created, sent.status, sent.bodyAsText())
        val feedbackId = sent.json()["id"]!!.str

        val listed = client.get("/api/v1/admin/feedback") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, listed.status)
        val body = listed.json()
        val row = body["feedback"]!!.jsonArray.map { it.jsonObject }.single { it["id"]!!.str == feedbackId }
        assertEquals(message, row["message"]!!.str, "trimmed")
        assertEquals(member, row["email"]!!.str)
        assertEquals("bug", row["kind"]!!.str)
        assertEquals("android", row["platform"]!!.str)
        assertEquals("0.1.0.360", row["app_version"]!!.str)
        assertEquals(JsonNull, row["resolved_at"])
        assertTrue(body["open_count"]!!.jsonPrimitive.int >= 1)
        val images = row["images"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("image/png", "image/jpeg"), images.map { it["content_type"]!!.str }, "in the order sent, typed by their bytes")

        val image = client.get("/api/v1/admin/feedback/$feedbackId/images/${images[0]["id"]!!.str}") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, image.status)
        assertEquals("image/png", image.headers["Content-Type"])
        assertEquals("nosniff", image.headers["X-Content-Type-Options"])
        assertContentEquals(png, image.readRawBytes())

        val resolve = client.patch("/api/v1/admin/feedback/$feedbackId") {
            bearerAuth(adminToken)
            jsonBody("""{"resolved": true}""")
        }
        assertEquals(HttpStatusCode.NoContent, resolve.status)
        val resolved = client.get("/api/v1/admin/feedback") { bearerAuth(adminToken) }.json()["feedback"]!!.jsonArray
            .map { it.jsonObject }.single { it["id"]!!.str == feedbackId }
        assertNotEquals(JsonNull, resolved["resolved_at"])

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/admin/feedback/$feedbackId") { bearerAuth(adminToken) }.status)
        assertTrue(client.get("/api/v1/admin/feedback") { bearerAuth(adminToken) }.json()["feedback"]!!.jsonArray.none { it.jsonObject["id"]!!.str == feedbackId })
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/api/v1/admin/feedback/$feedbackId/images/${images[0]["id"]!!.str}") { bearerAuth(adminToken) }.status,
            "the screenshots go with it",
        )
    }

    @Test
    fun onlyAdminsSeeItAndOnlySignedInUsersSendIt() = feedbackTest {
        val token = client.register()
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/feedback") { bearerAuth(token) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/feedback") { jsonBody("""{"kind":"bug","message":"x","platform":"web"}""") }.status)
        val sent = send(token, """{"kind": "idea", "message": "Dark mode for the watch", "platform": "web"}""")
        assertEquals(HttpStatusCode.Created, sent.status)
        val other = client.register()
        val patch = client.patch("/api/v1/admin/feedback/${sent.json()["id"]!!.str}") {
            bearerAuth(other)
            jsonBody("""{"resolved": true}""")
        }
        assertEquals(HttpStatusCode.Forbidden, patch.status)
    }

    @Test
    fun whatIsRefused() = feedbackTest {
        val token = client.register()
        suspend fun code(body: String): Pair<HttpStatusCode, String> = send(token, body).let { it.status to it.errorCode() }

        assertEquals(HttpStatusCode.BadRequest to "invalid_kind", code("""{"kind": "rant", "message": "x", "platform": "web"}"""))
        assertEquals(HttpStatusCode.BadRequest to "invalid_platform", code("""{"kind": "bug", "message": "x", "platform": "fridge"}"""))
        assertEquals(HttpStatusCode.BadRequest to "empty_message", code("""{"kind": "bug", "message": "   ", "platform": "web"}"""))
        assertEquals(HttpStatusCode.BadRequest to "message_too_long", code("""{"kind": "bug", "message": "${"a".repeat(MAX_FEEDBACK_MESSAGE + 1)}", "platform": "web"}"""))
        val four = List(4) { "\"${b64(png)}\"" }.joinToString()
        assertEquals(HttpStatusCode.BadRequest to "too_many_images", code("""{"kind": "bug", "message": "x", "platform": "web", "images": [$four]}"""))
        assertEquals(HttpStatusCode.BadRequest to "invalid_image", code("""{"kind": "bug", "message": "x", "platform": "web", "images": ["${b64("<svg></svg>".toByteArray())}"]}"""))
        assertEquals(HttpStatusCode.BadRequest to "invalid_image", code("""{"kind": "bug", "message": "x", "platform": "web", "images": ["not base64 !!"]}"""))
        val big = png + ByteArray(MAX_FEEDBACK_IMAGE_BYTES)
        assertEquals(HttpStatusCode.PayloadTooLarge to "image_too_large", code("""{"kind": "bug", "message": "x", "platform": "web", "images": ["${b64(big)}"]}"""))
    }

    @Test
    fun aDayHasALimit() = feedbackTest {
        val token = client.register()
        repeat(MAX_FEEDBACK_PER_DAY) {
            assertEquals(HttpStatusCode.Created, send(token, """{"kind": "idea", "message": "idea $it", "platform": "web"}""").status)
        }
        val over = send(token, """{"kind": "idea", "message": "one more", "platform": "web"}""")
        assertEquals(HttpStatusCode.TooManyRequests, over.status)
        assertEquals("too_much_feedback", over.errorCode())
    }

    @Test
    fun theImageTypeIsReadFromTheBytes() {
        assertEquals("image/png", FeedbackStore.imageType(png))
        assertEquals("image/jpeg", FeedbackStore.imageType(jpeg))
        assertEquals("image/webp", FeedbackStore.imageType("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertEquals(null, FeedbackStore.imageType("GIF89a".toByteArray()))
        assertEquals(null, FeedbackStore.imageType(ByteArray(0)))
    }
}
