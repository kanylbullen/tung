package io.github.kanylbullen.tung.server

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.content.OutgoingContent
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.PostgreSQLContainer
import io.github.kanylbullen.tung.server.push.HostResolver
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.UUID
import kotlin.test.assertEquals

/**
 * One real Postgres for the whole test JVM (Testcontainers, the same image as
 * deploy/docker-compose.yml). Every test registers its own users, so tests do
 * not see each other's data and need no cleanup.
 */
object TestDatabase {
    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:17-alpine").apply { start() }
    }

    fun config(
        authRequestsPerMinute: Int = 10_000,
        trustProxy: Boolean = false,
        tokenRequestsPerMinute: Int = 10_000,
        clientIpHeader: String? = null,
    ) = ServerConfig(
        databaseUrl = container.jdbcUrl,
        databaseUser = container.username,
        databasePassword = container.password,
        authRequestsPerMinute = authRequestsPerMinute,
        tokenRequestsPerMinute = tokenRequestsPerMinute,
        trustProxy = trustProxy,
        clientIpHeader = clientIpHeader,
        pushResolver = TestResolver,
    )
}

/**
 * Host names for the push endpoint guard, so no test depends on real DNS:
 * a public distributor and a name that points inside. Anything else does
 * not resolve.
 */
val TestResolver = HostResolver { host ->
    when (host) {
        "ntfy.sh" -> listOf(InetAddress.getByName("159.203.148.75"))
        "internal.example.org" -> listOf(InetAddress.getByName("203.0.113.9"), InetAddress.getByName("10.0.0.7"))
        else -> throw UnknownHostException(host)
    }
}

/** A Ktor test application on the real database. The rate limit is lifted unless a test asks for one. */
fun tungTest(
    authRequestsPerMinute: Int = 10_000,
    trustProxy: Boolean = false,
    clientIpHeader: String? = null,
    pushAllowPrivate: Boolean = false,
    tokenRequestsPerMinute: Int = 10_000,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    environment { config = MapApplicationConfig() }
    application { module(TestDatabase.config(authRequestsPerMinute, trustProxy, tokenRequestsPerMinute, clientIpHeader).copy(pushAllowPrivate = pushAllowPrivate)) }
    block()
}

/** A JSON object of about [bytes] bytes: one string field of padding. */
fun paddedJson(bytes: Int, field: String = "padding"): String = """{"$field": "${"x".repeat(bytes)}"}"""

/** A JSON body sent without a Content-Length (chunked): [bytes] bytes of padding in one string field. */
fun HttpRequestBuilder.chunkedJsonBody(bytes: Int, field: String = "padding") {
    setBody(object : OutgoingContent.WriteChannelContent() {
        override val contentType = ContentType.Application.Json
        override val contentLength: Long? = null
        override suspend fun writeTo(channel: ByteWriteChannel) {
            channel.writeFully("""{"$field": """".toByteArray())
            val chunk = ByteArray(8 * 1024) { 'x'.code.toByte() }
            repeat(bytes / chunk.size) { channel.writeFully(chunk) }
            channel.writeFully("\"}".toByteArray())
        }
    })
}

fun uniqueEmail() = "user-${UUID.randomUUID()}@example.org"

fun id(): String = UUID.randomUUID().toString()

fun json(text: String): JsonObject = ApiJson.parseToJsonElement(text).jsonObject

suspend fun HttpResponse.json(): JsonObject = json(bodyAsText())

val JsonElement.str: String get() = jsonPrimitive.content

suspend fun HttpResponse.errorCode(): String = json()["error"]!!.jsonObject["code"]!!.str

fun HttpRequestBuilder.jsonBody(text: String) {
    contentType(ContentType.Application.Json)
    setBody(text)
}

/** Registers a fresh user and returns their session token. */
suspend fun HttpClient.register(email: String = uniqueEmail(), password: String = "correct horse battery"): String {
    val response = post("/api/v1/auth/register") { jsonBody("""{"email": "$email", "password": "$password"}""") }
    assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    return response.json()["token"]!!.str
}

suspend fun HttpClient.authedGet(path: String, token: String) = get(path) { bearerAuth(token) }

suspend fun HttpClient.putWorkout(token: String, workoutId: String, body: String) =
    put("/api/v1/workouts/$workoutId") {
        bearerAuth(token)
        jsonBody(body)
    }
