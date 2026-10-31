package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sign-ups to test the Android app: anyone may sign up once, only an admin sees the list, and only where the server runs the test. */
class TesterSignupTest {

    private val owner = uniqueEmail()

    private fun testersTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application { module(TestDatabase.config().copy(adminEmails = setOf(owner), testerSignups = true)) }
        block()
    }

    private suspend fun ApplicationTestBuilder.signUp(body: String) = client.post("/api/v1/testers") { jsonBody(body) }

    private suspend fun ApplicationTestBuilder.listed(token: String): List<String> =
        client.get("/api/v1/testers") { bearerAuth(token) }.json()["testers"]!!.jsonArray.map { it.jsonObject["email"]!!.str }

    @Test
    fun aSignUpIsStoredOnceAndOnlyTheOwnerSeesIt() = testersTest {
        val address = "Tester-${id()}@Example.org"
        val first = signUp("""{"email": "$address", "consent": true, "language": "sv"}""")
        assertEquals(HttpStatusCode.Accepted, first.status, first.bodyAsText())
        val again = signUp("""{"email": "${address.lowercase()}", "consent": true}""")
        assertEquals(HttpStatusCode.Accepted, again.status, "the same answer for an address already there")
        assertEquals(first.bodyAsText(), again.bodyAsText())

        val ownerToken = client.register(owner)
        val rows = client.get("/api/v1/testers") { bearerAuth(ownerToken) }.json()["testers"]!!.jsonArray.map { it.jsonObject }
        val row = rows.single { it["email"]!!.str == address.lowercase() }
        assertEquals("sv", row["language"]!!.str)

        val someoneElse = client.get("/api/v1/testers") { bearerAuth(client.register()) }
        assertEquals(HttpStatusCode.Forbidden, someoneElse.status)
        assertEquals("not_admin", someoneElse.errorCode())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/testers").status)

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/testers/${address.lowercase()}") { bearerAuth(ownerToken) }.status)
        assertFalse(address.lowercase() in listed(ownerToken))
    }

    @Test
    fun consentAnAddressAndTheHoneypot() = testersTest {
        val noConsent = signUp("""{"email": "x-${id()}@example.org"}""")
        assertEquals(HttpStatusCode.BadRequest, noConsent.status)
        assertEquals("consent_required", noConsent.errorCode())

        val notAnAddress = signUp("""{"email": "not an address", "consent": true}""")
        assertEquals(HttpStatusCode.BadRequest, notAnAddress.status)
        assertEquals("invalid_email", notAnAddress.errorCode())

        val bot = "bot-${id()}@example.org"
        val botAnswer = signUp("""{"email": "$bot", "consent": true, "website": "https://spam.example"}""")
        assertEquals(HttpStatusCode.Accepted, botAnswer.status, "a bot is answered as anyone else")
        assertTrue(bot !in listed(client.register(owner)), "and stored as no one")
    }

    @Test
    fun theFormIsOnTheLandingPage() = testersTest {
        val html = client.get("/").bodyAsText()
        assertTrue("""id="signup"""" in html)
        assertTrue("Sign up below." in html)
        assertFalse("testers:" in html, "no marker is left in the page")
    }

    @Test
    fun aServerWithoutTheTestHasNoSignUps() = testApplication {
        val owner = uniqueEmail()
        environment { config = MapApplicationConfig() }
        application { module(TestDatabase.config().copy(adminEmails = setOf(owner))) }

        val signUp = client.post("/api/v1/testers") { jsonBody("""{"email": "x-${id()}@example.org", "consent": true}""") }
        assertEquals(HttpStatusCode.NotFound, signUp.status)
        assertEquals("not_configured", signUp.errorCode())
        val list = client.get("/api/v1/testers") { bearerAuth(client.register(owner)) }
        assertEquals(HttpStatusCode.NotFound, list.status, "not even for an admin")

        val html = client.get("/").bodyAsText()
        assertFalse("""id="signup"""" in html, "no form")
        assertFalse("Sign up below." in html)
        assertTrue("In a test on Google Play." in html, "Android is still listed")
        assertFalse("testers:" in html, "no marker is left in the page")
    }
}
