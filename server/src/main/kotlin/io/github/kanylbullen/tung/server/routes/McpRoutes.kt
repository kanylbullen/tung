package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.server.Accounts
import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.ServerConfig
import io.github.kanylbullen.tung.server.TokenPrincipal
import io.github.kanylbullen.tung.server.badRequest
import io.github.kanylbullen.tung.server.mcp.MCP_PROTOCOL_VERSIONS
import io.github.kanylbullen.tung.server.mcp.McpExchange
import io.github.kanylbullen.tung.server.mcp.TungMcp
import io.github.kanylbullen.tung.server.oauth.mcpChallenge
import io.github.kanylbullen.tung.server.oauth.publicBaseUrl
import io.github.kanylbullen.tung.server.payloadTooLarge
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.request.accept
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.JSONRPCRequest
import io.modelcontextprotocol.kotlin.sdk.JSONRPCResponse
import io.modelcontextprotocol.kotlin.sdk.InitializeResult
import io.modelcontextprotocol.kotlin.sdk.shared.AbstractTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.ClassDiscriminatorMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** The largest JSON-RPC body `/mcp` reads. A plan of 50 sets for a dozen exercises is a few kilobytes. */
private const val MAX_MCP_BODY_BYTES = 1L * 1024 * 1024

private const val SESSION_HEADER = "Mcp-Session-Id"
private const val PROTOCOL_HEADER = "MCP-Protocol-Version"

/** The SDK's own JSON settings (its `McpJson` is internal), so messages read and write exactly as the SDK expects. */
@OptIn(ExperimentalSerializationApi::class)
private val mcpJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    classDiscriminatorMode = ClassDiscriminatorMode.NONE
    explicitNulls = false
}

/**
 * The SDK's [io.modelcontextprotocol.kotlin.sdk.server.Server] handles a
 * message inline in the coroutine that hands it over, and answers through
 * [send] in that same coroutine. So a response belongs to the request whose
 * [McpExchange] is in the context: one transport serves every HTTP request
 * at once, and nothing is kept between them.
 */
class ExchangeTransport : AbstractTransport() {
    override suspend fun start() = Unit

    override suspend fun send(message: JSONRPCMessage) {
        currentCoroutineContext()[McpExchange]?.responses?.add(message)
    }

    override suspend fun close() = _onClose()

    suspend fun receive(message: JSONRPCMessage) = _onMessage(message)
}

/**
 * Streamable-HTTP sessions: the id handed out at `initialize`, which user it
 * belongs to and the protocol version agreed. Requests do not need one (all
 * state is in the database), but a client that sends one gets 404 once it is
 * gone, and starts over, as the transport specifies. In memory: a restart
 * forgets them, idle ones expire after [ttlMs].
 */
class McpSessions(
    private val ttlMs: Long = 24 * 3_600_000L,
    private val max: Int = 10_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Session(val userId: String, val protocolVersion: String, @Volatile var lastSeen: Long)

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun create(userId: String, protocolVersion: String): String {
        val now = clock()
        sessions.entries.removeIf { now - it.value.lastSeen > ttlMs }
        if (sessions.size >= max) sessions.entries.minByOrNull { it.value.lastSeen }?.let { sessions.remove(it.key) }
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(24).also(random::nextBytes))
        sessions[id] = Session(userId, protocolVersion, now)
        return id
    }

    /** True when [id] is a live session of [userId]; refreshes it. Another user's session is unknown, never a hint. */
    fun touch(id: String, userId: String): Boolean {
        val session = sessions[id] ?: return false
        val now = clock()
        if (session.userId != userId || now - session.lastSeen > ttlMs) {
            if (session.userId == userId) sessions.remove(id)
            return false
        }
        session.lastSeen = now
        return true
    }

    fun end(id: String, userId: String): Boolean = if (sessions[id]?.userId == userId) sessions.remove(id) != null else false
}

/**
 * MCP over streamable HTTP at `/mcp`. POST takes one JSON-RPC message or a
 * batch and answers with JSON (or the same as one SSE event, for a client
 * that accepts only `text/event-stream`); notifications alone get 202. GET
 * answers 405: the server never sends anything unasked, which the transport
 * allows. DELETE ends a session. Every request needs a bearer token; without
 * one the 401 names the OAuth metadata, which is how hosted clients find the
 * login. Bearer tokens make DNS rebinding harmless, so `Origin` is not checked.
 */
fun Route.mcpRoutes(config: ServerConfig, accounts: Accounts, mcp: TungMcp, transport: ExchangeTransport, sessions: McpSessions) {
    post("/mcp") {
        val principal = call.mcpPrincipal(config, accounts)
        call.request.headers[PROTOCOL_HEADER]?.let {
            if (it !in MCP_PROTOCOL_VERSIONS) throw badRequest("Unsupported $PROTOCOL_HEADER $it; this server speaks ${MCP_PROTOCOL_VERSIONS.joinToString()}.")
        }
        val body = call.readBody() ?: return@post call.respondRpc(HttpStatusCode.BadRequest, rpcError(null, -32700, "Parse error: the body is not JSON."))
        val items = when (body) {
            is JsonArray -> body.toList()
            else -> listOf(body)
        }
        if (items.isEmpty()) return@post call.respondRpc(HttpStatusCode.BadRequest, rpcError(null, -32600, "Invalid request: an empty batch."))
        val initializing = items.any { (it as? JsonObject)?.get("method")?.jsonPrimitive?.contentOrNull == "initialize" }
        val sessionId = call.request.headers[SESSION_HEADER]
        if (!initializing && sessionId != null && !sessions.touch(sessionId, principal.userId)) {
            throw ApiException(HttpStatusCode.NotFound, "session_not_found", "This MCP session has ended. Start a new one with initialize.")
        }

        val exchange = McpExchange(principal)
        val out = mutableListOf<JsonElement>()
        var requests = 0
        withContext(exchange) {
            for (item in items) {
                val message = runCatching { mcpJson.decodeFromJsonElement(JSONRPCMessage.serializer(), item) }.getOrNull()
                if (message == null) {
                    out += rpcError((item as? JsonObject)?.get("id"), -32600, "Invalid request: not a JSON-RPC 2.0 message.")
                    continue
                }
                if (message is JSONRPCRequest) requests++
                transport.receive(message)
            }
        }
        if (requests == 0 && out.isEmpty()) return@post call.respond(HttpStatusCode.Accepted)
        val responses = exchange.responses.map { mcpJson.encodeToJsonElement(JSONRPCMessage.serializer(), it) }
        if (initializing) {
            val version = exchange.responses.filterIsInstance<JSONRPCResponse>()
                .firstNotNullOfOrNull { (it.result as? InitializeResult)?.protocolVersion }
            if (version != null) call.response.header(SESSION_HEADER, sessions.create(principal.userId, version))
        }
        val all = responses + out
        val answer: JsonElement = if (body is JsonArray) JsonArray(all) else all.first()
        call.respondRpc(HttpStatusCode.OK, answer)
    }

    get("/mcp") {
        call.mcpPrincipal(config, accounts)
        call.response.header(HttpHeaders.Allow, "POST, DELETE")
        throw ApiException(
            HttpStatusCode.MethodNotAllowed,
            "method_not_allowed",
            "This server sends nothing unasked, so there is no event stream to open. POST JSON-RPC messages to /mcp.",
        )
    }

    delete("/mcp") {
        val principal = call.mcpPrincipal(config, accounts)
        val sessionId = call.request.headers[SESSION_HEADER] ?: throw badRequest("Send the $SESSION_HEADER to end.")
        if (!sessions.end(sessionId, principal.userId)) throw ApiException(HttpStatusCode.NotFound, "session_not_found", "No such MCP session.")
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The bearer token's principal, or 401 with the challenge that leads an MCP client to the OAuth metadata. */
private suspend fun ApplicationCall.mcpPrincipal(config: ServerConfig, accounts: Accounts): TokenPrincipal {
    val header = runCatching { request.parseAuthorizationHeader() }.getOrNull() as? HttpAuthHeader.Single
    val token = header?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }?.blob
    val principal = token?.let { accounts.authenticate(it) }
    if (principal != null) return principal
    throw ApiException(
        HttpStatusCode.Unauthorized,
        if (token == null) "unauthorized" else "invalid_token",
        if (token == null) "Connect with OAuth (see the WWW-Authenticate header) or send an API token as 'Authorization: Bearer tung_…'."
        else "The token is unknown, revoked or expired. Connect again.",
        challenge = mcpChallenge(publicBaseUrl(config), invalidToken = token != null),
    )
}

/** The body as JSON, null when it is not JSON; 413 past [MAX_MCP_BODY_BYTES]. */
private suspend fun ApplicationCall.readBody(): JsonElement? {
    val tooLarge = { payloadTooLarge("payload_too_large", "An MCP request can be at most ${MAX_MCP_BODY_BYTES / 1024} KiB.") }
    request.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { if (it > MAX_MCP_BODY_BYTES) throw tooLarge() }
    val bytes = receiveChannel().readRemaining(MAX_MCP_BODY_BYTES + 1).readByteArray()
    if (bytes.size > MAX_MCP_BODY_BYTES) throw tooLarge()
    return runCatching { mcpJson.parseToJsonElement(bytes.decodeToString()) }.getOrNull()
}

private fun rpcError(id: JsonElement?, code: Int, message: String): JsonObject = buildJsonObject {
    put("jsonrpc", "2.0")
    put("id", id ?: JsonNull)
    putJsonObject("error") {
        put("code", code)
        put("message", message)
    }
}

/** JSON, or one SSE event for a client that accepts only `text/event-stream`. */
private suspend fun ApplicationCall.respondRpc(status: HttpStatusCode, body: JsonElement) {
    val accept = request.accept().orEmpty()
    val sseOnly = "text/event-stream" in accept && "application/json" !in accept && "*/*" !in accept
    if (sseOnly) {
        response.header(HttpHeaders.CacheControl, "no-cache")
        respondText("event: message\ndata: $body\n\n", ContentType.Text.EventStream, status)
    } else {
        respondText(body.toString(), ContentType.Application.Json, status)
    }
}
