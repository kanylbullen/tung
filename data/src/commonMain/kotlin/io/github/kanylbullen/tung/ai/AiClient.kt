package io.github.kanylbullen.tung.ai

import io.github.kanylbullen.tung.core.ai.AiHttpRequest
import io.github.kanylbullen.tung.core.ai.aiErrorMessage
import io.github.kanylbullen.tung.data.ioDispatcher
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.io.IOException

/** How long one assistant call may take in all, connecting and answering: two minutes, as answers are not streamed and a model may think first. */
const val AI_TIMEOUT_MILLIS = 120_000L

/**
 * The assistant's HTTP client: the platform's engine (OkHttp on Android,
 * Darwin on iOS) with [timeoutMillis] for the whole call, **no retries**
 * (where the engine has a silent retry on a failed connection, it is off)
 * and **no redirects**, so the key in the header never follows a redirect to
 * another host. Separate from sync's client, whose timeouts and retries differ.
 */
expect fun aiHttpClient(timeoutMillis: Long = AI_TIMEOUT_MILLIS): HttpClient

/** Why a call gave no usable answer, in the words the screen shows ([AiService] adds the last five). */
enum class AiFailure {
    /** No model named in Settings. */
    NOT_CONFIGURED,

    /** No connection, or it broke. */
    NETWORK,

    /** No answer within [AI_TIMEOUT_MILLIS]. */
    TIMEOUT,

    /** 401 or 403: the key is wrong or lacks access. */
    UNAUTHORIZED,

    /** 404 or a redirect: the base URL or the model name is wrong. */
    NOT_FOUND,

    /** 429: rate limit or quota. */
    RATE_LIMITED,

    /** Any other 4xx: the provider refused the request; its message says why. */
    REJECTED,

    /** 5xx, or an overloaded provider. */
    SERVER,

    /** A 2xx with no text in it, and no reason given. */
    NO_ANSWER,

    /** A 2xx with no text because the token limit was reached first (`max_tokens`, `length`): the model thought it away. */
    OUT_OF_ROOM,

    /** A 2xx in which the model declined the request (`stop_reason: refusal`). */
    DECLINED,

    /** Text that is not the JSON asked for. */
    UNREADABLE,

    /** JSON, but no exercise in it is in the catalogue. */
    NOTHING_USABLE,
}

/** The outcome of one HTTP call: the body of a 2xx, or why not with the provider's own message if it sent one. */
sealed interface AiHttpResult {
    data class Ok(val body: String) : AiHttpResult
    data class Failed(val failure: AiFailure, val message: String? = null) : AiHttpResult
}

/** Sends one prepared request (`core.ai.aiHttpRequest`): one call per tap, nothing retried, nothing logged. */
class AiClient(private val http: HttpClient) {

    suspend fun send(request: AiHttpRequest): AiHttpResult = withContext(ioDispatcher) {
        try {
            val response = http.post(request.url) {
                // Content-Type travels with the body; Ktor refuses it as a plain header.
                request.headers.filterKeys { !it.equals(HttpHeaders.ContentType, ignoreCase = true) }.forEach { (name, value) -> header(name, value) }
                setBody(TextContent(request.body, ContentType.Application.Json))
            }
            val body = runCatching { response.bodyAsText() }.getOrDefault("")
            val status = response.status.value
            when {
                status in 200..299 -> AiHttpResult.Ok(body)
                status in 300..399 -> AiHttpResult.Failed(AiFailure.NOT_FOUND)
                status == 401 || status == 403 -> AiHttpResult.Failed(AiFailure.UNAUTHORIZED, aiErrorMessage(body))
                status == 404 -> AiHttpResult.Failed(AiFailure.NOT_FOUND, aiErrorMessage(body))
                status == 429 -> AiHttpResult.Failed(AiFailure.RATE_LIMITED, aiErrorMessage(body))
                status in 400..499 -> AiHttpResult.Failed(AiFailure.REJECTED, aiErrorMessage(body))
                else -> AiHttpResult.Failed(AiFailure.SERVER, aiErrorMessage(body))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpRequestTimeoutException) {
            AiHttpResult.Failed(AiFailure.TIMEOUT)
        } catch (e: ConnectTimeoutException) {
            AiHttpResult.Failed(AiFailure.TIMEOUT)
        } catch (e: SocketTimeoutException) {
            AiHttpResult.Failed(AiFailure.TIMEOUT)
        } catch (e: IOException) {
            AiHttpResult.Failed(AiFailure.NETWORK)
        } catch (e: Exception) {
            // Anything else the engine throws on a strange address or answer is a failed call, never a crash.
            AiHttpResult.Failed(AiFailure.NETWORK)
        }
    }
}
