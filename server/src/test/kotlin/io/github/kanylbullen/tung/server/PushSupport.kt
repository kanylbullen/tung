package io.github.kanylbullen.tung.server

import com.sun.net.httpserver.HttpServer
import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.PUSH_ENDPOINTS_PATH
import io.github.kanylbullen.tung.core.sync.PushEndpoint
import io.github.kanylbullen.tung.core.sync.PushEndpointList
import io.github.kanylbullen.tung.core.sync.PushEndpointRequest
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.server.push.FCM_BASE_URL
import io.github.kanylbullen.tung.server.push.FcmServiceAccount
import io.github.kanylbullen.tung.server.push.PushService
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tiny HTTP server on an ephemeral loopback port that records every
 * request: the fake UnifiedPush distributor, the fake FCM and the fake
 * Google token endpoint. [respond] decides the answer per request.
 */
class FakeHttp : AutoCloseable {
    data class Request(val method: String, val path: String, val headers: Map<String, String>, val body: String)

    val requests: MutableList<Request> = CopyOnWriteArrayList()

    @Volatile
    var respond: (Request) -> Pair<Int, String> = { 200 to "" }

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/") { exchange ->
            val request = Request(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestHeaders.entries.associate { (k, v) -> k.lowercase() to v.joinToString(",") },
                exchange.requestBody.readBytes().decodeToString(),
            )
            requests += request
            val (status, body) = respond(request)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    val url = "http://127.0.0.1:${server.address.port}"

    fun to(path: String): List<Request> = requests.filter { it.path == path }

    override fun close() = server.stop(0)
}

/**
 * A test application whose push debounce is [debounceMillis], with the
 * given FCM settings; [block] gets the application's [PushService] to wait
 * for pushes to settle.
 */
fun pushTest(
    debounceMillis: Long = 100,
    fcm: FcmServiceAccount? = null,
    fcmBaseUrl: String = FCM_BASE_URL,
    block: suspend ApplicationTestBuilder.(PushService) -> Unit,
) = testApplication {
    environment { config = MapApplicationConfig() }
    var push: PushService? = null
    application {
        module(TestDatabase.config().copy(pushDebounceMillis = debounceMillis, fcmServiceAccount = fcm, fcmBaseUrl = fcmBaseUrl))
        push = attributes[PushServiceKey]
    }
    startApplication()
    block(checkNotNull(push))
}

suspend fun HttpClient.registerEndpoint(token: String, deviceId: String, transport: String, endpoint: String): HttpResponse =
    post(PUSH_ENDPOINTS_PATH) {
        bearerAuth(token)
        jsonBody(ApiJson.encodeToString(PushEndpointRequest.serializer(), PushEndpointRequest(deviceId, transport, endpoint)))
    }

/** Registers and expects 201 or 200. */
suspend fun HttpClient.registered(token: String, deviceId: String, transport: String, endpoint: String): PushEndpoint {
    val response = registerEndpoint(token, deviceId, transport, endpoint)
    assertTrue(response.status == HttpStatusCode.Created || response.status == HttpStatusCode.OK, response.bodyAsText())
    return ApiJson.decodeFromString(PushEndpoint.serializer(), response.bodyAsText())
}

suspend fun HttpClient.endpoints(token: String): List<PushEndpoint> {
    val response = authedGet(PUSH_ENDPOINTS_PATH, token)
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return ApiJson.decodeFromString(PushEndpointList.serializer(), response.bodyAsText()).endpoints
}

/** A sync that pushes one new workout row, made on [device] (null: no device header). */
suspend fun HttpClient.syncFrom(token: String, device: String?, changes: SyncChanges = oneRow()): HttpResponse {
    val response = post(SYNC_PATH) {
        bearerAuth(token)
        device?.let { header(DEVICE_HEADER, it) }
        jsonBody(ApiJson.encodeToString(SyncRequest.serializer(), SyncRequest(0, changes)))
    }
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return response
}

fun oneRow() = SyncChanges(workouts = listOf(SyncWorkout(id(), 1_700_000_000_000, 1_700_000_100_000, null, null, null, 1_000)))

fun rsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

/** A service-account JSON as the Firebase console makes it, with [keys]' private key and [tokenUri]. */
fun serviceAccountJson(keys: KeyPair, tokenUri: String, projectId: String = "tung-test"): String {
    val pem = "-----BEGIN PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
        "\n-----END PRIVATE KEY-----\n"
    return buildJsonObject {
        put("type", "service_account")
        put("project_id", projectId)
        put("private_key_id", "abc123")
        put("private_key", pem)
        put("client_email", "push@$projectId.iam.gserviceaccount.com")
        put("client_id", "1234567890")
        put("token_uri", tokenUri)
    }.toString()
}
