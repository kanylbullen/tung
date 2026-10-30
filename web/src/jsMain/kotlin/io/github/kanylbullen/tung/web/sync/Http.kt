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

/** `fetch` failed before an answer: no connection, refused, or blocked by CORS. */
class HttpUnreachable(cause: Throwable?) : RuntimeException("unreachable", cause)

/**
 * One `POST` with a JSON body through the browser's `fetch`. The headers are
 * the caller's (the bearer token, the device id); a failure to reach the
 * server at all is [HttpUnreachable], any answer is returned as it came.
 */
suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpReply {
    val requestHeaders = Headers().apply {
        append("Content-Type", "application/json")
        headers.forEach { (name, value) -> append(name, value) }
    }
    val response = try {
        window.fetch(url, RequestInit(method = "POST", headers = requestHeaders, body = body)).await()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        throw HttpUnreachable(e)
    }
    val text = response.text().await()
    return HttpReply(response.status.toInt(), text)
}
