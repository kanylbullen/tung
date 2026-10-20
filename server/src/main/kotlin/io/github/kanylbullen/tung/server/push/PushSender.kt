package io.github.kanylbullen.tung.server.push

import io.github.kanylbullen.tung.core.sync.PushMessage
import io.github.kanylbullen.tung.core.sync.PushTransport
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/** How long one push may take, connecting and answering each. */
val PUSH_TIMEOUT: Duration = Duration.ofSeconds(5)

/** What became of one push. */
sealed interface PushOutcome {
    /** Accepted by FCM or the distributor. */
    data object Sent : PushOutcome

    /** The endpoint no longer exists (404/410, FCM `UNREGISTERED`): revoke it. */
    data object Gone : PushOutcome

    /** Anything else; logged and dropped, never retried. [reason] never holds the endpoint or a token. */
    data class Failed(val reason: String) : PushOutcome
}

/** Sends the empty "sync now" over one transport ([PushTransport]). */
interface PushSender {
    val transport: String

    /** False when the transport is not configured: its endpoints are kept but never sent to. */
    val enabled: Boolean

    suspend fun send(endpoint: String): PushOutcome
}

/** A transport the server is not configured for (FCM without a service account). */
class DisabledPushSender(override val transport: String) : PushSender {
    override val enabled = false

    override suspend fun send(endpoint: String): PushOutcome = PushOutcome.Failed("$transport push is not configured on this server")
}

/** The HTTP client every sender shares: the JDK's own, no redirects followed, [PUSH_TIMEOUT] to connect. */
fun pushHttpClient(): HttpClient =
    HttpClient.newBuilder()
        .connectTimeout(PUSH_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

/** The body of every push: `{"type":"sync"}`. `type` has a default, so it must be encoded explicitly. */
internal val PUSH_BODY: String = Json { encodeDefaults = true }.encodeToString(PushMessage.serializer(), PushMessage())

/**
 * UnifiedPush: the endpoint is the URL the device's distributor (ntfy or
 * another) gave it, and a push is a POST of `{"type":"sync"}` to it. 2xx is
 * sent, 404 and 410 mean the distributor dropped the endpoint, anything
 * else (or no answer within [PUSH_TIMEOUT]) failed.
 */
class UnifiedPushSender(private val http: HttpClient) : PushSender {
    override val transport = PushTransport.UNIFIED_PUSH
    override val enabled = true

    override suspend fun send(endpoint: String): PushOutcome {
        val request = try {
            HttpRequest.newBuilder(URI(endpoint))
                .timeout(PUSH_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(PUSH_BODY))
                .build()
        } catch (e: Exception) {
            return PushOutcome.Failed("the endpoint is not a usable URL")
        }
        val status = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).await().statusCode()
        } catch (e: HttpTimeoutException) {
            return PushOutcome.Failed("no answer within ${PUSH_TIMEOUT.seconds} s")
        } catch (e: IOException) {
            return PushOutcome.Failed(describe(e, endpoint))
        }
        return when (status) {
            in 200..299 -> PushOutcome.Sent
            404, 410 -> PushOutcome.Gone
            else -> PushOutcome.Failed("the distributor answered HTTP $status")
        }
    }
}

/** An I/O error as a log line, with the endpoint (a capability URL or a token) replaced by its host. */
internal fun describe(e: Throwable, endpoint: String): String {
    val message = e.message?.replace(endpoint, endpointHost(endpoint))
    return listOfNotNull(e.javaClass.simpleName, message).joinToString(": ")
}

/** What of an endpoint may be logged: the host of a URL, nothing of a token. */
fun endpointHost(endpoint: String): String =
    runCatching { URI(endpoint).host }.getOrNull() ?: "(token)"
