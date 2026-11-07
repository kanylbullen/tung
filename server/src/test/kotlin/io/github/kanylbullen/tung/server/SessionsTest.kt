package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.PushTransport.UNIFIED_PUSH
import io.github.kanylbullen.tung.server.routes.TesterSignups
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Idle expiry of sessions and watches, `POST /auth/logout/others`, `POST /auth/password`, and the tester sign-up going with the account. */
class SessionsTest {

    private suspend fun HttpClient.login(email: String, password: String = "correct horse battery"): HttpResponse =
        post("/api/v1/auth/login") { jsonBody("""{"email": "$email", "password": "$password"}""") }

    private suspend fun HttpClient.sessionFor(email: String, password: String = "correct horse battery"): String =
        login(email, password).json()["token"]!!.str

    private suspend fun HttpClient.apiToken(session: String): String = post("/api/v1/tokens") {
        bearerAuth(session)
        jsonBody("""{"name": "my script", "scope": "write"}""")
    }.json()["token"]!!.str

    private suspend fun HttpClient.changePassword(token: String, current: String, new: String, device: String? = null) =
        post("/api/v1/auth/password") {
            bearerAuth(token)
            device?.let { header(DEVICE_HEADER, it) }
            jsonBody("""{"current_password": "$current", "new_password": "$new"}""")
        }

    private suspend fun HttpClient.registerPush(token: String, device: String) {
        val response = registerEndpoint(token, device, UNIFIED_PUSH, "https://ntfy.sh/up${id().take(8)}")
        assertTrue(response.status.value in 200..299, "push registration: ${response.status}")
    }

    private suspend fun liveEndpoints(userId: String): List<String> = Db.connect(TestDatabase.config()).use { db ->
        db.tx {
            PushEndpoints.selectAll().where { (PushEndpoints.userId eq userId) }
                .filter { it[PushEndpoints.revokedAt] == null }.map { it[PushEndpoints.deviceId] }
        }
    }

    private suspend fun HttpClient.userId(token: String): String = authedGet("/api/v1/me", token).json()["id"]!!.str

    @Test
    fun aSessionNobodyUsedFor180DaysStopsWorking() = tungTest {
        val email = uniqueEmail()
        val stale = client.register(email)
        val fresh = client.sessionFor(email)
        val script = client.apiToken(fresh)
        val now = System.currentTimeMillis()
        Db.connect(TestDatabase.config()).use { db ->
            db.tx {
                ApiTokens.update({ ApiTokens.tokenHash eq Tokens.hash(stale) }) { it[lastUsedAt] = now - SESSION_IDLE_MS - 1000 }
                // A made API token is the user's own to manage: idle as long, it keeps working.
                ApiTokens.update({ ApiTokens.tokenHash eq Tokens.hash(script) }) { it[lastUsedAt] = now - SESSION_IDLE_MS - 1000 }
                // Just inside the window still works.
                ApiTokens.update({ ApiTokens.tokenHash eq Tokens.hash(fresh) }) { it[lastUsedAt] = now - SESSION_IDLE_MS + 60_000 }
            }
        }

        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", stale).status)
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", fresh).status)
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", script).status)
        // And it is revoked, so it does not come back to life by being "used".
        Db.connect(TestDatabase.config()).use { db ->
            assertNotNull(db.tx { ApiTokens.selectAll().where { ApiTokens.tokenHash eq Tokens.hash(stale) }.single()[ApiTokens.revokedAt] })
        }
    }

    @Test
    fun theListSaysWhenASessionStopsWorking() = tungTest {
        val session = client.register()
        val script = client.apiToken(session)
        val tokens = client.authedGet("/api/v1/tokens", session).json()["tokens"]!!.jsonArray.map { it.jsonObject }
        val own = tokens.single { it["current"]!!.jsonPrimitive.boolean }
        val made = tokens.single { it["name"]!!.str == "my script" }
        val lastUse = (own["last_used_at"]!!.jsonPrimitive.longOrNull ?: own["created_at"]!!.jsonPrimitive.long)
        assertEquals(lastUse + SESSION_IDLE_MS, own["expires_at"]!!.jsonPrimitive.long)
        assertEquals(JsonNull, made["expires_at"], "a made token never expires")
        assertTrue(script.isNotEmpty())
    }

    @Test
    fun aNeverUsedSessionCountsFromWhenItWasMade() = tungTest {
        val stale = client.register()
        Db.connect(TestDatabase.config()).use { db ->
            db.tx {
                ApiTokens.update({ ApiTokens.tokenHash eq Tokens.hash(stale) }) {
                    it[createdAt] = System.currentTimeMillis() - SESSION_IDLE_MS - 1000
                    it[lastUsedAt] = null
                }
            }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", stale).status)
    }

    @Test
    fun signingOutOthersKeepsOnlyTheCallerAndItsDevice() = tungTest {
        val email = uniqueEmail()
        val phone = client.register(email)
        val laptop = client.sessionFor(email)
        val script = client.apiToken(phone)
        // A session nobody used for 180 days is not "signed out" by this: it already does not work.
        val idle = client.sessionFor(email)
        Db.connect(TestDatabase.config()).use { db ->
            db.tx { ApiTokens.update({ ApiTokens.tokenHash eq Tokens.hash(idle) }) { it[lastUsedAt] = System.currentTimeMillis() - SESSION_IDLE_MS - 1000 } }
        }
        client.registerPush(phone, "phone-device")
        client.registerPush(laptop, "laptop-device")
        val userId = client.userId(phone)

        val response = client.post("/api/v1/auth/logout/others") {
            bearerAuth(phone)
            header(DEVICE_HEADER, "phone-device")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(2, response.json()["revoked_tokens"]!!.jsonPrimitive.int)

        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", phone).status)
        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", laptop).status)
        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", script).status)
        assertEquals(listOf("phone-device"), liveEndpoints(userId))
    }

    @Test
    fun onlyASessionMaySignOthersOutOrChangeThePassword() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val script = client.apiToken(session)

        val signOut = client.post("/api/v1/auth/logout/others") { bearerAuth(script) }
        assertEquals(HttpStatusCode.Forbidden, signOut.status)
        assertEquals("session_required", signOut.errorCode())
        val change = client.changePassword(script, "correct horse battery", "a brand new password")
        assertEquals(HttpStatusCode.Forbidden, change.status)
        assertEquals("session_required", change.errorCode())

        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", session).status)
        assertEquals(HttpStatusCode.OK, client.login(email).status)
    }

    @Test
    fun changingThePasswordNeedsTheCurrentOneAndSignsOthersOut() = tungTest {
        val email = uniqueEmail()
        val phone = client.register(email)
        val laptop = client.sessionFor(email)

        val wrong = client.changePassword(phone, "not the password", "a brand new password")
        assertEquals(HttpStatusCode.Forbidden, wrong.status)
        assertEquals("wrong_password", wrong.errorCode())
        val short = client.changePassword(phone, "correct horse battery", "short")
        assertEquals(HttpStatusCode.BadRequest, short.status)
        assertEquals("weak_password", short.errorCode())
        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", laptop).status, "a refused change ends nothing")

        assertEquals(HttpStatusCode.NoContent, client.changePassword(phone, "correct horse battery", "a brand new password").status)

        assertEquals(HttpStatusCode.OK, client.authedGet("/api/v1/me", phone).status)
        assertEquals(HttpStatusCode.Unauthorized, client.authedGet("/api/v1/me", laptop).status)
        assertEquals(HttpStatusCode.Unauthorized, client.login(email).status)
        assertEquals(HttpStatusCode.OK, client.login(email, "a brand new password").status)
    }

    @Test
    fun deletingTheAccountDeletesItsTesterSignUp() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val stranger = uniqueEmail()
        Db.connect(TestDatabase.config()).use { db ->
            db.tx {
                for (address in listOf(email, stranger)) {
                    TesterSignups.insert {
                        it[TesterSignups.email] = address
                        it[createdAt] = System.currentTimeMillis()
                    }
                }
            }
        }
        val deleted = client.delete("/api/v1/me") {
            bearerAuth(session)
            jsonBody("""{"email": "$email"}""")
        }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        Db.connect(TestDatabase.config()).use { db ->
            db.tx {
                assertNull(TesterSignups.selectAll().where { TesterSignups.email eq email }.singleOrNull())
                assertNotNull(TesterSignups.selectAll().where { TesterSignups.email eq stranger }.singleOrNull())
            }
        }
    }
}
