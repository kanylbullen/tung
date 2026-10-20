package io.github.kanylbullen.tung.server

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ErrorBody(val error: ErrorDetail)

@Serializable
data class ErrorDetail(val code: String, val message: String)

/**
 * Every error the API answers with on purpose. Handlers throw it rather than
 * respond, so StatusPages turns it into `{"error": {"code", "message"}}` and the
 * status handlers below (which catch Ktor's own 401/404/405/429) leave it alone.
 */
class ApiException(val status: HttpStatusCode, val code: String, override val message: String) : RuntimeException(message)

fun badRequest(message: String, code: String = "invalid_request") = ApiException(HttpStatusCode.BadRequest, code, message)

fun notFound(what: String) = ApiException(HttpStatusCode.NotFound, "not_found", "$what not found.")

fun conflict(code: String, message: String) = ApiException(HttpStatusCode.Conflict, code, message)

fun forbidden(code: String, message: String) = ApiException(HttpStatusCode.Forbidden, code, message)

fun unauthorized(code: String, message: String) = ApiException(HttpStatusCode.Unauthorized, code, message)

fun payloadTooLarge(code: String, message: String) = ApiException(HttpStatusCode.PayloadTooLarge, code, message)

fun Application.installErrorPages() {
    install(StatusPages) {
        exception<ApiException> { call, e ->
            if (e.status == HttpStatusCode.Unauthorized) call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"tung\"")
            call.respond(e.status, ErrorBody(ErrorDetail(e.code, e.message)))
        }
        exception<BadRequestException> { call, e ->
            // Malformed JSON, a missing field, a wrong type: Ktor wraps the parser's message.
            val detail = generateSequence<Throwable>(e) { it.cause }.last().message ?: e.message
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ErrorDetail("invalid_request", "The request body could not be read: $detail")))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Unhandled error on ${call.request.local.method.value} ${call.request.local.uri}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(ErrorDetail("internal", "Something went wrong on the server.")))
        }
        // Responses Ktor makes itself, without passing through a handler.
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ErrorBody(ErrorDetail("not_found", "No such endpoint.")))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respond(status, ErrorBody(ErrorDetail("method_not_allowed", "This endpoint does not take that method.")))
        }
        status(HttpStatusCode.Unauthorized) { call, status ->
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"tung\"")
            call.respond(status, ErrorBody(ErrorDetail("unauthorized", "Send a valid API token as 'Authorization: Bearer tung_…'.")))
        }
        status(HttpStatusCode.TooManyRequests) { call, status ->
            call.respond(status, ErrorBody(ErrorDetail("rate_limited", "Too many requests. Wait a minute and try again.")))
        }
    }
}
