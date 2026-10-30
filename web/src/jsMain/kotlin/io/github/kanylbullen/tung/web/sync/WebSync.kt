package io.github.kanylbullen.tung.web.sync

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.SYNC_MAX_ROWS
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncPlanner
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write
import kotlin.js.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** The phone's `SyncJson`: every field on the wire, unknown ones ignored. */
val SyncJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

sealed interface SyncResult {
    data object NotSignedIn : SyncResult
    data class Success(val pushed: Int, val applied: Int) : SyncResult
    data class Failed(val failure: SyncFailure) : SyncResult
}

/**
 * One sync at a time against `POST /api/v1/sync`, the phone's
 * `SyncClient.syncOnce` over the browser's store: plan (core's
 * [SyncPlanner]: dirty rows and tombstones in chunks of at most
 * [SYNC_MAX_ROWS]) → POST each chunk with the latest cursor → apply each
 * response ([TungStore.apply], core's rules) and write its journal to
 * IndexedDB → store the cursor. While a response comes back full it pulls
 * again. Success and failure are recorded in [WebSettings]; a 401 signs the
 * browser out.
 *
 * This loop is the one piece the phone has in `data/` (`SyncClient`) and the
 * web has here; it moves into core behind a transport interface, a
 * follow-up now that `data/` is multiplatform.
 *
 * @param retryDelaysMillis the waits between attempts of one request that
 *   failed with a 5xx or could not reach the server; a 4xx is never retried.
 * @param onSignedOut called after a 401 has cleared the token.
 */
class WebSync(
    private val store: TungStore,
    private val db: IndexedDbStore,
    private val settings: WebSettings,
    private val clock: () -> Long = { Date.now().toLong() },
    private val retryDelaysMillis: List<Long> = listOf(2_000L, 8_000L),
    private val onSignedOut: suspend () -> Unit = {},
    /** The transport, the browser's `fetch` by default; a test hands in its own. */
    private val post: PostJson = ::postJson,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)

    /** True while a sync is in progress, for the screen's status line. */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    suspend fun syncOnce(): SyncResult = mutex.withLock {
        _running.value = true
        try {
            runSync()
        } finally {
            _running.value = false
        }
    }

    private suspend fun runSync(): SyncResult {
        val baseUrl = settings.account.value?.serverUrl ?: return SyncResult.NotSignedIn
        val token = settings.token ?: return SyncResult.NotSignedIn
        return try {
            val local = store.localChanges()
            var cursor = settings.syncState.value.cursor
            var pushed = 0
            var applied = 0
            var last: SyncResponse? = null
            suspend fun exchange(request: SyncRequest) {
                val response = postSync(baseUrl, token, request.copy(cursor = cursor))
                val journal = ArrayList<Write>()
                applied += store.apply(response, request.changes, journal)
                db.write(journal)
                store.bump()
                pushed += request.changes.size()
                cursor = maxOf(cursor, response.cursor)
                settings.setCursor(cursor)
                last = response
            }
            SyncPlanner.plan(local.dirty, local.tombstones, cursor).forEach { exchange(it) }
            while ((last?.changes?.size() ?: 0) >= SYNC_MAX_ROWS) exchange(SyncRequest())
            settings.recordSuccess(clock())
            SyncResult.Success(pushed, applied)
        } catch (failed: SyncFailedException) {
            if (failed.failure == SyncFailure.SIGNED_OUT) {
                settings.signedOut(SyncFailure.SIGNED_OUT)
                onSignedOut()
            } else {
                settings.recordFailure(failed.failure)
            }
            SyncResult.Failed(failed.failure)
        }
    }

    private suspend fun postSync(baseUrl: String, token: String, request: SyncRequest): SyncResponse {
        val deviceId = settings.deviceId()
        val body = SyncJson.encodeToString(SyncRequest.serializer(), request)
        var attempt = 0
        while (true) {
            val failure = try {
                val reply = post(
                    baseUrl + SYNC_PATH,
                    body,
                    mapOf("Authorization" to "Bearer $token", DEVICE_HEADER to deviceId),
                )
                when {
                    reply.isSuccess -> return SyncJson.decodeFromString(SyncResponse.serializer(), reply.body)
                    reply.status == 401 -> throw SyncFailedException(SyncFailure.SIGNED_OUT)
                    reply.status in 400..499 -> throw SyncFailedException(SyncFailure.REJECTED)
                    else -> SyncFailure.SERVER
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncFailedException) {
                throw e
            } catch (e: HttpUnreachable) {
                SyncFailure.NETWORK
            } catch (e: Exception) {
                // A body that is not a SyncResponse: the server's fault, and maybe passing.
                SyncFailure.SERVER
            }
            val wait = retryDelaysMillis.getOrNull(attempt++) ?: throw SyncFailedException(failure)
            delay(wait)
        }
    }

    private class SyncFailedException(val failure: SyncFailure) : Exception(failure.key)
}
