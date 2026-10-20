package io.github.kanylbullen.tung.server.push

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Told after every write that changed a user's data, by whom: the device id
 * of the client ([DEVICE_HEADER]), or null for a client without one (a
 * script, an MCP tool), which counts as different from every device.
 */
fun interface ChangeNotifier {
    fun changed(userId: String, sourceDeviceId: String?)

    companion object {
        val NONE = ChangeNotifier { _, _ -> }
    }
}

/** The calling client's device id, from [DEVICE_HEADER]; null when absent or unusable. */
fun ApplicationCall.sourceDevice(): String? =
    request.headers[DEVICE_HEADER]?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_DEVICE_ID }

/**
 * Push (issue #34): after a change, every other device of the user gets an
 * empty "sync now" on its registered endpoint, never content.
 *
 * - **Debounced per user.** The first change starts a [debounceMillis]
 *   window; changes inside it are folded into the same batch, which keeps
 *   the set of devices the changes came from. When the window closes, every
 *   active endpoint is sent to except one whose device made *every* change
 *   in the batch (it already has them). A change without a device id counts
 *   as nobody's, so then every endpoint is sent to.
 * - **One send in flight per endpoint.** If the previous push to an
 *   endpoint is still running when the next batch fires, the endpoint is
 *   skipped: the push in flight already says "sync now".
 * - **Outcomes.** Sent stamps `last_used_at`; gone (404/410, FCM
 *   `UNREGISTERED`) revokes the endpoint; failed is logged at warn and
 *   dropped, never retried. Only an endpoint's id, transport and URL host
 *   are logged, never the URL (a capability) or an FCM token.
 * - Endpoints of a transport whose sender is disabled (FCM without a
 *   service account) are kept and skipped.
 *
 * Runs on its own [scope], so a request never waits for it; [close] cancels
 * it when the application stops.
 */
class PushService(
    private val store: PushStore,
    senders: List<PushSender>,
    private val debounceMillis: Long,
) : ChangeNotifier {

    private val log = LoggerFactory.getLogger(PushService::class.java)
    private val senders: Map<String, PushSender> = senders.associateBy { it.transport }
    private val job = SupervisorJob()
    private val scope = CoroutineScope(
        job + Dispatchers.IO + CoroutineExceptionHandler { _, e -> log.warn("Push failed unexpectedly", e) },
    )

    /** Pending batches: user id to the source devices of the changes folded in so far. */
    private val pending = HashMap<String, MutableSet<String?>>()

    /** Endpoints with a push in flight, as `userId/endpointId`. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        val fcm = this.senders[PushTransport.FCM]
        if (fcm == null || !fcm.enabled) {
            log.info("FCM push is off: TUNG_FCM_SERVICE_ACCOUNT_JSON is not set. Endpoints with transport fcm are kept but not sent to.")
        }
    }

    override fun changed(userId: String, sourceDeviceId: String?) {
        synchronized(pending) {
            pending[userId]?.let {
                it += sourceDeviceId
                return
            }
            pending[userId] = mutableSetOf(sourceDeviceId)
        }
        scope.launch {
            delay(debounceMillis)
            val sources = synchronized(pending) { pending.remove(userId) } ?: return@launch
            deliver(userId, sources)
        }
    }

    private suspend fun deliver(userId: String, sources: Set<String?>) {
        val targets = store.targets(userId).filterNot { target -> sources.all { it == target.deviceId } }
        for (target in targets) {
            val sender = senders[target.transport]?.takeIf { it.enabled } ?: continue
            val key = "$userId/${target.id}"
            if (!inFlight.add(key)) continue
            scope.launch {
                try {
                    send(userId, target, sender)
                } finally {
                    inFlight.remove(key)
                }
            }
        }
    }

    private suspend fun send(userId: String, target: Target, sender: PushSender) {
        val outcome = try {
            sender.send(target.endpoint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PushOutcome.Failed(describe(e, target.endpoint))
        }
        when (outcome) {
            PushOutcome.Sent -> store.markUsed(userId, target)
            PushOutcome.Gone -> {
                log.info("Push endpoint {} ({}) is gone; revoked", target.id, target.logName())
                store.markGone(userId, target)
            }
            is PushOutcome.Failed -> log.warn("Push to endpoint {} ({}) failed: {}", target.id, target.logName(), outcome.reason)
        }
    }

    /** Waits until no batch is pending and no push is running; for tests. */
    suspend fun awaitIdle() {
        while (true) {
            val running = job.children.toList()
            val waiting = synchronized(pending) { pending.isNotEmpty() }
            if (running.isEmpty() && !waiting) return
            if (running.isEmpty()) delay(5) else running.joinAll()
        }
    }

    fun close() = scope.cancel()
}

/** What of an endpoint may be logged: the transport, and the host of a UnifiedPush URL. Never the URL or a token. */
private fun Target.logName(): String =
    if (transport == PushTransport.UNIFIED_PUSH) "$transport at ${endpointHost(endpoint)}" else transport
