package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.server.AUTH_RATE_LIMIT
import io.github.kanylbullen.tung.server.Accounts
import io.github.kanylbullen.tung.server.ApiJson
import io.github.kanylbullen.tung.server.Credentials
import io.github.kanylbullen.tung.server.ServerConfig
import io.github.kanylbullen.tung.server.oauth.AUTH_NONE
import io.github.kanylbullen.tung.server.oauth.AUTH_SECRET_BASIC
import io.github.kanylbullen.tung.server.oauth.AUTH_SECRET_POST
import io.github.kanylbullen.tung.server.oauth.ConsentPage
import io.github.kanylbullen.tung.server.oauth.OAuthClient
import io.github.kanylbullen.tung.server.oauth.OAuthException
import io.github.kanylbullen.tung.server.oauth.OAuthScopes
import io.github.kanylbullen.tung.server.oauth.OAuthStore
import io.github.kanylbullen.tung.server.oauth.authorizationServerMetadata
import io.github.kanylbullen.tung.server.oauth.protectedResourceMetadata
import io.github.kanylbullen.tung.server.oauth.publicBaseUrl
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.formUrlEncode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64

private const val CSRF_COOKIE = "tung_oauth_csrf"
private const val MAX_REGISTRATION_BYTES = 64L * 1024
private const val MAX_REDIRECT_URIS = 10
private const val MAX_REDIRECT_URI_LENGTH = 2000
private const val MAX_CLIENT_NAME = 100
private const val DEFAULT_CLIENT_NAME = "MCP client"
private val CHALLENGE = Regex("^[A-Za-z0-9_-]{43}$")
private val SCHEME = Regex("^[a-z][a-z0-9+.-]*$")
private val FORBIDDEN_SCHEMES = setOf("javascript", "data", "file", "vbscript", "blob", "about", "ftp", "ws", "wss")
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "[::1]", "::1")

/** RFC 7591 registration request; unknown metadata is ignored. */
@Serializable
private data class ClientRegistration(
    val redirectUris: List<String> = emptyList(),
    val clientName: String? = null,
    val tokenEndpointAuthMethod: String? = null,
    val grantTypes: List<String>? = null,
    val responseTypes: List<String>? = null,
)

/**
 * tung-server as an OAuth 2.1 authorization server for hosted MCP clients:
 * discovery (RFC 8414, RFC 9728), open dynamic client registration (RFC
 * 7591, rate limited), the authorization code flow with PKCE S256 only
 * behind a server-rendered login and consent page, and the token endpoint
 * with rotating refresh tokens. The access tokens are ordinary API tokens.
 */
fun Route.oauthRoutes(config: ServerConfig, accounts: Accounts, store: OAuthStore) {
    get("/.well-known/oauth-authorization-server") { call.respond(authorizationServerMetadata(call.publicBaseUrl(config))) }
    get("/.well-known/oauth-protected-resource") { call.respond(protectedResourceMetadata(call.publicBaseUrl(config))) }
    // RFC 9728 §3.1 puts the metadata of https://host/mcp under this path; some clients look here first.
    get("/.well-known/oauth-protected-resource/mcp") { call.respond(protectedResourceMetadata(call.publicBaseUrl(config))) }

    get("/oauth/authorize") {
        when (val checked = checkAuthorization(call.request.queryParameters, store)) {
            is Checked.DeadEnd -> call.respondPage(ConsentPage.error(checked.message), HttpStatusCode.BadRequest)
            is Checked.Refused -> call.redirectWithError(config, checked)
            is Checked.Ok -> call.respondPage(ConsentPage.render(checked.request, call.csrfToken()))
        }
    }

    rateLimit(AUTH_RATE_LIMIT) {
        post("/oauth/register") { register(call, store) }

        post("/oauth/authorize") {
            val form = call.receiveParameters()
            val cookie = call.request.cookies[CSRF_COOKIE]
            val sent = form["csrf"]
            if (cookie == null || sent == null || !MessageDigest.isEqual(cookie.toByteArray(), sent.toByteArray())) {
                call.respondPage(ConsentPage.error("This form has expired or did not come from this server."), HttpStatusCode.BadRequest)
                return@post
            }
            val request = when (val checked = checkAuthorization(form, store)) {
                is Checked.DeadEnd -> return@post call.respondPage(ConsentPage.error(checked.message), HttpStatusCode.BadRequest)
                is Checked.Refused -> return@post call.redirectWithError(config, checked)
                is Checked.Ok -> checked.request
            }
            if (form["action"] != "allow") {
                return@post call.redirectWithError(
                    config,
                    Checked.Refused(request.redirectUri, "access_denied", "The user denied access.", request.state),
                )
            }
            val email = form["email"].orEmpty()
            val user = accounts.verifyCredentials(Credentials(email, form["password"].orEmpty()))
            if (user == null) {
                call.respondPage(ConsentPage.render(request, cookie, email, "Wrong email or password."))
                return@post
            }
            val code = store.createCode(request.clientId, user.id, request.redirectUri, request.scope, request.codeChallenge, request.resource)
            val params = listOfNotNull("code" to code, request.state?.let { "state" to it }, "iss" to call.publicBaseUrl(config))
            call.respondRedirect(withQuery(request.redirectUri, params), permanent = false)
        }
    }

    post("/oauth/token") {
        if (call.request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded) {
            throw OAuthException("invalid_request", "Send the token request as application/x-www-form-urlencoded.")
        }
        val form = call.receiveParameters()
        val client = authenticateClient(call, form, store)
        val tokens = when (form["grant_type"]) {
            "authorization_code" -> store.exchangeCode(
                client,
                form["code"] ?: throw OAuthException("invalid_request", "code is missing."),
                form["redirect_uri"],
                form["code_verifier"],
            )
            "refresh_token" -> store.refresh(client, form["refresh_token"] ?: throw OAuthException("invalid_request", "refresh_token is missing."))
            null -> throw OAuthException("invalid_request", "grant_type is missing.")
            else -> throw OAuthException("unsupported_grant_type", "Only authorization_code and refresh_token are supported.")
        }
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header(HttpHeaders.Pragma, "no-cache")
        call.respond(
            buildJsonObject {
                put("access_token", tokens.accessToken)
                put("token_type", "Bearer")
                put("expires_in", tokens.expiresInSeconds)
                put("refresh_token", tokens.refreshToken)
                put("scope", tokens.scope)
            },
        )
    }
}

private sealed interface Checked {
    /** No safe place to send the user back to: shown as a page. */
    data class DeadEnd(val message: String) : Checked

    /** An error the client hears about at its redirect URI. */
    data class Refused(val redirectUri: String, val error: String, val description: String, val state: String?) : Checked

    data class Ok(val request: ConsentPage.Request) : Checked
}

/** Checks an authorization request, from the query (GET) or the consent form (POST). */
private suspend fun checkAuthorization(params: Parameters, store: OAuthStore): Checked {
    val clientId = params["client_id"] ?: return Checked.DeadEnd("The request names no app (client_id is missing).")
    val client = store.client(clientId) ?: return Checked.DeadEnd("This app is not registered with this Tung server.")
    store.markUsed(client.id)
    val redirectUri = params["redirect_uri"] ?: client.redirectUris.singleOrNull()
        ?: return Checked.DeadEnd("The request has no redirect_uri.")
    if (redirectUri !in client.redirectUris) return Checked.DeadEnd("The redirect URI is not one this app registered.")
    val state = params["state"]
    fun refuse(error: String, description: String) = Checked.Refused(redirectUri, error, description, state)
    if (params["response_type"] != "code") return refuse("unsupported_response_type", "Only response_type=code is supported.")
    val challenge = params["code_challenge"] ?: return refuse("invalid_request", "PKCE is required: send code_challenge with code_challenge_method=S256.")
    if (params["code_challenge_method"] != "S256") return refuse("invalid_request", "code_challenge_method must be S256.")
    if (!CHALLENGE.matches(challenge)) return refuse("invalid_request", "code_challenge must be a base64url SHA-256 (43 characters).")
    return Checked.Ok(
        ConsentPage.Request(
            clientId = client.id,
            clientName = client.name,
            redirectUri = redirectUri,
            state = state,
            scope = OAuthScopes.grant(params["scope"]),
            codeChallenge = challenge,
            resource = params["resource"],
        ),
    )
}

private suspend fun register(call: ApplicationCall, store: OAuthStore) {
    val bytes = call.receiveChannel().readRemaining(MAX_REGISTRATION_BYTES + 1).readByteArray()
    if (bytes.size > MAX_REGISTRATION_BYTES) throw OAuthException("invalid_client_metadata", "The registration request is too large.")
    val request = try {
        ApiJson.decodeFromString(ClientRegistration.serializer(), bytes.decodeToString())
    } catch (e: SerializationException) {
        throw OAuthException("invalid_client_metadata", "The registration request could not be read: ${e.message}")
    } catch (e: IllegalArgumentException) {
        throw OAuthException("invalid_client_metadata", "The registration request could not be read: ${e.message}")
    }
    val uris = request.redirectUris
    if (uris.isEmpty()) throw OAuthException("invalid_redirect_uri", "redirect_uris must name at least one redirect URI.")
    if (uris.size > MAX_REDIRECT_URIS) throw OAuthException("invalid_redirect_uri", "At most $MAX_REDIRECT_URIS redirect URIs.")
    uris.firstOrNull { !validRedirectUri(it) }?.let {
        throw OAuthException(
            "invalid_redirect_uri",
            "Not an acceptable redirect URI: $it. Use https, http only on localhost, or an app's own scheme; no fragment.",
        )
    }
    request.grantTypes?.let { types ->
        val unsupported = types - setOf("authorization_code", "refresh_token")
        if (unsupported.isNotEmpty()) throw OAuthException("invalid_client_metadata", "Unsupported grant_types: ${unsupported.joinToString()}.")
    }
    request.responseTypes?.let { types ->
        if (types.any { it != "code" }) throw OAuthException("invalid_client_metadata", "Only the response type code is supported.")
    }
    // RFC 7591 defaults to client_secret_basic; MCP clients are public and say none, so a missing method means none here.
    val method = request.tokenEndpointAuthMethod ?: AUTH_NONE
    if (method !in setOf(AUTH_NONE, AUTH_SECRET_POST, AUTH_SECRET_BASIC)) {
        throw OAuthException("invalid_client_metadata", "token_endpoint_auth_method must be none or client_secret_post.")
    }
    val name = request.clientName?.trim()?.take(MAX_CLIENT_NAME)?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLIENT_NAME
    val (client, secret) = store.register(name, uris, method)
    call.response.header(HttpHeaders.CacheControl, "no-store")
    call.respond(
        HttpStatusCode.Created,
        buildJsonObject {
            put("client_id", client.id)
            put("client_id_issued_at", client.createdAt / 1000)
            put("client_name", client.name)
            putJsonArray("redirect_uris") { client.redirectUris.forEach { add(it) } }
            putJsonArray("grant_types") { add("authorization_code"); add("refresh_token") }
            putJsonArray("response_types") { add("code") }
            put("token_endpoint_auth_method", client.authMethod)
            put("scope", OAuthScopes.all.joinToString(" "))
            if (secret != null) {
                put("client_secret", secret)
                put("client_secret_expires_at", 0)
            }
        },
    )
}

/**
 * https anywhere; http only to the loopback interface (native apps such as
 * Claude Code listen there, RFC 8252 §7.3); or an app's private scheme
 * (`cursor://…`, RFC 8252 §7.1). Never a fragment, never a scheme a browser
 * would run or read from disk.
 */
private fun validRedirectUri(raw: String): Boolean {
    if (raw.length > MAX_REDIRECT_URI_LENGTH) return false
    val uri = runCatching { URI(raw) }.getOrNull() ?: return false
    if (!uri.isAbsolute || uri.rawFragment != null) return false
    val scheme = uri.scheme.lowercase()
    if (!SCHEME.matches(scheme) || scheme in FORBIDDEN_SCHEMES) return false
    return when (scheme) {
        "https" -> !uri.host.isNullOrEmpty()
        "http" -> uri.host?.lowercase() in LOOPBACK_HOSTS
        else -> true
    }
}

/** client_id and, for a confidential client, its secret: from HTTP Basic or the form (client_secret_post). */
private suspend fun authenticateClient(call: ApplicationCall, form: Parameters, store: OAuthStore): OAuthClient {
    val basic = (call.request.parseAuthorizationHeader() as? HttpAuthHeader.Single)
        ?.takeIf { it.authScheme.equals("Basic", ignoreCase = true) }
        ?.let { header ->
            val decoded = runCatching { Base64.getDecoder().decode(header.blob).decodeToString() }.getOrNull()
                ?: throw OAuthException("invalid_client", "The Basic credentials could not be read.")
            val (id, secret) = decoded.split(':', limit = 2).takeIf { it.size == 2 }
                ?: throw OAuthException("invalid_client", "The Basic credentials could not be read.")
            URLDecoder.decode(id, Charsets.UTF_8) to URLDecoder.decode(secret, Charsets.UTF_8)
        }
    val formId = form["client_id"]
    if (basic != null && formId != null && formId != basic.first) throw OAuthException("invalid_request", "client_id differs between the header and the form.")
    val clientId = basic?.first ?: formId ?: throw OAuthException("invalid_client", "client_id is missing.")
    val client = store.client(clientId) ?: throw OAuthException("invalid_client", "Unknown client_id. Register the client again.")
    if (!client.isPublic && !store.secretMatches(client, basic?.second ?: form["client_secret"])) {
        throw OAuthException("invalid_client", "Wrong or missing client_secret.")
    }
    store.markUsed(client.id)
    return client
}

/** The CSRF token of this browser: a cookie only this server's pages can read back into the form (double submit). */
private fun ApplicationCall.csrfToken(): String {
    request.cookies[CSRF_COOKIE]?.takeIf { it.length >= 32 }?.let { return it }
    val token = OAuthStore.random()
    response.cookies.append(
        Cookie(
            name = CSRF_COOKIE,
            value = token,
            path = "/oauth",
            httpOnly = true,
            secure = request.origin.scheme == "https",
            extensions = mapOf("SameSite" to "Strict"),
        ),
    )
    return token
}

private suspend fun ApplicationCall.respondPage(html: String, status: HttpStatusCode = HttpStatusCode.OK) {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("X-Frame-Options", "DENY")
    response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
    response.header("Referrer-Policy", "no-referrer")
    respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

private suspend fun ApplicationCall.redirectWithError(config: ServerConfig, refused: Checked.Refused) {
    val params = listOfNotNull(
        "error" to refused.error,
        "error_description" to refused.description,
        refused.state?.let { "state" to it },
        "iss" to publicBaseUrl(config),
    )
    respondRedirect(withQuery(refused.redirectUri, params), permanent = false)
}

/** [uri] with [params] added to its query, keeping what it already has (any scheme, so not through URLBuilder). */
private fun withQuery(uri: String, params: List<Pair<String, String>>): String =
    uri + (if ('?' in uri) "&" else "?") + params.formUrlEncode()
