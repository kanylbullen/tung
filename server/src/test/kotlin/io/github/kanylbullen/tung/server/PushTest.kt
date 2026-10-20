package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.DEVICE_HEADER
import io.github.kanylbullen.tung.core.sync.PushTransport.FCM
import io.github.kanylbullen.tung.core.sync.PushTransport.UNIFIED_PUSH
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.server.push.FCM_SCOPE
import io.github.kanylbullen.tung.server.push.FcmServiceAccount
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URLDecoder
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Push delivery (issue #34): who gets the empty "sync now", when, and over which transport. */
class PushTest {

    @Test
    fun aSyncFromOneDevicePushesEveryOtherDeviceOnce() = FakeHttp().use { distributor ->
        pushTest { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")

            client.syncFrom(token, device = "A")
            push.awaitIdle()

            val sent = distributor.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/up/B", sent.path)
            assertEquals("""{"type":"sync"}""", sent.body)
            assertTrue(sent.headers["content-type"].orEmpty().startsWith("application/json"), sent.headers.toString())
            val b = client.endpoints(token).single { it.deviceId == "B" }
            assertNotNull(b.lastUsedAt, "a push that was accepted stamps last_used_at")
            assertNull(client.endpoints(token).single { it.deviceId == "A" }.lastUsedAt)
        }
    }

    @Test
    fun changesInsideTheWindowAreOnePush() = FakeHttp().use { distributor ->
        pushTest(debounceMillis = 1_500) { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")

            repeat(3) { client.syncFrom(token, device = "A") }
            push.awaitIdle()
            assertEquals(listOf("/up/B"), distributor.requests.map { it.path })

            // The window closed: the next change is a push of its own.
            client.syncFrom(token, device = "A")
            push.awaitIdle()
            assertEquals(listOf("/up/B", "/up/B"), distributor.requests.map { it.path })
        }
    }

    @Test
    fun aDeviceIsSkippedOnlyWhenItMadeEveryChangeInTheWindow() = FakeHttp().use { distributor ->
        pushTest(debounceMillis = 1_500) { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")
            client.registered(token, "C", UNIFIED_PUSH, "${distributor.url}/up/C")

            client.syncFrom(token, device = "A")
            client.syncFrom(token, device = "B")
            push.awaitIdle()
            assertEquals(setOf("/up/A", "/up/B", "/up/C"), distributor.requests.map { it.path }.toSet())
            assertEquals(3, distributor.requests.size)
        }
    }

    @Test
    fun anEndpointThatIsGoneIsRevokedAndAFailureIsKept() = FakeHttp().use { distributor ->
        distributor.respond = { request ->
            when (request.path) {
                "/up/B" -> 410 to ""
                "/up/C" -> 500 to "boom"
                else -> 200 to ""
            }
        }
        pushTest { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")
            client.registered(token, "C", UNIFIED_PUSH, "${distributor.url}/up/C")

            client.syncFrom(token, device = "A")
            push.awaitIdle()

            assertEquals(setOf("/up/B", "/up/C"), distributor.requests.map { it.path }.toSet())
            val left = client.endpoints(token)
            assertEquals(listOf("A", "C"), left.map { it.deviceId }, "410 revokes; 500 is logged and the endpoint kept")
            assertNull(left.single { it.deviceId == "C" }.lastUsedAt)
        }
    }

    @Test
    fun aPullOnlySyncPushesNothing() = FakeHttp().use { distributor ->
        pushTest { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")

            client.syncFrom(token, device = "A", changes = SyncChanges())
            client.syncFrom(token, device = null, changes = SyncChanges())
            push.awaitIdle()
            assertEquals(emptyList(), distributor.requests)
        }
    }

    @Test
    fun aCommandWithoutADeviceHeaderPushesEveryDevice() = FakeHttp().use { distributor ->
        pushTest { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")

            client.done(token, "start_workout")
            push.awaitIdle()
            assertEquals(setOf("/up/A", "/up/B"), distributor.requests.map { it.path }.toSet())
            assertEquals(2, distributor.requests.size)
        }
    }

    @Test
    fun restWritesAndMcpToolsPushToo() = FakeHttp().use { distributor ->
        pushTest { push ->
            val token = client.register()
            client.registered(token, "A", UNIFIED_PUSH, "${distributor.url}/up/A")
            client.registered(token, "B", UNIFIED_PUSH, "${distributor.url}/up/B")

            // A REST write from device A wakes only B.
            val put = client.put("/api/v1/workouts/${id()}") {
                bearerAuth(token)
                header(DEVICE_HEADER, "A")
                jsonBody("""{"started_at": 1700000000000}""")
            }
            assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
            push.awaitIdle()
            assertEquals(listOf("/up/B"), distributor.requests.map { it.path })
            distributor.requests.clear()

            // Without the header the writer is nobody's device: both are woken.
            val exercise = client.post("/api/v1/exercises") {
                bearerAuth(token)
                jsonBody("""{"name": "Prowler", "muscle_group": "legs", "equipment": "other"}""")
            }
            assertEquals(HttpStatusCode.Created, exercise.status, exercise.bodyAsText())
            push.awaitIdle()
            assertEquals(setOf("/up/A", "/up/B"), distributor.requests.map { it.path }.toSet())
            distributor.requests.clear()

            val fromA = client.post("/api/v1/commands/start_workout") {
                bearerAuth(token)
                header(DEVICE_HEADER, "A")
                jsonBody("{}")
            }
            assertEquals(HttpStatusCode.OK, fromA.status, fromA.bodyAsText())
            push.awaitIdle()
            assertEquals(listOf("/up/B"), distributor.requests.map { it.path })
            distributor.requests.clear()

            // An MCP tool that changes data: a client without a device, so both.
            val mcp = client.post("/mcp") {
                bearerAuth(token)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                jsonBody("""{"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "create_exercise", "arguments": {"name": "Sled push", "muscle_group": "legs", "equipment": "other"}}}""")
            }
            assertEquals(HttpStatusCode.OK, mcp.status, mcp.bodyAsText())
            assertTrue("isError\":true" !in mcp.bodyAsText(), mcp.bodyAsText())
            push.awaitIdle()
            assertEquals(setOf("/up/A", "/up/B"), distributor.requests.map { it.path }.toSet())
            distributor.requests.clear()

            // A read tool changes nothing.
            client.post("/mcp") {
                bearerAuth(token)
                header(HttpHeaders.Accept, "application/json, text/event-stream")
                jsonBody("""{"jsonrpc": "2.0", "id": 4, "method": "tools/call", "params": {"name": "current_workout", "arguments": {}}}""")
            }
            push.awaitIdle()
            assertEquals(emptyList(), distributor.requests)
        }
    }

    @Test
    fun anotherUsersWritesNeverPushHere() = FakeHttp().use { distributor ->
        pushTest { push ->
            val alice = client.register()
            val bob = client.register()
            client.registered(alice, "alice-phone", UNIFIED_PUSH, "${distributor.url}/up/alice")
            client.registered(bob, "bob-phone", UNIFIED_PUSH, "${distributor.url}/up/bob")

            client.syncFrom(bob, device = null)
            client.done(bob, "start_workout")
            push.awaitIdle()
            assertEquals(listOf("/up/bob"), distributor.requests.map { it.path })
        }
    }

    @Test
    fun withoutAServiceAccountFcmEndpointsAreKeptButNeverSentTo() = FakeHttp().use { fakeFcm ->
        FakeHttp().use { distributor ->
            pushTest(fcm = null, fcmBaseUrl = fakeFcm.url) { push ->
                val token = client.register()
                client.registered(token, "android", FCM, "fcm-token-1")
                client.registered(token, "degoogled", UNIFIED_PUSH, "${distributor.url}/up/D")

                client.done(token, "start_workout")
                push.awaitIdle()

                assertEquals(emptyList(), fakeFcm.requests, "no network call for FCM")
                assertEquals(listOf("/up/D"), distributor.requests.map { it.path }, "the other transport still works")
                assertEquals(setOf("android", "degoogled"), client.endpoints(token).map { it.deviceId }.toSet())
            }
        }
    }

    @Test
    fun fcmSignsInWithTheServiceAccountOnceAndSendsADataMessage() = FakeHttp().use { google ->
        val keys = rsaKeyPair()
        google.respond = { request ->
            when (request.path) {
                "/token" -> 200 to """{"access_token": "ya29.test-token", "expires_in": 3599, "token_type": "Bearer"}"""
                "/v1/projects/tung-test/messages:send" -> 200 to """{"name": "projects/tung-test/messages/1"}"""
                else -> 404 to ""
            }
        }
        val account = FcmServiceAccount.parse(serviceAccountJson(keys, "${google.url}/token"))
        pushTest(fcm = account, fcmBaseUrl = google.url) { push ->
            val token = client.register()
            client.registered(token, "android", FCM, "fcm-token-1")

            client.done(token, "start_workout")
            push.awaitIdle()
            client.done(token, "start_workout")
            push.awaitIdle()

            val tokenRequests = google.to("/token")
            assertEquals(1, tokenRequests.size, "the access token is cached")
            val sends = google.to("/v1/projects/tung-test/messages:send")
            assertEquals(2, sends.size)

            // The token request: the JWT-bearer grant with an RS256 assertion signed by the service account.
            val tokenRequest = tokenRequests.single()
            assertEquals("POST", tokenRequest.method)
            assertTrue(tokenRequest.headers["content-type"].orEmpty().startsWith("application/x-www-form-urlencoded"))
            val form = tokenRequest.body.split('&').associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
            val (header, claims, signature) = assertNotNull(form["assertion"]).split('.')
            val decoder = Base64.getUrlDecoder()
            val headerJson = Json.parseToJsonElement(decoder.decode(header).decodeToString()).jsonObject
            assertEquals("RS256", headerJson["alg"]!!.jsonPrimitive.content)
            assertEquals("JWT", headerJson["typ"]!!.jsonPrimitive.content)
            val claimsJson = Json.parseToJsonElement(decoder.decode(claims).decodeToString()).jsonObject
            assertEquals("push@tung-test.iam.gserviceaccount.com", claimsJson["iss"]!!.jsonPrimitive.content)
            assertEquals(FCM_SCOPE, claimsJson["scope"]!!.jsonPrimitive.content)
            assertEquals("${google.url}/token", claimsJson["aud"]!!.jsonPrimitive.content)
            assertEquals(3600, claimsJson["exp"]!!.jsonPrimitive.long - claimsJson["iat"]!!.jsonPrimitive.long)
            val verified = Signature.getInstance("SHA256withRSA").run {
                initVerify(keys.public)
                update("$header.$claims".toByteArray())
                verify(decoder.decode(signature))
            }
            assertTrue(verified, "the assertion is signed with the service account's key")

            // The send: bearer token and the exact data-only, high-priority message.
            for (send in sends) {
                assertEquals("POST", send.method)
                assertEquals("Bearer ya29.test-token", send.headers["authorization"])
                assertEquals(
                    Json.parseToJsonElement("""{"message": {"token": "fcm-token-1", "data": {"type": "sync"}, "android": {"priority": "high"}}}"""),
                    Json.parseToJsonElement(send.body),
                )
            }
            assertNotNull(client.endpoints(token).single().lastUsedAt)
        }
    }

    @Test
    fun anUnregisteredFcmTokenIsRevoked() = FakeHttp().use { google ->
        google.respond = { request ->
            when (request.path) {
                "/token" -> 200 to """{"access_token": "ya29.t", "expires_in": 3600}"""
                else -> 404 to """{"error": {"code": 404, "status": "NOT_FOUND", "details": [{"errorCode": "UNREGISTERED"}]}}"""
            }
        }
        val account = FcmServiceAccount.parse(serviceAccountJson(rsaKeyPair(), "${google.url}/token"))
        pushTest(fcm = account, fcmBaseUrl = google.url) { push ->
            val token = client.register()
            client.registered(token, "android", FCM, "stale-token")
            client.done(token, "start_workout")
            push.awaitIdle()
            assertEquals(emptyList(), client.endpoints(token))
        }
    }
}
