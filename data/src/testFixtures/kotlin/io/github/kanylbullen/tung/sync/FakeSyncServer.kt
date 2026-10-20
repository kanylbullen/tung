package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SYNC_PATH
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

    /** Every sync request as received, before any auth check. */
    val syncRequests = CopyOnWriteArrayList<SyncRequest>()
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

    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
        "/api/v1/auth/login", "/api/v1/auth/register" -> {
            authPaths += request.path
            json(if (request.path.orEmpty().endsWith("register")) 201 else 200, """{"token":"$token","user":{"id":"u","email":"lifter@example.org","created_at":0}}""")
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
}
