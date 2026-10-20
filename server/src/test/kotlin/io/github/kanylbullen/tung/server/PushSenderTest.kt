package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.server.push.FcmSender
import io.github.kanylbullen.tung.server.push.FcmServiceAccount
import io.github.kanylbullen.tung.server.push.PushOutcome
import io.github.kanylbullen.tung.server.push.UnifiedPushSender
import io.github.kanylbullen.tung.server.push.pushHttpClient
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The two senders' answers to what FCM and a distributor can say, and the FCM configuration. No database. */
class PushSenderTest {

    @Test
    fun unifiedPushOutcomes() = FakeHttp().use { distributor ->
        distributor.respond = { request ->
            when (request.path) {
                "/ok" -> 201 to ""
                "/gone" -> 410 to ""
                "/missing" -> 404 to ""
                "/moved" -> 302 to ""
                else -> 503 to ""
            }
        }
        val sender = UnifiedPushSender(pushHttpClient())
        runBlocking {
            assertEquals(PushOutcome.Sent, sender.send("${distributor.url}/ok"))
            assertEquals(PushOutcome.Gone, sender.send("${distributor.url}/gone"))
            assertEquals(PushOutcome.Gone, sender.send("${distributor.url}/missing"))
            assertIs<PushOutcome.Failed>(sender.send("${distributor.url}/moved"), "redirects are not followed")
            assertIs<PushOutcome.Failed>(sender.send("${distributor.url}/down"))
            val closedPort = ServerSocket(0).use { it.localPort }
            val unreachable = sender.send("http://127.0.0.1:$closedPort/up/secret-capability")
            assertIs<PushOutcome.Failed>(unreachable)
            assertTrue("secret-capability" !in unreachable.reason, unreachable.reason)
        }
    }

    @Test
    fun fcmOutcomesAndTokenRefreshAfter401() = FakeHttp().use { google ->
        var sendStatus = 200 to "{}"
        google.respond = { request ->
            if (request.path == "/token") 200 to """{"access_token": "tok-${google.to("/token").size}", "expires_in": 3600}""" else sendStatus
        }
        val account = FcmServiceAccount.parse(serviceAccountJson(rsaKeyPair(), "${google.url}/token", projectId = "p"))
        val sender = FcmSender(account, pushHttpClient(), google.url)
        runBlocking {
            assertEquals(PushOutcome.Sent, sender.send("t"))
            sendStatus = 400 to """{"error": {"status": "INVALID_ARGUMENT", "details": [{"errorCode": "UNREGISTERED"}]}}"""
            assertEquals(PushOutcome.Gone, sender.send("t"))
            sendStatus = 400 to """{"error": {"status": "INVALID_ARGUMENT"}}"""
            assertIs<PushOutcome.Failed>(sender.send("t"))
            sendStatus = 404 to ""
            assertEquals(PushOutcome.Gone, sender.send("t"))
            sendStatus = 500 to ""
            assertIs<PushOutcome.Failed>(sender.send("t"))
            assertEquals(1, google.to("/token").size, "one token for all of these")

            sendStatus = 401 to """{"error": {"status": "UNAUTHENTICATED"}}"""
            assertIs<PushOutcome.Failed>(sender.send("t"))
            sendStatus = 200 to "{}"
            assertEquals(PushOutcome.Sent, sender.send("t"))
            assertEquals(2, google.to("/token").size, "a 401 drops the cached token")
            assertEquals("Bearer tok-2", google.to("/v1/projects/p/messages:send").last().headers["authorization"])
        }
    }

    @Test
    fun aFailingTokenEndpointIsAFailedPush() = FakeHttp().use { google ->
        google.respond = { 400 to """{"error": "invalid_grant"}""" }
        val account = FcmServiceAccount.parse(serviceAccountJson(rsaKeyPair(), "${google.url}/token"))
        val outcome = runBlocking { FcmSender(account, pushHttpClient(), google.url).send("t") }
        assertIs<PushOutcome.Failed>(outcome)
        assertTrue(google.to("/v1/projects/tung-test/messages:send").isEmpty())
    }

    @Test
    fun theServiceAccountComesInlineOrFromAFile() {
        val json = serviceAccountJson(rsaKeyPair(), "https://oauth2.googleapis.com/token")
        val base = mapOf("DATABASE_URL" to "jdbc:postgresql://x/y")
        assertNull(ServerConfig.fromEnv(base).fcmServiceAccount, "absent: FCM off")
        assertNull(ServerConfig.fromEnv(base + ("TUNG_FCM_SERVICE_ACCOUNT_JSON" to " ")).fcmServiceAccount, "empty: FCM off")

        val inline = assertNotNull(ServerConfig.fromEnv(base + ("TUNG_FCM_SERVICE_ACCOUNT_JSON" to json)).fcmServiceAccount)
        assertEquals("tung-test", inline.projectId)
        assertEquals("push@tung-test.iam.gserviceaccount.com", inline.clientEmail)
        assertTrue("PRIVATE" !in inline.toString() && "PRIVATE" !in ServerConfig.fromEnv(base + ("TUNG_FCM_SERVICE_ACCOUNT_JSON" to json)).toString())

        val file = File.createTempFile("tung-sa", ".json").apply { deleteOnExit(); writeText(json) }
        val fromFile = assertNotNull(ServerConfig.fromEnv(base + ("TUNG_FCM_SERVICE_ACCOUNT_JSON" to file.path)).fcmServiceAccount)
        assertEquals("tung-test", fromFile.projectId)
    }

    @Test
    fun aBrokenServiceAccountStopsTheServer() {
        val base = mapOf("DATABASE_URL" to "jdbc:postgresql://x/y")
        fun config(value: String) = ServerConfig.fromEnv(base + ("TUNG_FCM_SERVICE_ACCOUNT_JSON" to value))
        val missing = assertFailsWith<ConfigException> { config("/no/such/service-account.json") }
        assertTrue("/no/such/service-account.json" in missing.message.orEmpty(), missing.message)
        assertFailsWith<ConfigException> { config("{not json") }
        assertFailsWith<ConfigException> { config("""{"type": "service_account", "project_id": "p", "client_email": "e"}""") }
        val badKey = assertFailsWith<ConfigException> {
            config("""{"type": "service_account", "project_id": "p", "client_email": "e", "private_key": "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"}""")
        }
        assertTrue("AAAA" !in badKey.message.orEmpty(), "the key never appears in a message")
        assertFailsWith<ConfigException> { config("""{"type": "authorized_user", "project_id": "p"}""") }
    }
}
