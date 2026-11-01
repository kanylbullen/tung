package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.PUSH_ENDPOINTS_PATH
import io.github.kanylbullen.tung.core.sync.PushEndpoint
import io.github.kanylbullen.tung.core.sync.PushEndpointRequest
import io.github.kanylbullen.tung.core.sync.SyncEntry
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.entries
import io.github.kanylbullen.tung.core.sync.toChanges
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * tung-server as far as the app sees it, on a [MockWebServer]: the auth
 * routes and `POST /api/v1/sync` with the contract of issue #25. A pushed row
 * is stored only when its `updated_at` is strictly newer; every store stamps
 * the next `seq`; a response carries every row with `seq` above the request's
 * cursor (the client's own pushed rows included) and the highest `seq` as the
 * new cursor. An older push re-stamps the stored row, so the client gets the
 * newer one back.
 *
 * MockWebServer rather than Ktor's testApplication: it is a real HTTP server
 * on localhost, so the app's production client, OkHttp engine included, is
 * what the tests exercise, and the app's test classpath gets no Ktor server.
 */
class FakeSyncServer : Dispatcher() {

    private val server = MockWebServer().also { it.dispatcher = this }

    val baseUrl: String get() = server.url("/").toString().trimEnd('/')

    /** The token register and login hand out, and the one sync accepts. */
    var token = "tung_fake_session"

    /** The code `POST /api/v1/link/start` hands out, and the one `/link/claim` accepts once. */
    @Volatile var linkCode = "042917"

    /** Whether [linkCode] has been claimed. */
    @Volatile var linkClaimed = false

    /** Every `POST /api/v1/feedback` body the server took, as JSON text. */
    val feedback = CopyOnWriteArrayList<String>()

    /** What `/api/v1/feedback` answers; 201 unless a test sets another status (413, 429, …). */
    @Volatile var feedbackStatus = 201

    /** The Google client id `/auth/methods` names; null: the server does not offer Google (and `/auth/google` is 404). */
    @Volatile var googleClientId: String? = null

    /** The one Google ID token `/auth/google` accepts; anything else is 401 `invalid_google_token`. */
    @Volatile var googleIdToken = "google-id-token"

    /** The email `/auth/google` signs in as. */
    @Volatile var googleEmail = "lifter@gmail.com"

    /** Every id token `/auth/google` was sent. */
    val googleTokens = CopyOnWriteArrayList<String>()

    /** What `/me` says about Google: linked after a Google sign-in, or when a test sets it. */
    @Volatile var googleLinked = false

    /** Whether `DELETE /api/v1/me` deleted the account (with "lifter@example.org" typed, any case). */
    @Volatile var accountDeleted = false

    /** Every sync request as received, before any auth check. */
    val syncRequests = CopyOnWriteArrayList<SyncRequest>()

    /** The `X-Tung-Device` header of every sync request, in order (null when it was missing). */
    val syncDevices = CopyOnWriteArrayList<String?>()

    /** Every `POST /api/v1/push/endpoints` body, with the Authorization header it came with. */
    val pushRegistrations = CopyOnWriteArrayList<Pair<PushEndpointRequest, String?>>()

    /** The ids of every `DELETE /api/v1/push/endpoints/{id}`, with the Authorization header. */
    val pushDeletes = CopyOnWriteArrayList<Pair<String, String?>>()

    /** Every path the fake was asked for, in order. */
    val paths = CopyOnWriteArrayList<String>()
    val authPaths = CopyOnWriteArrayList<String>()

    /** Answer the next n sync requests with this status instead. */
    @Volatile var failNext: Int = 0
    @Volatile var failStatus: Int = 503

    /** Runs while a sync request is being handled, before the response is built. */
    @Volatile var duringSync: (SyncRequest) -> Unit = {}

    private class Stored(val entry: SyncEntry, val seq: Long)

    private val rows = HashMap<Pair<SyncTable, String>, Stored>()
    private var seq = 0L

    val cursor: Long @Synchronized get() = seq

    fun start() = server.start()

    fun stop() = server.shutdown()

    /** A change made elsewhere (another device): stored by the same strictly-newer rule as a push. */
    @Synchronized
    fun put(entry: SyncEntry) = store(entry)

    /** A row sent to the next client whatever its `updated_at`: a stale answer the client must ignore. */
    @Synchronized
    fun inject(entry: SyncEntry) {
        rows[entry.table to entry.id] = Stored(entry, ++seq)
    }

    @Synchronized
    fun stored(table: SyncTable, id: String): SyncEntry? = rows[table to id]?.entry

    @Synchronized
    fun storedCount(): Int = rows.size

    private fun store(entry: SyncEntry) {
        val key = entry.table to entry.id
        val current = rows[key]
        when {
            current == null || entry.updatedAt > current.entry.updatedAt -> rows[key] = Stored(entry, ++seq)
            entry.updatedAt < current.entry.updatedAt -> rows[key] = Stored(current.entry, ++seq)
            else -> Unit
        }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        paths += "${request.method} ${request.path}"
        val path = request.path.orEmpty()
        return when {
            path == PUSH_ENDPOINTS_PATH && request.method == "POST" -> registerPush(request)
            path.startsWith("$PUSH_ENDPOINTS_PATH/") && request.method == "DELETE" -> {
                pushDeletes += path.removePrefix("$PUSH_ENDPOINTS_PATH/") to request.getHeader("Authorization")
                MockResponse().setResponseCode(204)
            }
            else -> route(request)
        }
    }

    private fun registerPush(request: RecordedRequest): MockResponse {
        val body = SyncJson.decodeFromString(PushEndpointRequest.serializer(), request.body.readUtf8())
        pushRegistrations += body to request.getHeader("Authorization")
        if (request.getHeader("Authorization") != "Bearer $token") {
            return json(401, """{"error":{"code":"unauthorized","message":"Sign in again."}}""")
        }
        val endpoint = PushEndpoint(PUSH_ENDPOINT_ID, body.deviceId, body.transport, body.endpoint, createdAt = 1L)
        return json(201, SyncJson.encodeToString(PushEndpoint.serializer(), endpoint))
    }

    private fun route(request: RecordedRequest): MockResponse = when (request.path) {
        "/api/v1/auth/login", "/api/v1/auth/register" -> {
            authPaths += request.path
            json(
                if (request.path.orEmpty().endsWith("register")) 201 else 200,
                """{"token":"$token","user":{"id":"u","email":"lifter@example.org","created_at":0,"has_password":true,"google_linked":$googleLinked}}""",
            )
        }
        // Not in authPaths: Settings asks for it whenever the form shows a server.
        "/api/v1/auth/methods" -> {
            val google = googleClientId?.let { """{"client_id":"$it"}""" } ?: "null"
            json(200, """{"password":true,"google":$google}""")
        }
        "/api/v1/auth/google" -> {
            authPaths += request.path
            val sent = Regex("\"id_token\"\\s*:\\s*\"([^\"]*)\"").find(request.body.readUtf8())?.groupValues?.get(1)
            sent?.let { googleTokens += it }
            when {
                googleClientId == null -> json(404, """{"error":{"code":"not_configured","message":"Sign in with Google is not set up on this server."}}""")
                sent == null -> json(400, """{"error":{"code":"invalid_request","message":"id_token is missing."}}""")
                sent != googleIdToken -> json(401, """{"error":{"code":"invalid_google_token","message":"The Google token's signature is not valid."}}""")
                else -> {
                    googleLinked = true
                    json(200, """{"token":"$token","user":{"id":"g","email":"$googleEmail","created_at":0,"has_password":false,"google_linked":true}}""")
                }
            }
        }
        "/api/v1/me" -> {
            if (request.getHeader("Authorization") != "Bearer $token") {
                json(401, """{"error":{"code":"unauthorized","message":"Sign in again."}}""")
            } else if (request.method == "DELETE") {
                val typed = Regex("\"email\"\\s*:\\s*\"([^\"]*)\"").find(request.body.readUtf8())?.groupValues?.get(1)
                if (typed.equals("lifter@example.org", ignoreCase = true)) {
                    accountDeleted = true
                    MockResponse().setResponseCode(204)
                } else {
                    json(400, """{"error":{"code":"email_mismatch","message":"Type the account's email to confirm."}}""")
                }
            } else {
                json(200, """{"id":"u","email":"lifter@example.org","created_at":0,"has_password":true,"google_linked":$googleLinked}""")
            }
        }
        "/api/v1/auth/logout" -> {
            authPaths += request.path
            MockResponse().setResponseCode(204)
        }
        "/api/v1/link/start" -> {
            authPaths += request.path
            if (request.getHeader("Authorization") != "Bearer $token") {
                json(401, """{"error":{"code":"unauthorized","message":"Sign in again."}}""")
            } else {
                json(201, """{"code":"$linkCode","expires_at":${System.currentTimeMillis() + 10 * 60_000L}}""")
            }
        }
        "/api/v1/feedback" -> {
            authPaths += request.path
            if (request.getHeader("Authorization") != "Bearer $token") {
                json(401, """{"error":{"code":"unauthorized","message":"Sign in again."}}""")
            } else if (feedbackStatus != 201) {
                json(feedbackStatus, """{"error":{"code":"refused","message":"Refused."}}""")
            } else {
                feedback += request.body.readUtf8()
                json(201, """{"id":"f-${feedback.size}"}""")
            }
        }
        "/api/v1/link/claim" -> {
            authPaths += request.path
            val sent = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(request.body.readUtf8())?.groupValues?.get(1)
            synchronized(this) {
                if (sent == linkCode && !linkClaimed) {
                    linkClaimed = true
                    json(200, """{"token":"$token","user":{"id":"u","email":"lifter@example.org","created_at":0}}""")
                } else {
                    json(404, """{"error":{"code":"invalid_code","message":"That code is not valid."}}""")
                }
            }
        }
        SYNC_PATH -> sync(request)
        else -> json(404, """{"error":{"code":"not_found","message":"No such route."}}""")
    }

    private fun sync(request: RecordedRequest): MockResponse {
        val body = SyncJson.decodeFromString(SyncRequest.serializer(), request.body.readUtf8())
        syncRequests += body
        syncDevices += request.getHeader(DEVICE_HEADER)
        if (failNext > 0) {
            failNext--
            return json(failStatus, """{"error":{"code":"failure","message":"Failing on purpose."}}""")
        }
        if (request.getHeader("Authorization") != "Bearer $token") {
            return json(401, """{"error":{"code":"unauthorized","message":"Sign in again."}}""")
        }
        duringSync(body)
        val response = synchronized(this) {
            body.changes.entries().forEach { store(it) }
            val changed = rows.values.filter { it.seq > body.cursor }.sortedBy { it.seq }
            SyncResponse(
                cursor = changed.maxOfOrNull { it.seq } ?: body.cursor,
                changes = changed.map { it.entry }.toChanges(),
            )
        }
        return json(200, SyncJson.encodeToString(SyncResponse.serializer(), response))
    }

    private fun json(status: Int, text: String) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(text)

    companion object {
        /** The id the fake gives every push endpoint. */
        const val PUSH_ENDPOINT_ID = "push-endpoint-1"
    }
}
