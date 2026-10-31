package io.github.kanylbullen.tung.web.sync

import kotlinx.browser.window
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import org.w3c.fetch.Headers
import org.w3c.fetch.RequestInit

/** What the server answered: the status and the body as text. */
class HttpReply(val status: Int, val body: String) {
    val isSuccess: Boolean get() = status in 200..299
}

/** `fetch` failed before an answer: no connection, refused, blocked by CORS, or [REQUEST_TIMEOUT_MILLIS] passed. */
class HttpUnreachable(cause: Throwable?) : RuntimeException("unreachable", cause)

/** How long one request may take, the phone's `requestTimeoutMillis`; after it the request is aborted and counts as unreachable. */
const val REQUEST_TIMEOUT_MILLIS = 120_000

/** The one way the web talks to a server: a `POST` with a JSON body, so a test can hand in its own. */
typealias PostJson = suspend (url: String, body: String, headers: Map<String, String>) -> HttpReply

/** A `GET`, for the few reads before signing in (`/api/v1/auth/methods`); a test hands in its own. */
typealias GetJson = suspend (url: String) -> HttpReply

/** One `GET` through `fetch`, with [postJson]'s timeout and the caller's [headers]; no answer at all is [HttpUnreachable]. */
suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): HttpReply = send("GET", url, null, headers)

/** One `PATCH` with a JSON body, as [postJson]. */
suspend fun patchJson(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpReply =
    send("PATCH", url, body, headers + ("Content-Type" to "application/json"))

/**
 * A `GET` of a file the page shows but cannot link (the request needs the
 * token), as an object URL (`blob:`) for an `<img>`; null when the server
 * answered no file. The caller revokes it with `URL.revokeObjectURL`.
 */
suspend fun fetchObjectUrl(url: String, headers: Map<String, String>): String? {
    val requestHeaders = Headers().apply { headers.forEach { (name, value) -> append(name, value) } }
    val response = try {
        window.fetch(url, RequestInit(method = "GET", headers = requestHeaders)).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw HttpUnreachable(e)
    }
    if (!response.ok) return null
    val blob = try {
        response.blob().await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw HttpUnreachable(e)
    }
    return org.w3c.dom.url.URL.createObjectURL(blob)
}

/** One `DELETE` through `fetch`, as [getJson]. */
suspend fun deleteRequest(url: String, headers: Map<String, String> = emptyMap()): HttpReply = send("DELETE", url, null, headers)

/** The browser's `AbortController`, as far as [postJson] needs it. */
private external class AbortController {
    val signal: dynamic
    fun abort()
}

/**
 * One `POST` with a JSON body through the browser's `fetch`. The headers are
 * the caller's (the bearer token, the device id); a failure to reach the
 * server at all, or a request that takes longer than [REQUEST_TIMEOUT_MILLIS],
 * is [HttpUnreachable], and any answer is returned as it came.
 */
suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpReply =
    send("POST", url, body, headers + ("Content-Type" to "application/json"))

private suspend fun send(method: String, url: String, body: String?, headers: Map<String, String>): HttpReply {
    val requestHeaders = Headers().apply { headers.forEach { (name, value) -> append(name, value) } }
    val controller = AbortController()
    val timeout = window.setTimeout({ controller.abort() }, REQUEST_TIMEOUT_MILLIS)
    try {
        val init = RequestInit(method = method, headers = requestHeaders).also {
            if (body != null) it.body = body
            it.asDynamic().signal = controller.signal
        }
        val response = try {
            window.fetch(url, init).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw HttpUnreachable(e)
        }
        val text = try {
            response.text().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw HttpUnreachable(e)
        }
        return HttpReply(response.status.toInt(), text)
    } finally {
        window.clearTimeout(timeout)
    }
}
