package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PasswordResetTest {
    private val sent: MutableList<MailMessage> = Collections.synchronizedList(mutableListOf())

    @Volatile private var now = System.currentTimeMillis()

    private fun resetTest(mail: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            module(
                if (mail) {
                    TestDatabase.config().copy(publicUrl = "https://tung.example.org", mailer = Mailer { sent += it }, clock = { now })
                } else {
                    TestDatabase.config().copy(clock = { now })
                },
            )
        }
        block()
    }

    /** The token of the newest reset link sent to [email], waiting for [count] reset mails. */
    private suspend fun resetLink(email: String, count: Int = 1): String {
        fun resets() = sent.filter { it.to == email && "/reset-password?token=" in it.text }
        repeat(50) {
            if (resets().size >= count) return@repeat
            delay(100)
        }
        return Regex("""/reset-password\?token=(\S+)""").find(resets().last().text)!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.forgot(email: String) =
        client.post("/api/v1/auth/password/forgot") { jsonBody("""{"email": "$email", "language": "sv"}""") }

    private suspend fun ApplicationTestBuilder.choose(token: String, password: String) =
        client.post("/reset-password") { setBody(FormDataContent(Parameters.build { append("token", token); append("password", password) })) }

    private suspend fun ApplicationTestBuilder.login(email: String, password: String) =
        client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "$password"}""") }

    @Test
    fun aLinkByEmailSetsANewPasswordAndSignsEveryDeviceOut() = resetTest {
        val email = uniqueEmail()
        val old = client.register(email, "the old password")

        assertEquals(HttpStatusCode.Accepted, forgot(email).status)
        val token = resetLink(email)
        assertEquals("Välj ett nytt lösenord för Tung", sent.last { it.to == email && "reset-password" in it.text }.subject)

        // Opening the link only shows the form (a mail scanner may open it), with the token in it.
        val page = client.get("/reset-password?token=$token")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("name=\"token\" value=\"$token\"" in page.bodyAsText())
        assertEquals("no-referrer", page.headers["Referrer-Policy"])

        val weak = choose(token, "short")
        assertEquals(HttpStatusCode.BadRequest, weak.status)
        assertTrue("The password needs at least 8 characters." in weak.bodyAsText())

        val done = choose(token, "the new password")
        assertEquals(HttpStatusCode.OK, done.status)
        assertTrue("Your password is changed" in done.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(old) }.status)
        assertEquals(HttpStatusCode.Unauthorized, login(email, "the old password").status)
        val signedIn = login(email, "the new password")
        assertEquals(HttpStatusCode.OK, signedIn.status)
        // The link came to the inbox: the address counts as confirmed.
        assertEquals("true", signedIn.json()["user"]!!.jsonObjectField("email_verified"))

        // A link works once.
        assertEquals(HttpStatusCode.BadRequest, choose(token, "a third password").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/reset-password?token=$token").status)
    }

    @Test
    fun theAnswerIsTheSameForAnAddressWithNoAccountAndMailsAreLimited() = resetTest {
        val nobody = uniqueEmail()
        assertEquals(HttpStatusCode.Accepted, forgot(nobody).status)
        delay(300)
        assertTrue(sent.none { it.to == nobody })

        val email = uniqueEmail()
        client.register(email, "the old password")
        repeat(RESET_MAILS_PER_DAY + 2) { assertEquals(HttpStatusCode.Accepted, forgot(email).status) }
        val first = resetLink(email, count = RESET_MAILS_PER_DAY)
        delay(300)
        assertEquals(RESET_MAILS_PER_DAY, sent.count { it.to == email && "reset-password" in it.text })
        // Only the newest link works.
        val older = Regex("""/reset-password\?token=(\S+)""").find(sent.first { it.to == email && "reset-password" in it.text }.text)!!.groupValues[1]
        assertEquals(HttpStatusCode.BadRequest, client.get("/reset-password?token=$older").status)
        assertEquals(HttpStatusCode.OK, client.get("/reset-password?token=$first").status)
    }

    @Test
    fun aLinkExpiresAfterAnHour() = resetTest {
        val email = uniqueEmail()
        client.register(email, "the old password")
        forgot(email)
        val token = resetLink(email)
        now += PASSWORD_RESET_LIFETIME_MS + 1
        val page = client.get("/reset-password?token=$token")
        assertEquals(HttpStatusCode.BadRequest, page.status)
        assertTrue("This link does not work" in page.bodyAsText())
        assertEquals(HttpStatusCode.BadRequest, choose(token, "the new password").status)
        assertEquals(HttpStatusCode.OK, login(email, "the old password").status)
    }

    @Test
    fun aServerWithoutMailCannotReset() = resetTest(mail = false) {
        val email = uniqueEmail()
        client.register(email, "the old password")
        val refused = forgot(email)
        assertEquals(HttpStatusCode.NotFound, refused.status)
        assertEquals("not_configured", refused.errorCode())
        assertFalse(sent.any { it.to == email })
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectField(name: String): String? =
    (this as kotlinx.serialization.json.JsonObject)[name]?.toString()
