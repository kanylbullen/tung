package io.github.kanylbullen.tung.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

const val REDIRECT = "https://claude.example/api/mcp/auth_callback"

/** A PKCE verifier and its S256 challenge. */
class Pkce {
    val verifier: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    val challenge: String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/** The browser and the MCP client of one OAuth flow, against the test application. */
class OAuthDriver(private val test: ApplicationTestBuilder) {
    val http: HttpClient = test.createClient { followRedirects = false }
    val email = uniqueEmail()
    val password = "correct horse battery"

    suspend fun account(): String = http.register(email, password)

    suspend fun registerClient(name: String = "Test client", extra: String = ""): String {
        val response = http.post("/oauth/register") {
            jsonBody("""{"client_name": "$name", "redirect_uris": ["$REDIRECT"], "token_endpoint_auth_method": "none"$extra}""")
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json()["client_id"]!!.str
    }

    fun authorizeUrl(clientId: String, pkce: Pkce, redirect: String = REDIRECT, scope: String? = "read write", state: String = "st-123"): String =
        "/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=${redirect.encodeURLParameter()}" +
            "&code_challenge=${pkce.challenge}&code_challenge_method=S256&state=$state" +
            (scope?.let { "&scope=${it.encodeURLParameter()}" } ?: "") +
            "&resource=${"http://localhost/mcp".encodeURLParameter()}"

    /** Opens the consent page and submits it; returns the POST's response (a redirect on success). */
    suspend fun consent(
        clientId: String,
        pkce: Pkce,
        action: String = "allow",
        password: String = this.password,
        scope: String? = "read write",
        csrfOverride: String? = null,
    ): HttpResponse {
        val page = http.get(authorizeUrl(clientId, pkce, scope = scope))
        assertEquals(HttpStatusCode.OK, page.status, page.bodyAsText())
        val html = page.bodyAsText()
        val cookie = page.headers.getAll(HttpHeaders.SetCookie).orEmpty().single().substringBefore(';')
        fun hidden(name: String) = Regex("""name="$name" value="([^"]*)"""").find(html)?.groupValues?.get(1)
        val form = Parameters.build {
            listOf("response_type", "client_id", "redirect_uri", "state", "scope", "code_challenge", "code_challenge_method", "resource").forEach { key ->
                hidden(key)?.let { append(key, it.replace("&amp;", "&")) }
            }
            append("csrf", csrfOverride ?: hidden("csrf")!!)
            append("email", email)
            append("password", password)
            append("action", action)
        }
        return http.post("/oauth/authorize") {
            header(HttpHeaders.Cookie, cookie)
            setBody(FormDataContent(form))
        }
    }

    /** The full browser half: consent, then the code from the redirect. */
    suspend fun code(clientId: String, pkce: Pkce, scope: String? = "read write"): String {
        val response = consent(clientId, pkce, scope = scope)
        assertEquals(HttpStatusCode.Found, response.status, response.bodyAsText())
        val location = Url(response.headers[HttpHeaders.Location]!!)
        assertEquals(REDIRECT, location.toString().substringBefore('?'))
        assertEquals("st-123", location.parameters["state"])
        assertEquals("http://localhost", location.parameters["iss"])
        return location.parameters["code"]!!
    }

    suspend fun token(vararg fields: Pair<String, String>): HttpResponse =
        http.post("/oauth/token") { setBody(FormDataContent(Parameters.build { fields.forEach { (k, v) -> append(k, v) } })) }

    suspend fun exchange(clientId: String, code: String, verifier: String, redirect: String = REDIRECT): HttpResponse =
        token("grant_type" to "authorization_code", "code" to code, "redirect_uri" to redirect, "client_id" to clientId, "code_verifier" to verifier)

    suspend fun refresh(clientId: String, refreshToken: String): HttpResponse =
        token("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId)

    /** Register, consent, exchange: the tokens of a fresh grant. */
    suspend fun grant(clientId: String, scope: String? = "read write"): Pair<String, String> {
        val pkce = Pkce()
        val response = exchange(clientId, code(clientId, pkce, scope), pkce.verifier)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = response.json()
        return body["access_token"]!!.str to body["refresh_token"]!!.str
    }
}

private suspend fun HttpClient.mcpInitialize(token: String): HttpResponse = post("/mcp") {
    bearerAuth(token)
    header(HttpHeaders.Accept, "application/json, text/event-stream")
    jsonBody("""{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "1"}}}""")
}

class OAuthTest {

    @Test
    fun metadataAdvertisesTheFlow() = tungTest {
        val server = client.get("/.well-known/oauth-authorization-server").json()
        assertEquals("http://localhost", server["issuer"]!!.str)
        assertEquals("http://localhost/oauth/authorize", server["authorization_endpoint"]!!.str)
        assertEquals("http://localhost/oauth/token", server["token_endpoint"]!!.str)
        assertEquals("http://localhost/oauth/register", server["registration_endpoint"]!!.str)
        assertEquals(listOf("S256"), server["code_challenge_methods_supported"]!!.jsonArray.map { it.str })
        assertEquals(listOf("authorization_code", "refresh_token"), server["grant_types_supported"]!!.jsonArray.map { it.str })
        assertEquals(listOf("none", "client_secret_post"), server["token_endpoint_auth_methods_supported"]!!.jsonArray.map { it.str })
        assertEquals(listOf("read", "write"), server["scopes_supported"]!!.jsonArray.map { it.str })

        for (path in listOf("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp")) {
            val resource = client.get(path).json()
            assertEquals("http://localhost/mcp", resource["resource"]!!.str)
            assertEquals(listOf("http://localhost"), resource["authorization_servers"]!!.jsonArray.map { it.str })
        }
    }

    @Test
    fun theFullCodeFlowWithPkceGivesAWorkingApiToken() = tungTest {
        val oauth = OAuthDriver(this)
        val session = oauth.account()
        val clientId = oauth.registerClient()

        val page = oauth.http.get(oauth.authorizeUrl(clientId, Pkce()))
        assertTrue(page.bodyAsText().contains("Connect Test client to Tung"))
        assertTrue(page.bodyAsText().contains("read and change your training log"))
        assertEquals("DENY", page.headers["X-Frame-Options"])

        val pkce = Pkce()
        val code = oauth.code(clientId, pkce)
        val response = oauth.exchange(clientId, code, pkce.verifier)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        val tokens = response.json()
        val access = tokens["access_token"]!!.str
        assertTrue(access.startsWith("tung_"))
        assertTrue(tokens["refresh_token"]!!.str.startsWith("tungr_"))
        assertEquals("Bearer", tokens["token_type"]!!.str)
        assertEquals("read write", tokens["scope"]!!.str)
        assertEquals(30L * 24 * 3600, tokens["expires_in"]!!.jsonPrimitive.content.toLong())

        assertEquals(HttpStatusCode.OK, oauth.http.mcpInitialize(access).status, "the token opens /mcp")
        val me = oauth.http.authedGet("/api/v1/me", access)
        assertEquals(HttpStatusCode.OK, me.status, "and the REST API")
        assertEquals(oauth.email, me.json()["email"]!!.str)
        assertEquals(HttpStatusCode.OK, oauth.http.command(access, "start_workout").status, "with write scope")

        val listed = oauth.http.authedGet("/api/v1/tokens", session).json()["tokens"]!!.jsonArray.map { it.jsonObject }
        val mcpToken = listed.single { it["name"]!!.str == "mcp:Test client" }
        assertEquals("write", mcpToken["scope"]!!.str)
        assertNotNull(mcpToken["expires_at"]?.jsonPrimitive?.content?.toLongOrNull(), "OAuth tokens expire")
    }

    @Test
    fun aReadScopeGivesAReadToken() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        val clientId = oauth.registerClient()
        val (access, _) = oauth.grant(clientId, scope = "read")
        assertEquals(HttpStatusCode.OK, oauth.http.authedGet("/api/v1/me", access).status)
        assertEquals(HttpStatusCode.Forbidden, oauth.http.command(access, "start_workout").status)
    }

    @Test
    fun aWrongVerifierIsRejectedAndSpendsTheCode() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        val clientId = oauth.registerClient()
        val pkce = Pkce()
        val code = oauth.code(clientId, pkce)
        val wrong = oauth.exchange(clientId, code, Pkce().verifier)
        assertEquals(HttpStatusCode.BadRequest, wrong.status)
        assertEquals("invalid_grant", wrong.json()["error"]!!.str)
        assertEquals(HttpStatusCode.BadRequest, oauth.exchange(clientId, code, pkce.verifier).status, "the code is spent")

        val missing = oauth.token("grant_type" to "authorization_code", "code" to oauth.code(clientId, pkce), "client_id" to clientId)
        assertEquals("invalid_grant", missing.json()["error"]!!.str, "no verifier, no token")
    }

    @Test
    fun aCodeWorksOnceAndItsReuseRevokesTheToken() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        val clientId = oauth.registerClient()
        val pkce = Pkce()
        val code = oauth.code(clientId, pkce)
        val first = oauth.exchange(clientId, code, pkce.verifier)
        assertEquals(HttpStatusCode.OK, first.status)
        val access = first.json()["access_token"]!!.str

        val second = oauth.exchange(clientId, code, pkce.verifier)
        assertEquals(HttpStatusCode.BadRequest, second.status)
        assertEquals("invalid_grant", second.json()["error"]!!.str)
        assertEquals(HttpStatusCode.Unauthorized, oauth.http.authedGet("/api/v1/me", access).status, "RFC 6749 §4.1.2: revoke what the code gave")
    }

    @Test
    fun theRedirectUriMustMatchExactly() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        val clientId = oauth.registerClient()
        val pkce = Pkce()

        val elsewhere = oauth.http.get(oauth.authorizeUrl(clientId, pkce, redirect = "https://evil.example/callback"))
        assertEquals(HttpStatusCode.BadRequest, elsewhere.status, "an unregistered redirect is a dead end, never a redirect")
        assertTrue(elsewhere.bodyAsText().contains("not one this app registered"))
        val prefix = oauth.http.get(oauth.authorizeUrl(clientId, pkce, redirect = "$REDIRECT/extra"))
        assertEquals(HttpStatusCode.BadRequest, prefix.status, "exact match, not prefix")

        val code = oauth.code(clientId, pkce)
        val mismatch = oauth.exchange(clientId, code, pkce.verifier, redirect = "https://claude.example/other")
        assertEquals("invalid_grant", mismatch.json()["error"]!!.str)
    }

    @Test
    fun refreshRotatesAndTheOldTokensDie() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        val clientId = oauth.registerClient()
        val (access, refresh) = oauth.grant(clientId)

        val refreshed = oauth.refresh(clientId, refresh)
        assertEquals(HttpStatusCode.OK, refreshed.status, refreshed.bodyAsText())
        val newAccess = refreshed.json()["access_token"]!!.str
        val newRefresh = refreshed.json()["refresh_token"]!!.str
        assertTrue(newRefresh != refresh && newAccess != access)
        assertEquals(HttpStatusCode.Unauthorized, oauth.http.authedGet("/api/v1/me", access).status, "the old access token is replaced")
        assertEquals(HttpStatusCode.OK, oauth.http.authedGet("/api/v1/me", newAccess).status)

        val replay = oauth.refresh(clientId, refresh)
        assertEquals(HttpStatusCode.BadRequest, replay.status)
        assertEquals("invalid_grant", replay.json()["error"]!!.str, "the old refresh token is dead")
        // A rotated token presented again means it leaked: the whole grant ends.
        assertEquals(HttpStatusCode.Unauthorized, oauth.http.authedGet("/api/v1/me", newAccess).status)
        assertEquals("invalid_grant", oauth.refresh(clientId, newRefresh).json()["error"]!!.str)

        val otherClient = oauth.registerClient("Other")
        val (_, someRefresh) = oauth.grant(clientId)
        assertEquals("invalid_grant", oauth.refresh(otherClient, someRefresh).json()["error"]!!.str, "bound to its client")
    }

    @Test
    fun revokingTheApiTokenEndsTheGrant() = tungTest {
        val oauth = OAuthDriver(this)
        val session = oauth.account()
        val clientId = oauth.registerClient()
        val (access, refresh) = oauth.grant(clientId)
        val tokenId = oauth.http.authedGet("/api/v1/tokens", session).json()["tokens"]!!.jsonArray
            .map { it.jsonObject }.single { it["name"]!!.str == "mcp:Test client" }["id"]!!.str
        assertEquals(HttpStatusCode.NoContent, oauth.http.delete("/api/v1/tokens/$tokenId") { bearerAuth(session) }.status)

        val mcp = oauth.http.mcpInitialize(access)
        assertEquals(HttpStatusCode.Unauthorized, mcp.status)
        assertTrue(mcp.headers[HttpHeaders.WWWAuthenticate]!!.contains("error=\"invalid_token\""))
        assertEquals("invalid_grant", oauth.refresh(clientId, refresh).json()["error"]!!.str)
    }

    @Test
    fun theConsentPageGuardsItself() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        val clientId = oauth.registerClient()

        val wrongPassword = oauth.consent(clientId, Pkce(), password = "not the password")
        assertEquals(HttpStatusCode.OK, wrongPassword.status)
        assertTrue(wrongPassword.bodyAsText().contains("Wrong email or password."))

        val forged = oauth.consent(clientId, Pkce(), csrfOverride = "x".repeat(43))
        assertEquals(HttpStatusCode.BadRequest, forged.status, "the CSRF token must match the cookie")

        val denied = oauth.consent(clientId, Pkce(), action = "deny")
        assertEquals(HttpStatusCode.Found, denied.status)
        val location = Url(denied.headers[HttpHeaders.Location]!!)
        assertEquals("access_denied", location.parameters["error"])
        assertEquals("st-123", location.parameters["state"])

        val noPkce = oauth.http.get("/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=${REDIRECT.encodeURLParameter()}&state=s")
        assertEquals(HttpStatusCode.Found, noPkce.status)
        assertEquals("invalid_request", Url(noPkce.headers[HttpHeaders.Location]!!).parameters["error"])

        assertEquals(HttpStatusCode.BadRequest, oauth.http.get("/oauth/authorize?response_type=code&client_id=${id()}").status)
    }

    @Test
    fun registrationChecksRedirectUrisAndSecretsWork() = tungTest {
        val oauth = OAuthDriver(this)
        oauth.account()
        for (bad in listOf("http://evil.example/cb", "javascript:alert(1)", "https://claude.example/cb#frag", "/relative")) {
            val response = oauth.http.post("/oauth/register") { jsonBody("""{"client_name": "x", "redirect_uris": ["$bad"]}""") }
            assertEquals(HttpStatusCode.BadRequest, response.status, bad)
            assertEquals("invalid_redirect_uri", response.json()["error"]!!.str)
        }
        for (good in listOf("http://localhost:33418/callback", "http://127.0.0.1:5000/cb", "cursor://anysphere.cursor-retrieval/oauth/cb")) {
            val response = oauth.http.post("/oauth/register") { jsonBody("""{"redirect_uris": ["$good"]}""") }
            assertEquals(HttpStatusCode.Created, response.status, good)
            assertFalse("client_secret" in response.json(), "a public client gets no secret")
            assertEquals("none", response.json()["token_endpoint_auth_method"]!!.str)
        }

        val confidential = oauth.http.post("/oauth/register") {
            jsonBody("""{"client_name": "Server app", "redirect_uris": ["$REDIRECT"], "token_endpoint_auth_method": "client_secret_post"}""")
        }.json()
        val clientId = confidential["client_id"]!!.str
        val secret = confidential["client_secret"]!!.str
        val pkce = Pkce()
        val code = oauth.code(clientId, pkce)
        val noSecret = oauth.exchange(clientId, code, pkce.verifier)
        assertEquals(HttpStatusCode.Unauthorized, noSecret.status)
        assertEquals("invalid_client", noSecret.json()["error"]!!.str)
        val code2 = oauth.code(clientId, pkce)
        val withSecret = oauth.token(
            "grant_type" to "authorization_code", "code" to code2, "redirect_uri" to REDIRECT,
            "client_id" to clientId, "client_secret" to secret, "code_verifier" to pkce.verifier,
        )
        assertEquals(HttpStatusCode.OK, withSecret.status, withSecret.bodyAsText())
    }
}
