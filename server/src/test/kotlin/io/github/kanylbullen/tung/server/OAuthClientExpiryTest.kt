package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.server.oauth.AUTH_NONE
import io.github.kanylbullen.tung.server.oauth.OAuthClients
import io.github.kanylbullen.tung.server.oauth.OAuthRefreshTokens
import io.github.kanylbullen.tung.server.oauth.OAuthStore
import io.github.kanylbullen.tung.server.oauth.OAuthStore.Companion.UNUSED_CLIENT_LIFETIME_MS
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY_MS = 24 * 3_600_000L

class OAuthClientExpiryTest {

    private suspend fun Db.lastUsedAt(clientId: String): Long? =
        tx { OAuthClients.selectAll().where { OAuthClients.id eq clientId }.single()[OAuthClients.lastUsedAt] }

    private suspend fun Db.clientExists(clientId: String): Boolean =
        tx { OAuthClients.selectAll().where { OAuthClients.id eq clientId }.any() }

    @Test
    fun authorizeAndTokenCallsStampLastUsedAt() = tungTest {
        Db.connect(TestDatabase.config()).use { db ->
            val driver = OAuthDriver(this)
            driver.account()
            val clientId = driver.registerClient()
            assertNull(db.lastUsedAt(clientId), "registering is not a use")

            val pkce = Pkce()
            val code = driver.code(clientId, pkce)
            val afterAuthorize = assertNotNull(db.lastUsedAt(clientId), "authorize stamps it")

            // Move the stamp back, so the token call visibly moves it forward again.
            db.tx { OAuthClients.update({ OAuthClients.id eq clientId }) { it[lastUsedAt] = afterAuthorize - DAY_MS } }
            val exchanged = driver.exchange(clientId, code, pkce.verifier)
            assertEquals(HttpStatusCode.OK, exchanged.status)
            val afterToken = assertNotNull(db.lastUsedAt(clientId))
            assertTrue(afterToken >= afterAuthorize, "token stamps it: $afterToken")

            db.tx { OAuthClients.update({ OAuthClients.id eq clientId }) { it[lastUsedAt] = afterAuthorize - DAY_MS } }
            assertEquals(HttpStatusCode.OK, driver.refresh(clientId, exchanged.json()["refresh_token"]!!.str).status)
            assertTrue(assertNotNull(db.lastUsedAt(clientId)) >= afterAuthorize, "a refresh is a token call too")
        }
    }

    @Test
    fun theSweepDeletesOnlyAgedClientsWithoutALiveGrant() = tungTest {
        Db.connect(TestDatabase.config()).use { db ->
            val userId = client.authedGet("/api/v1/me", client.register()).json()["id"]!!.str
            val start = System.currentTimeMillis()
            var now = start
            val store = OAuthStore(db) { now }
            suspend fun grant(clientId: String) {
                val client = assertNotNull(store.client(clientId))
                val pkce = Pkce()
                val code = store.createCode(clientId, userId, REDIRECT, "read write", pkce.challenge, null)
                store.exchangeCode(client, code, REDIRECT, pkce.verifier)
            }

            // Registered at the start and never used again.
            val unused = store.register("Unused", listOf(REDIRECT), AUTH_NONE).first.id
            // Registered at the start; its grant from then has run out by the sweep (30 days access, 90 refresh).
            val expired = store.register("Expired", listOf(REDIRECT), AUTH_NONE).first.id
            grant(expired)
            // Registered at the start and holding a token issued 80 days later, still live at the sweep.
            val granted = store.register("Granted", listOf(REDIRECT), AUTH_NONE).first.id
            now = start + 80 * DAY_MS
            grant(granted)
            // Registered 85 days in: not old enough.
            now = start + 85 * DAY_MS
            val fresh = store.register("Fresh", listOf(REDIRECT), AUTH_NONE).first.id
            // Registered at the start but used 85 days in (an authorize or token call).
            now = start
            val used = store.register("Used", listOf(REDIRECT), AUTH_NONE).first.id
            now = start + 85 * DAY_MS
            store.markUsed(used)

            val sweepAt = start + UNUSED_CLIENT_LIFETIME_MS + DAY_MS
            assertTrue(store.sweepUnusedClients(sweepAt) >= 2)

            assertTrue(!db.clientExists(unused), "aged and never granted: deleted")
            assertTrue(!db.clientExists(expired), "aged, and its grant has run out: deleted")
            assertTrue(db.clientExists(granted), "aged but holding a live token: kept")
            assertTrue(db.clientExists(fresh), "not 90 days old: kept")
            assertTrue(db.clientExists(used), "used within 90 days: kept")
            val orphans = db.tx { OAuthRefreshTokens.selectAll().where { OAuthRefreshTokens.clientId eq expired }.count() }
            assertEquals(0L, orphans, "the deleted client's refresh tokens went with it")

            // Once the last grant has run out too, the next sweep takes it.
            assertTrue(store.sweepUnusedClients(start + 200 * DAY_MS) >= 1)
            assertTrue(!db.clientExists(granted))
        }
    }
}
