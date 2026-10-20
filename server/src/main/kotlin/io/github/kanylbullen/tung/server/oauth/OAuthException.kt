package io.github.kanylbullen.tung.server.oauth

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

/**
 * An error from the token or registration endpoint, answered the way RFC 6749
 * §5.2 and RFC 7591 §3.2.2 say: `{"error": "invalid_grant", "error_description": "…"}`
 * with 400, or 401 for `invalid_client`. Thrown, like `ApiException`; StatusPages answers it.
 */
class OAuthException(
    val error: String,
    val description: String,
    val status: HttpStatusCode = if (error == "invalid_client") HttpStatusCode.Unauthorized else HttpStatusCode.BadRequest,
) : RuntimeException(description)

@Serializable
data class OAuthErrorBody(val error: String, val errorDescription: String)

suspend fun ApplicationCall.respondOAuthError(e: OAuthException) {
    response.header(HttpHeaders.CacheControl, "no-store")
    if (e.status == HttpStatusCode.Unauthorized) response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"tung\"")
    respond(e.status, OAuthErrorBody(e.error, e.description))
}
