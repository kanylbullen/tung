package io.github.kanylbullen.tung.server.oauth

import io.github.kanylbullen.tung.server.ServerConfig
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The server's public base URL, which is also the OAuth issuer:
 * `TUNG_PUBLIC_URL` when set (it must be, in production: clients compare the
 * issuer they discover with the one they were given), else what this request
 * reached, which is right for a local test and wrong behind a proxy that
 * does not forward the host.
 */
fun ApplicationCall.publicBaseUrl(config: ServerConfig): String {
    config.publicUrl?.let { return it }
    val origin = request.origin
    val defaultPort = (origin.scheme == "https" && origin.serverPort == 443) || (origin.scheme == "http" && origin.serverPort == 80)
    return "${origin.scheme}://${origin.serverHost}${if (defaultPort) "" else ":${origin.serverPort}"}"
}

/** The MCP endpoint, the protected resource the tokens are for. */
fun mcpResource(base: String) = "$base/mcp"

/** RFC 8414 authorization server metadata. */
fun authorizationServerMetadata(base: String): JsonObject = buildJsonObject {
    put("issuer", base)
    put("authorization_endpoint", "$base/oauth/authorize")
    put("token_endpoint", "$base/oauth/token")
    put("registration_endpoint", "$base/oauth/register")
    putJsonArray("response_types_supported") { add("code") }
    putJsonArray("response_modes_supported") { add("query") }
    putJsonArray("grant_types_supported") { add("authorization_code"); add("refresh_token") }
    putJsonArray("code_challenge_methods_supported") { add("S256") }
    putJsonArray("token_endpoint_auth_methods_supported") { add(AUTH_NONE); add(AUTH_SECRET_POST) }
    putJsonArray("scopes_supported") { OAuthScopes.all.forEach { add(it) } }
    put("authorization_response_iss_parameter_supported", true)
    put("service_documentation", "$base/docs")
}

/** RFC 9728 protected resource metadata for `/mcp`. */
fun protectedResourceMetadata(base: String): JsonObject = buildJsonObject {
    put("resource", mcpResource(base))
    putJsonArray("authorization_servers") { add(base) }
    putJsonArray("scopes_supported") { OAuthScopes.all.forEach { add(it) } }
    putJsonArray("bearer_methods_supported") { add("header") }
    put("resource_name", "Tung")
    put("resource_documentation", "$base/docs")
}

/** The `WWW-Authenticate` challenge of a 401 from `/mcp`: where the client finds out how to get a token. */
fun mcpChallenge(base: String, invalidToken: Boolean): String =
    "Bearer resource_metadata=\"$base/.well-known/oauth-protected-resource\"" + if (invalidToken) ", error=\"invalid_token\"" else ""
