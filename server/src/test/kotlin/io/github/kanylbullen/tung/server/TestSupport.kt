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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.testcontainers.containers.PostgreSQLContainer
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

    fun config(authRequestsPerMinute: Int = 10_000, trustProxy: Boolean = false) = ServerConfig(
        databaseUrl = container.jdbcUrl,
        databaseUser = container.username,
        databasePassword = container.password,
        authRequestsPerMinute = authRequestsPerMinute,
        trustProxy = trustProxy,
    )
}

/** A Ktor test application on the real database. The rate limit is lifted unless a test asks for one. */
fun tungTest(
    authRequestsPerMinute: Int = 10_000,
    trustProxy: Boolean = false,
    block: suspend ApplicationTestBuilder.() -> Unit,
) = testApplication {
    environment { config = MapApplicationConfig() }
    application { module(TestDatabase.config(authRequestsPerMinute, trustProxy)) }
    block()
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
