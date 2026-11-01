package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SERVICES_ID = "fun.xuper.tung.web"
private const val BUNDLE_ID = "io.github.kanylbullen.tung"

/** Sign in with Apple: the same rules as Google's (`GoogleSignInTest`), Apple's issuer, and the two providers together. */
class AppleSignInTest {

    // One JWK set serves both providers' test keys; the issuer and audience tell the tokens apart.
    private val keys = FakeGoogle().apply { publish("k1") }

    @AfterTest
    fun stop() = keys.close()

    private fun appleTest(appleIds: List<String> = listOf(SERVICES_ID, BUNDLE_ID), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            environment { config = MapApplicationConfig() }
            application {
                module(
                    TestDatabase.config().copy(
                        googleClientIds = listOf(CLIENT_ID),
                        googleJwksUrl = keys.jwksUrl,
                        appleClientIds = appleIds,
                        appleJwksUrl = keys.jwksUrl,
                    ),
                )
            }
            block()
        }

    /** An Apple ID token: Apple's issuer, the web's Services ID, and `email_verified` as the string Apple sends. */
    private fun appleToken(
        sub: String = "001234.${UUID.randomUUID().toString().replace("-", "")}.1234",
        email: String? = uniqueEmail(),
        aud: String = SERVICES_ID,
        overrides: Map<String, JsonElement> = emptyMap(),
    ): String = keys.token(
        sub = sub,
        email = email,
        overrides = mapOf(
            "iss" to JsonPrimitive("https://appleid.apple.com"),
            "aud" to JsonPrimitive(aud),
            "azp" to JsonNull,
            "name" to JsonNull,
            "email_verified" to JsonPrimitive("true"),
        ) + overrides,
    )

    private suspend fun ApplicationTestBuilder.apple(idToken: String): HttpResponse =
        client.post("/api/v1/auth/apple") { jsonBody("""{"id_token": "$idToken"}""") }

    private suspend fun ApplicationTestBuilder.google(idToken: String): HttpResponse =
        client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "$idToken"}""") }

    private suspend fun ApplicationTestBuilder.me(token: String): JsonObject = client.get("/api/v1/me") { bearerAuth(token) }.json()

    private fun JsonObject.flag(name: String) = this[name]!!.jsonPrimitive.boolean

    @Test
    fun aNewAppleUserIsCreatedWithoutAPasswordAndSignsInAgainFromTheAppAsTheSameUser() = appleTest {
        val email = uniqueEmail()
        val sub = "001234.${UUID.randomUUID()}.0001"
        val first = apple(appleToken(sub = sub, email = email))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val user = first.json()["user"]!!.jsonObject
        assertEquals(email, user["email"]!!.str)
        assertFalse(user.flag("has_password"))
        assertTrue(user.flag("apple_linked"))
        assertFalse(user.flag("google_linked"))
        val me = me(first.json()["token"]!!.str)
        assertTrue(me.flag("apple_linked"))
        assertTrue(me.flag("email_verified"))

        // The iPhone app's token carries its bundle id as the audience: the same Apple ID, the same user.
        val fromApp = apple(appleToken(sub = sub, email = email, aud = BUNDLE_ID))
        assertEquals(HttpStatusCode.OK, fromApp.status, fromApp.bodyAsText())
        assertEquals(user["id"]!!.str, fromApp.json()["user"]!!.jsonObject["id"]!!.str)
    }

    @Test
    fun aPrivateRelayAddressMakesAnAccountOfItsOwn() = appleTest {
        val relay = "${UUID.randomUUID().toString().take(10)}@privaterelay.appleid.com"
        val response = apple(appleToken(email = relay, overrides = mapOf("is_private_email" to JsonPrimitive("true"))))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertEquals(relay, response.json()["user"]!!.jsonObject["email"]!!.str)
    }

    @Test
    fun tokensOfTheOtherProviderOrAnotherAppAreRefused() = appleTest {
        // A Google token at the Apple route, an Apple token at the Google route, and one for another app.
        val google = keys.token(email = uniqueEmail())
        assertEquals("invalid_apple_token", apple(google).errorCode())
        assertEquals("invalid_google_token", google(appleToken()).errorCode())
        val other = apple(appleToken(aud = "com.example.other"))
        assertEquals(HttpStatusCode.Unauthorized, other.status)
        assertEquals("invalid_apple_token", other.errorCode())
        // Without an email Tung has nothing to make the account with.
        val noEmail = apple(appleToken(email = null))
        assertEquals(HttpStatusCode.Unauthorized, noEmail.status)
        assertEquals("email_not_verified", noEmail.errorCode())
    }

    @Test
    fun linkingAPasswordAccountHandsItToTheAppleOwner() = appleTest {
        val email = uniqueEmail()
        val squatter = client.register(email, "the squatter's password")
        val linked = apple(appleToken(email = email))
        assertEquals(HttpStatusCode.OK, linked.status, linked.bodyAsText())
        assertFalse(linked.json()["user"]!!.jsonObject.flag("has_password"))
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(squatter) }.status)
        val login = client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "the squatter's password"}""") }
        assertEquals("invalid_credentials", login.errorCode())
    }

    @Test
    fun anAccountLinkedToGoogleIsHandedOverToAppleNeverJoinedSilently() = appleTest {
        val email = uniqueEmail()
        val googleSub = UUID.randomUUID().toString()
        val viaGoogle = google(keys.token(sub = googleSub, email = email))
        assertEquals(HttpStatusCode.OK, viaGoogle.status, viaGoogle.bodyAsText())
        val googleSession = viaGoogle.json()["token"]!!.str
        val userId = viaGoogle.json()["user"]!!.jsonObject["id"]!!.str

        val viaApple = apple(appleToken(email = email))
        assertEquals(HttpStatusCode.OK, viaApple.status, viaApple.bodyAsText())
        assertEquals(userId, viaApple.json()["user"]!!.jsonObject["id"]!!.str)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(googleSession) }.status, "the earlier sessions end")
        val me = me(viaApple.json()["token"]!!.str)
        assertTrue(me.flag("google_linked"))
        assertTrue(me.flag("apple_linked"))
        // Both ways in reach it from then on.
        assertEquals(userId, google(keys.token(sub = googleSub, email = email)).json()["user"]!!.jsonObject["id"]!!.str)

        // Another Apple ID with the same address is not this account's.
        val other = apple(appleToken(email = email))
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals("apple_account_mismatch", other.errorCode())
    }

    @Test
    fun methodsNameTheServicesIdAndAServerWithoutOneSaysNotConfigured() {
        appleTest {
            val methods = client.get("/api/v1/auth/methods").json()
            assertEquals(SERVICES_ID, methods["apple"]!!.jsonObject["client_id"]!!.str)
        }
        appleTest(appleIds = emptyList()) {
            assertEquals(JsonNull, client.get("/api/v1/auth/methods").json()["apple"])
            val response = apple(appleToken())
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertEquals("not_configured", response.errorCode())
        }
    }
}
