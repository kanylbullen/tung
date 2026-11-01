package io.github.kanylbullen.tung.server

import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EmailVerificationTest {
    private val sent: MutableList<MailMessage> = Collections.synchronizedList(mutableListOf())
    private val google = FakeGoogle().apply { publish("k1") }

    /** The test's clock: [EMAIL_CONFIRM_LIFETIME_MS] and [UNCONFIRMED_HOLD_MS] pass by moving it. */
    @Volatile private var now = System.currentTimeMillis()

    @AfterTest
    fun stop() = google.close()

    private fun verifyTest(admins: Set<String> = emptySet(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application {
            module(
                TestDatabase.config().copy(
                    publicUrl = "https://tung.example.org",
                    mailer = Mailer { sent += it },
                    googleClientIds = listOf(CLIENT_ID),
                    googleJwksUrl = google.jwksUrl,
                    adminEmails = admins,
                    clock = { now },
                ),
            )
        }
        block()
    }

    /** The token of the newest confirmation link sent to [email]. */
    private suspend fun linkTo(email: String, count: Int = 1): String {
        repeat(50) {
            if (sent.count { it.to == email } >= count) return@repeat
            delay(100)
        }
        val text = sent.last { it.to == email }.text
        return Regex("""https://tung\.example\.org/verify-email\?token=(\S+)""").find(text)!!.groupValues[1]
    }

    private suspend fun ApplicationTestBuilder.me(token: String) = client.get("/api/v1/me") { bearerAuth(token) }.json()

    private suspend fun ApplicationTestBuilder.open(link: String) = client.get("/verify-email?token=$link")

    @Test
    fun aNewAccountMaySignInButNotSyncUntilItsLinkIsOpened() = verifyTest {
        val email = uniqueEmail()
        val session = client.register(email, "correct horse battery")
        assertFalse(me(session)["email_verified"]!!.jsonPrimitive.boolean)
        val mail = run { linkTo(email); sent.last { it.to == email } }
        assertEquals("Confirm your email for Tung", mail.subject)

        val sync = client.sync(session)
        assertEquals(HttpStatusCode.Forbidden, sync.status)
        assertEquals("email_not_verified", sync.errorCode())
        for (path in listOf("/api/v1/tokens", "/api/v1/link/start", "/api/v1/commands/start_workout")) {
            val refused = client.post(path) { bearerAuth(session); jsonBody("""{"name": "x", "scope": "read"}""") }
            assertEquals(HttpStatusCode.Forbidden, refused.status, path)
            assertEquals("email_not_verified", refused.errorCode(), path)
        }

        val page = open(linkTo(email))
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("Your email is confirmed" in page.bodyAsText())
        assertTrue(me(session)["email_verified"]!!.jsonPrimitive.boolean)
        client.synced(session)
        // Opened again (a mail scanner may have opened it first): still confirmed.
        assertEquals(HttpStatusCode.OK, open(linkTo(email)).status)
    }

    @Test
    fun aNewLinkSpendsTheOldOneAndAskingIsLimited() = verifyTest {
        val email = uniqueEmail()
        val session = client.register(email, "correct horse battery")
        val first = linkTo(email)
        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/auth/verify/resend") { bearerAuth(session) }.status)
        val second = linkTo(email, count = 2)
        assertNotEquals(first, second)
        assertTrue("Confirm your email for Tung" == sent.last { it.to == email }.subject)
        assertEquals(HttpStatusCode.BadRequest, open(first).status)

        repeat(CONFIRM_RESENDS_PER_HOUR - 1) {
            assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/auth/verify/resend") { bearerAuth(session) }.status)
        }
        val limited = client.post("/api/v1/auth/verify/resend") { bearerAuth(session) }
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)

        val last = linkTo(email, count = 1 + CONFIRM_RESENDS_PER_HOUR)
        assertEquals(HttpStatusCode.OK, open(last).status)
        // Confirmed: asking again sends nothing.
        val before = sent.count { it.to == email }
        now += 2 * 60 * 60 * 1000L
        assertEquals(HttpStatusCode.Accepted, client.post("/api/v1/auth/verify/resend") { bearerAuth(session) }.status)
        delay(300)
        assertEquals(before, sent.count { it.to == email })
    }

    @Test
    fun aLinkExpiresAfterAWeek() = verifyTest {
        val email = uniqueEmail()
        val session = client.register(email, "correct horse battery")
        val link = linkTo(email)
        now += EMAIL_CONFIRM_LIFETIME_MS + 1
        val page = open(link)
        assertEquals(HttpStatusCode.BadRequest, page.status)
        assertTrue("This link does not work" in page.bodyAsText())
        assertFalse(me(session)["email_verified"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun anAddressNobodyConfirmedGivesWayAfterADay() = verifyTest {
        val email = uniqueEmail()
        val squatter = client.register(email, "the squatter's password")
        val again = client.post("/api/v1/auth/register") { jsonBody("""{"email": "$email", "password": "the owner's password"}""") }
        assertEquals(HttpStatusCode.Conflict, again.status)

        now += UNCONFIRMED_HOLD_MS + 1
        val owner = client.register(email, "the owner's password")
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/me") { bearerAuth(squatter) }.status)
        assertEquals(email, me(owner)["email"]!!.jsonPrimitive.content)

        // A confirmed address never gives way.
        assertEquals(HttpStatusCode.OK, open(linkTo(email, count = 2)).status)
        now += UNCONFIRMED_HOLD_MS + 1
        val late = client.post("/api/v1/auth/register") { jsonBody("""{"email": "$email", "password": "someone else's"}""") }
        assertEquals(HttpStatusCode.Conflict, late.status)
    }

    @Test
    fun googleConfirmsAnAddressAndAConfirmedAccountKeepsItsPassword() = verifyTest {
        // Unconfirmed: Google's owner gets the account, as before.
        val unconfirmed = uniqueEmail()
        client.register(unconfirmed, "the squatter's password")
        val taken = client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "${google.token(email = unconfirmed)}"}""") }
        assertEquals(HttpStatusCode.OK, taken.status)
        assertTrue(taken.json()["user"]!!.jsonObject["email_verified"]!!.jsonPrimitive.boolean)
        assertFalse(taken.json()["user"]!!.jsonObject["has_password"]!!.jsonPrimitive.boolean)

        // Confirmed by its link: the same person, who keeps the password.
        val confirmed = uniqueEmail()
        client.register(confirmed, "correct horse battery")
        open(linkTo(confirmed))
        val linked = client.post("/api/v1/auth/google") { jsonBody("""{"id_token": "${google.token(email = confirmed)}"}""") }
        assertEquals(HttpStatusCode.OK, linked.status)
        assertTrue(linked.json()["user"]!!.jsonObject["has_password"]!!.jsonPrimitive.boolean)
        val login = client.post("/api/v1/auth/login") { jsonBody("""{"email": "$confirmed", "password": "correct horse battery"}""") }
        assertEquals(HttpStatusCode.OK, login.status)
    }

    @Test
    fun anAdminAddressCountsOnceItIsConfirmed() {
        val admin = uniqueEmail()
        verifyTest(admins = setOf(admin)) {
            val session = client.register(admin, "correct horse battery")
            assertFalse(me(session)["is_admin"]!!.jsonPrimitive.boolean)
            open(linkTo(admin))
            assertTrue(me(session)["is_admin"]!!.jsonPrimitive.boolean)
        }
    }
}
