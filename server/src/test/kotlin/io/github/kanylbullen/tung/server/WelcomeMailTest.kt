package io.github.kanylbullen.tung.server

import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The welcome emails: one per new account (password or Google) and one per new test sign-up, never twice, never otherwise. */
class WelcomeMailTest {

    private val sent = CopyOnWriteArrayList<MailMessage>()
    private val google = FakeGoogle().apply { publish("k1") }

    @AfterTest
    fun stop() = google.close()

    private fun mailTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            module(
                TestDatabase.config().copy(
                    publicUrl = "https://tung.example.org",
                    mailer = Mailer { sent += it },
                    testerSignups = true,
                    googleClientIds = listOf(CLIENT_ID),
                    googleJwksUrl = google.jwksUrl,
                ),
            )
        }
        block()
    }

    /** The mails to [email], once the ones on their way have had time to arrive. */
    private suspend fun mailsTo(email: String, expected: Int): List<MailMessage> {
        repeat(50) {
            if (sent.count { it.to == email } >= expected) return@repeat
            delay(100)
        }
        delay(300) // a second one, if any, would be here by now
        return sent.filter { it.to == email }
    }

    @Test
    fun registeringSendsOneWelcomeInTheAppsLanguage() = mailTest {
        val email = uniqueEmail()
        val response = client.post("/api/v1/auth/register") {
            jsonBody("""{"email": "$email", "password": "correct horse battery", "language": "sv"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val mail = mailsTo(email, 1).single()
        assertEquals("Välkommen till Tung", mail.subject)
        assertTrue("https://tung.example.org/app/" in mail.text, mail.text)
        assertTrue("https://tung.example.org/delete-account" in mail.text, mail.text)
        assertTrue("Tung är i beta" in mail.text, "the beta notice: ${mail.text}")
        assertTrue("flytta till en annan Tung-server" in mail.text, "how to move: ${mail.text}")
        assertTrue("testet av Android-appen" in mail.text, "the Play test invite, on a server that runs it: ${mail.text}")
        val plain = WelcomeMail.accountMessage(email, swedish = true, url = "https://t.example.org", testInvite = false).text
        assertTrue("Android-appen" !in plain && "\n\n\n" !in plain, plain)

        // Signing in again sends nothing.
        client.post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "correct horse battery"}""") }
        assertEquals(1, mailsTo(email, 1).size)
    }

    @Test
    fun withoutALanguageTheWelcomeIsEnglish() = mailTest {
        val email = uniqueEmail()
        client.register(email)
        assertEquals("Welcome to Tung", mailsTo(email, 1).single().subject)
    }

    @Test
    fun aNewGoogleAccountIsWelcomedOnceAndALinkIsNot() = mailTest {
        val email = uniqueEmail()
        val sub = UUID.randomUUID().toString()
        repeat(2) {
            val response = client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "${google.token(sub = sub, email = email)}", "language": "en"}""") }
            assertEquals(HttpStatusCode.OK, response.status)
        }
        assertEquals(listOf("Welcome to Tung"), mailsTo(email, 1).map { it.subject })

        // An existing password account that Google links to is not new: its welcome came when it was made.
        val linked = uniqueEmail()
        client.register(linked)
        mailsTo(linked, 1)
        client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "${google.token(sub = UUID.randomUUID().toString(), email = linked)}"}""") }
        assertEquals(1, mailsTo(linked, 1).size)
    }

    @Test
    fun aTestSignUpIsThankedOnceAndABotNever() = mailTest {
        val email = uniqueEmail()
        repeat(2) {
            val response = client.post("/api/v1/testers") { jsonBody("""{"email": "$email", "consent": true, "language": "en"}""") }
            assertEquals(HttpStatusCode.Accepted, response.status)
        }
        assertEquals(listOf("You're on the list to test Tung"), mailsTo(email, 1).map { it.subject })

        val bot = uniqueEmail()
        client.post("/api/v1/testers") { jsonBody("""{"email": "$bot", "consent": true, "website": "spam"}""") }
        assertTrue(mailsTo(bot, 0).isEmpty())
    }

    @Test
    fun aFailingMailerNeverFailsTheSignUp() = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            module(TestDatabase.config().copy(publicUrl = "https://tung.example.org", mailer = Mailer { error("Resend is down") }))
        }
        client.register()
    }

    @Test
    fun atMostTheCeilingADayAndTheCountStartsOverTomorrow() = runBlocking {
        val mails = CopyOnWriteArrayList<MailMessage>()
        var now = 0L
        val scope = CoroutineScope(SupervisorJob())
        val welcome = WelcomeMail({ mails += it }, "https://t.example.org", scope, clock = { now }, perDay = 2)
        repeat(5) { welcome.account("p$it@example.org", "sv-SE,sv;q=0.9,en;q=0.8") }
        now += 24 * 60 * 60 * 1000L
        welcome.tester("next@example.org", null)
        repeat(50) { if (mails.size < 3) delay(50) }
        assertEquals(setOf("p0@example.org", "p1@example.org", "next@example.org"), mails.map { it.to }.toSet())
        assertEquals("Välkommen till Tung", mails.first { it.to == "p0@example.org" }.subject, "a browser's Accept-Language counts")
        scope.cancel()
    }

    @Test
    fun resendGetsTheMessageWithTheKey() = runBlocking {
        val received = CopyOnWriteArrayList<Pair<String?, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/emails") { exchange ->
                received += exchange.requestHeaders.getFirst("Authorization") to exchange.requestBody.readBytes().decodeToString()
                val answer = """{"id":"e1"}""".toByteArray()
                exchange.sendResponseHeaders(200, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            createContext("/fail/emails") { exchange ->
                val answer = """{"name":"validation_error","message":"The domain is not verified."}""".toByteArray()
                exchange.sendResponseHeaders(403, answer.size.toLong())
                exchange.responseBody.use { it.write(answer) }
            }
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            ResendMailer("re_test", "Tung <tung@example.org>", base).send(MailMessage("a@example.org", "Hi", "Text"))
            val (auth, body) = received.single()
            assertEquals("Bearer re_test", auth)
            val json = json(body)
            assertEquals("Tung <tung@example.org>", json["from"]!!.str)
            assertEquals(listOf("a@example.org"), json["to"]!!.jsonArray.map { it.str })
            assertEquals("Hi", json["subject"]!!.str)
            assertEquals("Text", json["text"]!!.str)

            val failure = assertFailsWith<IllegalStateException> {
                ResendMailer("re_test", "Tung <tung@example.org>", "$base/fail").send(MailMessage("a@example.org", "Hi", "Text"))
            }
            assertEquals("Resend answered 403 (validation_error)", failure.message, "the status and the error's name, never its text")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aKeyNeedsASenderAndThePublicUrl() {
        val base = mapOf("DATABASE_URL" to "jdbc:postgresql://x/y", "TUNG_RESEND_API_KEY" to "re_x")
        assertFailsWith<ConfigException> { ServerConfig.fromEnv(base) }
        assertFailsWith<ConfigException> { ServerConfig.fromEnv(base + ("TUNG_MAIL_FROM" to "Tung <t@example.org>")) }
        val config = ServerConfig.fromEnv(base + ("TUNG_MAIL_FROM" to "Tung <t@example.org>") + ("TUNG_PUBLIC_URL" to "https://t.example.org"))
        assertTrue(config.mailer is ResendMailer)
        assertEquals(null, ServerConfig.fromEnv(mapOf("DATABASE_URL" to "jdbc:postgresql://x/y")).mailer)
    }
}
