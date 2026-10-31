package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.server.routes.keepBlocks
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The admin's account list: every account with how it signs in and a few counts, for the admins only. */
class AdminTest {

    private val admin = uniqueEmail()

    private fun adminTest(testerSignups: Boolean = false, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment { config = MapApplicationConfig() }
        application { module(TestDatabase.config().copy(adminEmails = setOf(admin), testerSignups = testerSignups)) }
        block()
    }

    @Test
    fun anAdminSeesEveryAccountWithItsCounts() = adminTest {
        val adminToken = client.register(admin)
        val member = uniqueEmail()
        val memberToken = client.register(member)
        client.putWorkout(memberToken, id(), """{"started_at": 1000, "ended_at": 2000}""")
        client.putWorkout(memberToken, id(), """{"started_at": 5000, "ended_at": 6000}""")
        client.putWorkout(memberToken, id(), """{"started_at": 9000}""") // in progress: not counted
        client.authedGet("/api/v1/me", memberToken) // the token is used
        val idle = uniqueEmail()
        client.register(idle)

        val response = client.get("/api/v1/admin/users") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.json()
        assertFalse(body["tester_signups"]!!.jsonPrimitive.boolean)
        assertTrue(body["user_count"]!!.jsonPrimitive.int >= 3)
        val users = body["users"]!!.jsonArray.map { it.jsonObject }.associateBy { it["email"]!!.str }

        val row = users.getValue(member)
        assertEquals(2, row["workouts"]!!.jsonPrimitive.int)
        assertEquals(5000L, row["last_workout_at"]!!.jsonPrimitive.long)
        assertTrue(row["has_password"]!!.jsonPrimitive.boolean)
        assertFalse(row["google_linked"]!!.jsonPrimitive.boolean)
        assertFalse(row["is_admin"]!!.jsonPrimitive.boolean)
        assertEquals(1, row["active_tokens"]!!.jsonPrimitive.int)
        assertTrue(row["last_seen_at"]!!.jsonPrimitive.long > 0)
        assertFalse("note" in row || "password_hash" in row, "no training content and no secrets")

        assertTrue(users.getValue(admin)["is_admin"]!!.jsonPrimitive.boolean)
        assertEquals(0, users.getValue(idle)["workouts"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, users.getValue(idle)["last_workout_at"])

        val newestFirst = body["users"]!!.jsonArray.map { it.jsonObject["created_at"]!!.jsonPrimitive.long }
        assertEquals(newestFirst.sortedDescending(), newestFirst)
    }

    @Test
    fun meSaysWhoIsAnAdminAndOnlyAdminsGetTheList() = adminTest(testerSignups = true) {
        val adminToken = client.register(admin)
        val memberToken = client.register()
        assertTrue(client.authedGet("/api/v1/me", adminToken).json()["is_admin"]!!.jsonPrimitive.boolean)
        assertFalse(client.authedGet("/api/v1/me", memberToken).json()["is_admin"]!!.jsonPrimitive.boolean)

        val refused = client.get("/api/v1/admin/users") { bearerAuth(memberToken) }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("not_admin", refused.errorCode())
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/admin/users").status)
        assertTrue(client.get("/api/v1/admin/users") { bearerAuth(adminToken) }.json()["tester_signups"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun aReadTokenOfAnAdminGetsNoAccounts() = adminTest(testerSignups = true) {
        val adminToken = client.register(admin)
        val created = client.post("/api/v1/tokens") {
            bearerAuth(adminToken)
            jsonBody("""{"name": "assistant", "scope": "read"}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val readToken = created.json()["token"]!!.str
        val users = client.get("/api/v1/admin/users") { bearerAuth(readToken) }
        assertEquals(HttpStatusCode.Forbidden, users.status)
        assertEquals("insufficient_scope", users.errorCode())
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/testers") { bearerAuth(readToken) }.status)
    }

    @Test
    fun withNoAdminsNobodyIsOne() = tungTest {
        val token = client.register()
        assertFalse(client.authedGet("/api/v1/me", token).json()["is_admin"]!!.jsonPrimitive.boolean)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/v1/admin/users") { bearerAuth(token) }.status)
    }

    @Test
    fun keepBlocksKeepsOneVariantAndDropsTheOther() {
        val html = "a\n  <!-- x:begin -->\n  X\n  <!-- x:end -->\n  <!-- no-x:begin -->\n  Y\n  <!-- no-x:end -->\nb"
        assertEquals("a\n  X\nb", keepBlocks(html, keep = "x", drop = "no-x"))
        assertEquals("a\n  Y\nb", keepBlocks(html, keep = "no-x", drop = "x"))
    }
}
