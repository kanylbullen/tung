package io.github.kanylbullen.tung.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a device has to push: dirty rows and dirty tombstones, as wire rows. */
data class LocalChanges(val dirty: SyncChanges, val tombstones: SyncChanges)

sealed interface SyncResult {
    /** [pushed] rows went up, [applied] rows of the responses changed something here. */
    data class Success(val pushed: Int, val applied: Int) : SyncResult

    /** No server or no token: nothing to do. */
    data object NotSignedIn : SyncResult

    data class Failed(val failure: SyncFailure) : SyncResult
}

/** The device's log, as sync sees it: Room on the phone and the watch, memory and IndexedDB in the browser. */
interface SyncLocalStore {
    /** Everything dirty, rows and tombstones, read consistently. */
    suspend fun readDirty(): LocalChanges

    /**
     * Merges [response] (core's [SyncApplier]) and clears the dirty flags of
     * [pushed], the request it answers, only where a row still has the
     * `updatedAt` it went with; in one transaction. Returns how many rows of
     * the response changed something here.
     */
    suspend fun apply(response: SyncResponse, pushed: SyncChanges): Int
}

/** Where the device is signed in, and what sync records about itself. */
interface SyncSession {
    /** The server's base URL, or null when signed out. */
    suspend fun serverUrl(): String?

    /** The session token, or null when signed out. */
    suspend fun token(): String?

    /** The device's stable id for [DEVICE_HEADER]. */
    suspend fun deviceId(): String

    suspend fun cursor(): Long

    suspend fun setCursor(cursor: Long)

    suspend fun recordSuccess(at: Long)

    suspend fun recordFailure(failure: SyncFailure)

    /** The server answered 401: forget the token and record [SyncFailure.SIGNED_OUT]. */
    suspend fun signedOutByServer()
}

/** What one `POST /api/v1/sync` came back with, before the loop decides what it means. */
sealed interface SyncReply {
    data class Answer(val response: SyncResponse) : SyncReply

    /** An answer that was not a success; the body is not read. */
    data class Status(val code: Int) : SyncReply

    /** A success whose body was not a [SyncResponse]. */
    data object Unreadable : SyncReply

    /** No answer at all: no connection, refused, timed out. */
    data object Unreachable : SyncReply
}

/** One `POST /api/v1/sync` with the bearer [token] and [DEVICE_HEADER] = [deviceId]: Ktor on a device, `fetch` in the browser. */
fun interface SyncTransport {
    suspend fun post(baseUrl: String, token: String, deviceId: String, request: SyncRequest): SyncReply
}

/**
 * One sync at a time, the same on every client: plan ([SyncPlanner]: dirty
 * rows and tombstones in chunks of at most [SYNC_MAX_ROWS]) → POST each
 * chunk with the latest cursor → apply each response ([SyncLocalStore.apply])
 * → store the cursor. While a response comes back full it pulls again, so a
 * server that pages its answer is read to the end. Success and failure are
 * recorded in the [SyncSession]; a 401 signs the device out.
 *
 * A 5xx, an unreadable answer and no answer are retried after each of
 * [retryDelaysMillis]; any other 4xx is never retried.
 */
class SyncLoop(
    private val local: SyncLocalStore,
    private val session: SyncSession,
    private val transport: SyncTransport,
    private val clock: () -> Long,
    private val retryDelaysMillis: List<Long> = listOf(2_000L, 8_000L),
    private val onSignedOut: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)

    /** True while a sync is in progress, for a status line. */
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
        val baseUrl = session.serverUrl() ?: return SyncResult.NotSignedIn
        val token = session.token() ?: return SyncResult.NotSignedIn
        return try {
            val changes = local.readDirty()
            var cursor = session.cursor()
            var pushed = 0
            var applied = 0
            var last: SyncResponse? = null
            suspend fun exchange(request: SyncRequest) {
                val response = post(baseUrl, token, request.copy(cursor = cursor))
                applied += local.apply(response, request.changes)
                pushed += request.changes.size()
                cursor = maxOf(cursor, response.cursor)
                session.setCursor(cursor)
                last = response
            }
            SyncPlanner.plan(changes.dirty, changes.tombstones, cursor).forEach { exchange(it) }
            while ((last?.changes?.size() ?: 0) >= SYNC_MAX_ROWS) exchange(SyncRequest())
            session.recordSuccess(clock())
            SyncResult.Success(pushed, applied)
        } catch (failed: SyncFailedException) {
            if (failed.failure == SyncFailure.SIGNED_OUT) {
                session.signedOutByServer()
                onSignedOut()
            } else {
                session.recordFailure(failed.failure)
            }
            SyncResult.Failed(failed.failure)
        }
    }

    private suspend fun post(baseUrl: String, token: String, request: SyncRequest): SyncResponse {
        val deviceId = session.deviceId()
        var attempt = 0
        while (true) {
            val reply = try {
                transport.post(baseUrl, token, deviceId, request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncReply.Unreachable
            }
            val failure = when (reply) {
                is SyncReply.Answer -> return reply.response
                is SyncReply.Status -> when (reply.code) {
                    401 -> throw SyncFailedException(SyncFailure.SIGNED_OUT)
                    in 400..499 -> throw SyncFailedException(SyncFailure.REJECTED)
                    else -> SyncFailure.SERVER
                }
                SyncReply.Unreadable -> SyncFailure.SERVER
                SyncReply.Unreachable -> SyncFailure.NETWORK
            }
            val wait = retryDelaysMillis.getOrNull(attempt++) ?: throw SyncFailedException(failure)
            delay(wait)
        }
    }

    private class SyncFailedException(val failure: SyncFailure) : Exception(failure.key)
}
