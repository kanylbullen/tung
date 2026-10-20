package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.core.command.CommandNames
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandWire
import io.github.kanylbullen.tung.core.command.WireDecoding
import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.BEARER
import io.github.kanylbullen.tung.server.TokenPrincipal
import io.github.kanylbullen.tung.server.Tokens
import io.github.kanylbullen.tung.server.badRequest
import io.github.kanylbullen.tung.server.command.CommandService
import io.github.kanylbullen.tung.server.forbidden
import io.github.kanylbullen.tung.server.payloadTooLarge
import io.github.kanylbullen.tung.server.push.sourceDevice
import io.github.kanylbullen.tung.server.unauthorized
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The largest command body: a plan of 50 sets for a dozen exercises is a few kilobytes. */
private const val MAX_COMMAND_BODY_BYTES = 256L * 1024

/**
 * `POST /api/v1/commands/{name}`: core's command surface one to one under
 * its wire names (`CommandNames`), with `CommandWire`'s JSON, executed by
 * the server-side command handler, the same one the MCP tools use. Answers
 * the `CommandResult`: 200 for done and needs_answers, 422 for rejected.
 * Needs a write token, like every other write.
 */
fun Route.commandRoutes(commands: CommandService) {
    route("/api/v1") {
        authenticate(BEARER) {
            post("/commands/{name}") {
                val principal = call.principal<TokenPrincipal>()
                    ?: throw unauthorized("unauthorized", "Send a valid API token as 'Authorization: Bearer tung_…'.")
                if (principal.scope != Tokens.SCOPE_WRITE) {
                    throw forbidden("insufficient_scope", "This token is read-only. Use a token with scope 'write' to run commands.")
                }
                val name = call.parameters["name"].orEmpty()
                if (CommandNames.classOf(name) == null) {
                    throw ApiException(
                        HttpStatusCode.NotFound,
                        "unknown_command",
                        "There is no command called '$name'. The commands are: ${CommandWire.names.joinToString()}.",
                    )
                }
                val command = when (val decoded = CommandWire.decode(name, call.readCommandBody())) {
                    is WireDecoding.Invalid -> throw badRequest(decoded.reasons.joinToString("; ") + ".", "invalid_command")
                    is WireDecoding.Decoded -> decoded.command
                }
                val result = commands.handlerFor(principal.userId, call.sourceDevice()).execute(command)
                call.respondText(
                    CommandWire.encode(result).toString(),
                    ContentType.Application.Json,
                    if (result is CommandResult.Rejected) HttpStatusCode.UnprocessableEntity else HttpStatusCode.OK,
                )
            }
        }
    }
}

/** The body as a JSON object; an empty body is `{}` (commands without fields). */
private suspend fun ApplicationCall.readCommandBody(): JsonObject {
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
        if (it > MAX_COMMAND_BODY_BYTES) throw payloadTooLarge("payload_too_large", "A command can be at most ${MAX_COMMAND_BODY_BYTES / 1024} KiB.")
    }
    val bytes = receiveChannel().readRemaining(MAX_COMMAND_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_COMMAND_BODY_BYTES) throw payloadTooLarge("payload_too_large", "A command can be at most ${MAX_COMMAND_BODY_BYTES / 1024} KiB.")
    val text = bytes.decodeToString()
    if (text.isBlank()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
        ?: throw badRequest("The body must be a JSON object with the command's fields.")
}
