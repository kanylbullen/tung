package io.github.kanylbullen.tung.server

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.TextColumnType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Link codes (issue #30): a signed-in device asks for a code, a watch trades it for a token. */
class LinkTest {

    private suspend fun HttpClient.start(token: String): HttpResponse = post("/api/v1/link/start") { bearerAuth(token) }

    private suspend fun HttpClient.claim(code: String): HttpResponse =
        post("/api/v1/link/claim") { jsonBody("""{"code": "$code"}""") }

    private suspend fun HttpClient.startCode(token: String): String {
        val response = start(token)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.json()["code"]!!.str
    }

    @Test
    fun aCodeGivesAWatchTokenOnceAndOnlyOnce() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val before = System.currentTimeMillis()

        val started = client.start(session)
        assertEquals(HttpStatusCode.Created, started.status, started.bodyAsText())
        val body = started.json()
        val code = body["code"]!!.str
        assertTrue(Regex("^[0-9]{6}$").matches(code), code)
        val expiresAt = body["expires_at"]!!.jsonPrimitive.long
        assertTrue(expiresAt in before + 10 * 60_000L..System.currentTimeMillis() + 10 * 60_000L, "valid ten minutes")

        val claimed = client.claim(code)
        assertEquals(HttpStatusCode.OK, claimed.status, claimed.bodyAsText())
        val linked = claimed.json()
        val watchToken = linked["token"]!!.str
        assertTrue(watchToken.startsWith("tung_"))
        assertEquals(email, linked["user"]!!.jsonObject["email"]!!.str)

        // The new token is the same user's, may write, and is listed as "watch".
        assertEquals(email, client.get("/api/v1/me") { bearerAuth(watchToken) }.json()["email"]!!.str)
        val tokens = client.get("/api/v1/tokens") { bearerAuth(session) }.json()["tokens"]!!.jsonArray.map { it.jsonObject }
        val watch = tokens.single { it["name"]!!.str == "watch" }
        assertEquals("write", watch["scope"]!!.str)
        assertEquals(HttpStatusCode.Created, client.start(watchToken).status)

        val again = client.claim(code)
        assertEquals(HttpStatusCode.NotFound, again.status)
        assertEquals("invalid_code", again.errorCode())
    }

    @Test
    fun anExpiredCodeIsInvalid() = tungTest {
        val code = client.startCode(client.register())
        Db.connect(TestDatabase.config()).use { db ->
            db.tx {
                exec(
                    "UPDATE link_codes SET expires_at = ? WHERE code = ?",
                    listOf(
                        org.jetbrains.exposed.sql.LongColumnType() to System.currentTimeMillis() - 1,
                        TextColumnType() to code,
                    ),
                )
            }
        }
        val response = client.claim(code)
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("invalid_code", response.errorCode())
    }

    @Test
    fun unknownAndMalformedCodesAreInvalid() = tungTest {
        for (code in listOf("000000x", "12345", "abcdef", "")) {
            val response = client.claim(code)
            assertEquals(HttpStatusCode.NotFound, response.status, code)
            assertEquals("invalid_code", response.errorCode())
        }
        val malformed = client.post("/api/v1/link/claim") { jsonBody("""{"nope": 1}""") }
        assertEquals(HttpStatusCode.BadRequest, malformed.status)
    }

    @Test
    fun startNeedsAWriteToken() = tungTest {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/v1/link/start").status)
        val session = client.register()
        val created = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "reader", "scope": "read"}""")
        }
        val readToken = created.json()["token"]!!.str
        val refused = client.start(readToken)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertEquals("insufficient_scope", refused.errorCode())
    }

    @Test
    fun claimsAreRateLimitedPerAddress() = tungTest {
        repeat(10) {
            assertEquals(HttpStatusCode.NotFound, client.claim("%06d".format(it)).status)
        }
        // The eleventh attempt in a minute is refused even with a valid code.
        val code = client.startCode(client.register())
        val limited = client.claim(code)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", limited.errorCode())
    }

    @Test
    fun startIsRateLimitedPerUser() = tungTest {
        val first = client.register()
        val codes = (1..5).map { client.startCode(first) }
        assertEquals(5, codes.toSet().size, "every code is new")
        val limited = client.start(first)
        assertEquals(HttpStatusCode.TooManyRequests, limited.status)
        assertEquals("rate_limited", limited.errorCode())
        // Another user has a budget of their own.
        assertEquals(HttpStatusCode.Created, client.start(client.register()).status)
    }
}
