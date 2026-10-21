package io.github.kanylbullen.tung.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.bearerAuth
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
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CLIENT_ID = "1234-web.apps.googleusercontent.com"
private const val OTHER_CLIENT_ID = "5678-other.apps.googleusercontent.com"

/**
 * Google, as far as tung-server can tell: an RSA key pair per `kid`, and a
 * JWK set served over HTTP on a free port, with the keys the test decides
 * to publish. [fetches] counts the requests for it.
 */
class FakeGoogle : AutoCloseable {
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    val keys = mutableMapOf<String, KeyPair>()
    @Volatile var published: Set<String> = emptySet()
    val fetches = AtomicInteger()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/certs") { exchange ->
            fetches.incrementAndGet()
            val body = buildJsonObject {
                putJsonArray("keys") {
                    published.forEach { kid ->
                        val pub = keys.getValue(kid).public as RSAPublicKey
                        add(
                            buildJsonObject {
                                put("kty", "RSA")
                                put("alg", "RS256")
                                put("use", "sig")
                                put("kid", kid)
                                put("n", b64.encodeToString(unsigned(pub.modulus.toByteArray())))
                                put("e", b64.encodeToString(unsigned(pub.publicExponent.toByteArray())))
                            },
                        )
                    }
                }
            }.toString().toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.responseHeaders.add("Cache-Control", "public, max-age=3600, must-revalidate, no-transform")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    val jwksUrl = "http://127.0.0.1:${server.address.port}/certs"

    fun key(kid: String): KeyPair = keys.getOrPut(kid) { KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair() }

    /** Publishes [kid] (making its key on first use). */
    fun publish(vararg kids: String) {
        kids.forEach(::key)
        published = published + kids
    }

    /** An ID token as Google would issue it; every claim can be overridden or removed (JsonNull). */
    fun token(
        sub: String = UUID.randomUUID().toString(),
        email: String? = uniqueEmail(),
        verified: Boolean = true,
        kid: String = "k1",
        signWith: String = kid,
        alg: String = "RS256",
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        overrides: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): String {
        val header = buildJsonObject {
            put("alg", alg)
            put("kid", kid)
            put("typ", "JWT")
        }
        val base = buildJsonObject {
            put("iss", "https://accounts.google.com")
            put("aud", CLIENT_ID)
            put("azp", "android-client.apps.googleusercontent.com")
            put("sub", sub)
            if (email != null) put("email", email)
            put("email_verified", verified)
            put("name", "Test Person")
            put("iat", nowSeconds)
            put("exp", nowSeconds + 3600)
        }
        val claims = JsonObject((base + overrides).filterValues { it != JsonNull })
        val signingInput = b64.encodeToString(header.toString().toByteArray()) + "." + b64.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key(signWith).private)
            update(signingInput.toByteArray())
            sign()
        }
        return signingInput + "." + b64.encodeToString(signature)
    }

    override fun close() = server.stop(0)

    private fun unsigned(bytes: ByteArray) = if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}

class GoogleSignInTest {

    private val google = FakeGoogle().apply { publish("k1") }

    @AfterTest
    fun stop() = google.close()

    private fun googleTest(clientIds: List<String> = listOf(CLIENT_ID, OTHER_CLIENT_ID), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment { config = MapApplicationConfig() }
            application { module(TestDatabase.config().copy(googleClientIds = clientIds, googleJwksUrl = google.jwksUrl)) }
            block()
        }

    private suspend fun ApplicationTestBuilder.signIn(idToken: String): HttpResponse =
        client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "$idToken"}""") }

    private suspend fun ApplicationTestBuilder.login(email: String, password: String): HttpResponse =
        client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "$password"}""") }

    private suspend fun ApplicationTestBuilder.me(token: String): JsonObject = client.get("/api/v1/me") { bearerAuth(token) }.json()

    @Test
    fun aNewGoogleUserIsCreatedWithoutAPasswordAndSignsInAgainAsTheSameUser() = googleTest {
        val email = uniqueEmail()
        val sub = UUID.randomUUID().toString()
        val first = signIn(google.token(sub = sub, email = "  ${email.uppercase()} "))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val body = first.json()
        val token = body["token"]!!.str
        assertTrue(token.startsWith("tung_"))
        val user = body["user"]!!.jsonObject
        assertEquals(email, user["email"]!!.str, "stored trimmed and lower-cased")
        assertFalse(user["has_password"]!!.jsonPrimitive.boolean)
        assertTrue(user["google_linked"]!!.jsonPrimitive.boolean)

        val me = me(token)
        assertEquals(user["id"]!!.str, me["id"]!!.str)
        assertFalse(me["has_password"]!!.jsonPrimitive.boolean)
        assertTrue(me["google_linked"]!!.jsonPrimitive.boolean)

        val again = signIn(google.token(sub = sub, email = email))
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals(user["id"]!!.str, again.json()["user"]!!.jsonObject["id"]!!.str)
        assertNotEquals(token, again.json()["token"]!!.str)
    }

    @Test
    fun aGoogleOnlyUserCannotLogInWithAPassword() = googleTest {
        val email = uniqueEmail()
        assertEquals(HttpStatusCode.OK, signIn(google.token(email = email)).status)
        // The dummy hash's own password, the one that would match if a null hash fell back to it.
        for (password in listOf("not a real password", "correct horse battery", "")) {
            val response = login(email, password)
            assertEquals(HttpStatusCode.Unauthorized, response.status, response.bodyAsText())
            assertEquals("invalid_credentials", response.errorCode())
        }
    }

    @Test
    fun anExistingPasswordUserWithAVerifiedEmailIsLinkedAndKeepsThePassword() = googleTest {
        val email = uniqueEmail()
        val passwordToken = client.register(email, "the right password")
        val userId = me(passwordToken)["id"]!!.str
        assertFalse(me(passwordToken)["google_linked"]!!.jsonPrimitive.boolean)

        val sub = UUID.randomUUID().toString()
        val linked = signIn(google.token(sub = sub, email = email.uppercase()))
        assertEquals(HttpStatusCode.OK, linked.status, linked.bodyAsText())
        val user = linked.json()["user"]!!.jsonObject
        assertEquals(userId, user["id"]!!.str)
        assertTrue(user["has_password"]!!.jsonPrimitive.boolean)
        assertTrue(user["google_linked"]!!.jsonPrimitive.boolean)
        assertTrue(me(passwordToken)["google_linked"]!!.jsonPrimitive.boolean)

        val password = login(email, "the right password")
        assertEquals(HttpStatusCode.OK, password.status)
        assertEquals(userId, password.json()["user"]!!.jsonObject["id"]!!.str)

        // Linked by sub from now on: even an unverified token with another email reaches the account.
        val bySub = signIn(google.token(sub = sub, email = uniqueEmail(), verified = false))
        assertEquals(HttpStatusCode.OK, bySub.status)
        assertEquals(userId, bySub.json()["user"]!!.jsonObject["id"]!!.str)

        // Another Google account with the same verified email is not linked over the first.
        val other = signIn(google.token(email = email))
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals("google_account_mismatch", other.errorCode())
    }

    @Test
    fun anUnverifiedEmailIsRefusedAndLinksNothing() = googleTest {
        val email = uniqueEmail()
        val passwordToken = client.register(email, "the right password")
        val sub = UUID.randomUUID().toString()

        val refused = signIn(google.token(sub = sub, email = email, verified = false))
        assertEquals(HttpStatusCode.Unauthorized, refused.status)
        assertEquals("email_not_verified", refused.errorCode())
        assertFalse(me(passwordToken)["google_linked"]!!.jsonPrimitive.boolean)

        val noEmail = signIn(google.token(email = null))
        assertEquals(HttpStatusCode.Unauthorized, noEmail.status)

        // Nothing was stored for that sub: a later verified sign-in links the password account, not a new one.
        val verified = signIn(google.token(sub = sub, email = email))
        assertEquals(me(passwordToken)["id"]!!.str, verified.json()["user"]!!.jsonObject["id"]!!.str)
    }

    @Test
    fun tokensThatAreNotGoogleTokensForUsAreRejected() = googleTest {
        val now = System.currentTimeMillis() / 1000
        val cases = mapOf(
            "wrong audience" to google.token(overrides = mapOf("aud" to JsonPrimitive("someone-else.apps.googleusercontent.com"))),
            "expired" to google.token(nowSeconds = now - 3600 - 120),
            "wrong issuer" to google.token(overrides = mapOf("iss" to JsonPrimitive("https://evil.example"))),
            "no iat" to google.token(overrides = mapOf("iat" to JsonNull)),
            "bad signature" to google.token(kid = "k1", signWith = "attacker"),
            "HS256" to google.token(alg = "HS256"),
            "not a JWT" to "not.a-jwt",
        )
        for ((what, token) in cases) {
            val response = signIn(token)
            assertEquals(HttpStatusCode.Unauthorized, response.status, "$what: ${response.bodyAsText()}")
            assertEquals("invalid_google_token", response.errorCode(), what)
        }

        // Inside the 60 s leeway, and for the second configured client id: accepted.
        assertEquals(HttpStatusCode.OK, signIn(google.token(nowSeconds = now - 3600 + 30 - 60)).status)
        assertEquals(HttpStatusCode.OK, signIn(google.token(overrides = mapOf("aud" to JsonPrimitive(OTHER_CLIENT_ID)))).status)

        val malformed = client.post("/api/v1/auth/google") { jsonBody("""{"id_token": """) }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
        val missing = client.post("/api/v1/auth/google") { jsonBody("""{}""") }
        assertEquals(HttpStatusCode.BadRequest, missing.status)
    }

    @Test
    fun theKeySetIsCachedAndAnUnknownKidRefetchesItOnce() = googleTest {
        assertEquals(HttpStatusCode.OK, signIn(google.token()).status)
        assertEquals(HttpStatusCode.OK, signIn(google.token()).status)
        assertEquals(1, google.fetches.get(), "max-age=3600 keeps the set")

        // Google rotates: a token with a kid the cached set lacks fetches the set again and finds it.
        google.publish("k2")
        assertEquals(HttpStatusCode.OK, signIn(google.token(kid = "k2")).status)
        assertEquals(2, google.fetches.get())

        // A kid Google never published: one refetch, then 401; another right after does not refetch.
        google.key("k3")
        val unknown = signIn(google.token(kid = "k3"))
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("invalid_google_token", unknown.errorCode())
        assertEquals(2, google.fetches.get(), "the refetch for k2 was under a minute ago")
    }

    @Test
    fun anUnknownKidRefetchesOnceThenFails() = googleTest {
        google.key("k9")
        val unknown = signIn(google.token(kid = "k9"))
        assertEquals(HttpStatusCode.Unauthorized, unknown.status)
        assertEquals("invalid_google_token", unknown.errorCode())
        assertEquals(2, google.fetches.get(), "the first fetch and exactly one refetch")
    }

    @Test
    fun withoutAClientIdGoogleIsAbsent() = tungTest {
        val response = client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "${google.token()}"}""") }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_configured", response.errorCode())

        val methods = client.get("/api/v1/auth/methods").json()
        assertTrue(methods["password"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, methods["google"])

        val oauth = OAuthDriver(this)
        val page = oauth.http.get(oauth.authorizeUrl(oauth.registerClient(), Pkce())).bodyAsText()
        assertFalse("gsi/client" in page)
        assertFalse("g_id_onload" in page)
        assertEquals(HttpStatusCode.NotFound, oauth.http.post("/oauth/google").status)
    }

    @Test
    fun methodsNameTheFirstClientId() = googleTest {
        val methods = client.get("/api/v1/auth/methods").json()
        assertTrue(methods["password"]!!.jsonPrimitive.boolean)
        assertEquals(CLIENT_ID, methods["google"]!!.jsonObject["client_id"]!!.str)
    }

    @Test
    fun theConsentPageOffersGoogleAndItLeadsToTheSameCodeAsAPassword() = googleTest {
        val oauth = OAuthDriver(this)
        val clientId = oauth.registerClient()
        val pkce = Pkce()

        val page = oauth.http.get(oauth.authorizeUrl(clientId, pkce))
        assertEquals(HttpStatusCode.OK, page.status)
        val html = page.bodyAsText()
        assertTrue("""<script src="https://accounts.google.com/gsi/client" async></script>""" in html, html)
        assertTrue("""id="g_id_onload" data-client_id="$CLIENT_ID"""" in html)
        assertTrue("""data-login_uri="http://localhost/oauth/google"""" in html)
        assertTrue("""data-ux_mode="redirect"""" in html)
        assertTrue("""data-text="continue_with"""" in html)
        val csp = page.headers["Content-Security-Policy"]!!
        assertTrue("script-src https://accounts.google.com/gsi/client" in csp, csp)
        assertTrue("frame-src https://accounts.google.com/gsi/" in csp, csp)
        val state = Regex("""data-state="([^"]+)"""").find(html)!!.groupValues[1].replace("&amp;", "&")

        val email = uniqueEmail()
        val passwordUser = oauth.http.register(email, "the right password")

        suspend fun postGoogle(credential: String, csrfCookie: String = "gcsrf-1", csrfField: String = "gcsrf-1", state: String = "x") =
            oauth.http.post("/oauth/google") {
                header(HttpHeaders.Cookie, "g_csrf_token=$csrfCookie")
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("credential", credential)
                            append("g_csrf_token", csrfField)
                            append("select_by", "btn")
                            append("state", state)
                        },
                    ),
                )
            }

        // Google's double-submit cookie must match, and the state must be ours.
        assertEquals(HttpStatusCode.BadRequest, postGoogle(google.token(email = email), csrfField = "other", state = state).status)
        assertEquals(HttpStatusCode.BadRequest, postGoogle(google.token(email = email), state = state.dropLast(2) + "xx").status)
        // A rejected token shows the login page again with the reason.
        val refused = postGoogle(google.token(email = email, verified = false), state = state)
        assertEquals(HttpStatusCode.OK, refused.status)
        val refusedPage = refused.bodyAsText()
        assertTrue("g_id_onload" in refusedPage, refusedPage)
        assertTrue("has not verified" in refusedPage, refusedPage)

        val signedIn = postGoogle(google.token(email = email), state = state)
        assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
        val consent = signedIn.bodyAsText()
        assertTrue("Signed in with Google as <strong>$email</strong>" in consent, consent)
        assertFalse("name=\"password\"" in consent)
        val cookie = signedIn.headers.getAll(HttpHeaders.SetCookie).orEmpty().single { it.startsWith("tung_oauth_csrf=") }.substringBefore(';')
        fun hidden(name: String) = Regex("""name="$name" value="([^"]*)"""").find(consent)?.groupValues?.get(1)?.replace("&amp;", "&")
        suspend fun allow(ticket: String?) = oauth.http.post("/oauth/authorize") {
            header(HttpHeaders.Cookie, cookie)
            setBody(
                FormDataContent(
                    Parameters.build {
                        listOf("response_type", "client_id", "redirect_uri", "state", "scope", "code_challenge", "code_challenge_method", "resource", "csrf")
                            .forEach { key -> hidden(key)?.let { append(key, it) } }
                        if (ticket != null) append("google_ticket", ticket)
                        append("action", "allow")
                    },
                ),
            )
        }
        // A forged ticket does not stand in for the password.
        assertEquals(HttpStatusCode.BadRequest, allow(hidden("google_ticket")!!.dropLast(2) + "xx").status)

        val allowed = allow(hidden("google_ticket"))
        assertEquals(HttpStatusCode.Found, allowed.status, allowed.bodyAsText())
        val location = Url(allowed.headers[HttpHeaders.Location]!!)
        assertEquals(REDIRECT, location.toString().substringBefore('?'))
        assertEquals("st-123", location.parameters["state"])
        val code = assertNotNull(location.parameters["code"])

        val exchanged = oauth.exchange(clientId, code, pkce.verifier)
        assertEquals(HttpStatusCode.OK, exchanged.status, exchanged.bodyAsText())
        val access = exchanged.json()["access_token"]!!.str
        // The grant is the password user's account, now linked.
        val granted = oauth.http.get("/api/v1/me") { bearerAuth(access) }.json()
        assertEquals(me(passwordUser)["id"]!!.str, granted["id"]!!.str)
        assertTrue(granted["google_linked"]!!.jsonPrimitive.boolean)
    }
}
