package io.github.kanylbullen.tung.web.sync

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.LocalChanges
import io.github.kanylbullen.tung.core.sync.SYNC_PATH
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncFailure
import io.github.kanylbullen.tung.core.sync.SyncLocalStore
import io.github.kanylbullen.tung.core.sync.SyncLoop
import io.github.kanylbullen.tung.core.sync.SyncReply
import io.github.kanylbullen.tung.core.sync.SyncRequest
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncResult
import io.github.kanylbullen.tung.core.sync.SyncSession
import io.github.kanylbullen.tung.core.sync.SyncTransport
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write
import kotlin.js.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

/** The phone's `SyncJson`: every field on the wire, unknown ones ignored. */
val SyncJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Sync in the browser: core's [SyncLoop] (the phone's and the watch's too)
 * over this browser's store, its settings and `fetch`. Each response is
 * merged in memory ([TungStore.apply]) and its journal written to IndexedDB
 * in one transaction before the cursor moves.
 *
 * @param retryDelaysMillis the waits between attempts of one request that
 *   failed with a 5xx or could not reach the server; a 4xx is never retried.
 * @param onSignedOut called after a 401 has cleared the token.
 * @param post the transport, the browser's `fetch` by default; a test hands in its own.
 */
class WebSync(
    store: TungStore,
    db: IndexedDbStore,
    settings: WebSettings,
    clock: () -> Long = { Date.now().toLong() },
    retryDelaysMillis: List<Long> = listOf(2_000L, 8_000L),
    onSignedOut: suspend () -> Unit = {},
    post: PostJson = ::postJson,
) {
    private val loop = SyncLoop(
        local = object : SyncLocalStore {
            override suspend fun readDirty(): LocalChanges = store.localChanges()
            override suspend fun apply(response: SyncResponse, pushed: SyncChanges): Int {
                val journal = ArrayList<Write>()
                val changed = store.apply(response, pushed, journal)
                db.write(journal)
                store.bump()
                return changed
            }
        },
        session = object : SyncSession {
            override suspend fun serverUrl() = settings.account.value?.serverUrl
            override suspend fun token() = settings.token
            override suspend fun deviceId() = settings.deviceId()
            override suspend fun cursor() = settings.syncState.value.cursor
            override suspend fun setCursor(cursor: Long) = settings.setCursor(cursor)
            override suspend fun recordSuccess(at: Long) = settings.recordSuccess(at)
            override suspend fun recordFailure(failure: SyncFailure) = settings.recordFailure(failure)
            override suspend fun signedOutByServer() = settings.signedOut(SyncFailure.SIGNED_OUT)
        },
        transport = SyncTransport { baseUrl, token, deviceId, request ->
            val reply = try {
                post(
                    baseUrl + SYNC_PATH,
                    SyncJson.encodeToString(SyncRequest.serializer(), request),
                    mapOf("Authorization" to "Bearer $token", DEVICE_HEADER to deviceId),
                )
            } catch (e: HttpUnreachable) {
                return@SyncTransport SyncReply.Unreachable
            }
            if (!reply.isSuccess) return@SyncTransport SyncReply.Status(reply.status)
            try {
                SyncReply.Answer(SyncJson.decodeFromString(SyncResponse.serializer(), reply.body))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncReply.Unreadable
            }
        },
        clock = clock,
        retryDelaysMillis = retryDelaysMillis,
        onSignedOut = onSignedOut,
    )

    /** True while a sync is in progress, for the screen's status line. */
    val running: StateFlow<Boolean> = loop.running

    suspend fun syncOnce(): SyncResult = loop.syncOnce()
}
