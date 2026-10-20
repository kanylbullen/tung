package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.PUSH_ENDPOINTS_PATH
import io.github.kanylbullen.tung.core.sync.PushTransport.FCM
import io.github.kanylbullen.tung.core.sync.PushTransport.UNIFIED_PUSH
import io.github.kanylbullen.tung.server.push.MAX_ENDPOINTS_PER_USER
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private suspend fun HttpClient.deleteEndpoint(token: String, id: String): HttpResponse =
    delete("$PUSH_ENDPOINTS_PATH/$id") { bearerAuth(token) }

/** `/api/v1/push/endpoints`: register, replace, list, revoke, per user. */
class PushEndpointsTest {

    @Test
    fun registerListReplaceAndRevoke() = tungTest {
        val token = client.register()
        val created = client.registerEndpoint(token, "phone-1", UNIFIED_PUSH, "https://ntfy.sh/upAbc123?up=1")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val first = client.endpoints(token).single()
        assertEquals("phone-1", first.deviceId)
        assertEquals(UNIFIED_PUSH, first.transport)
        assertEquals("https://ntfy.sh/upAbc123?up=1", first.endpoint)
        assertNull(first.lastUsedAt)
        assertTrue(json(created.bodyAsText()).containsKey("device_id"), "snake_case on the wire")

        // The same device again (it moved to FCM): replaced in place, same id, 200.
        val replaced = client.registerEndpoint(token, "phone-1", FCM, "fcm-token-xyz")
        assertEquals(HttpStatusCode.OK, replaced.status, replaced.bodyAsText())
        val second = client.endpoints(token).single()
        assertEquals(first.id, second.id)
        assertEquals(FCM to "fcm-token-xyz", second.transport to second.endpoint)
        assertEquals(first.createdAt, second.createdAt)

        client.registered(token, "watch-1", UNIFIED_PUSH, "http://192.168.1.20:8080/upWatch")
        assertEquals(listOf("phone-1", "watch-1"), client.endpoints(token).map { it.deviceId })

        assertEquals(HttpStatusCode.NoContent, client.deleteEndpoint(token, first.id).status)
        assertEquals(listOf("watch-1"), client.endpoints(token).map { it.deviceId })
        assertEquals(HttpStatusCode.NotFound, client.deleteEndpoint(token, first.id).status, "already revoked")
        assertEquals(HttpStatusCode.NotFound, client.deleteEndpoint(token, id()).status)

        // A revoked device that registers again gets its row back.
        val back = client.registerEndpoint(token, "phone-1", UNIFIED_PUSH, "https://ntfy.sh/upNew")
        assertEquals(HttpStatusCode.OK, back.status, back.bodyAsText())
        assertEquals(first.id, client.endpoints(token).single { it.deviceId == "phone-1" }.id)
    }

    @Test
    fun anotherUserCanNeitherSeeNorRevokeAnEndpoint() = tungTest {
        val alice = client.register()
        val bob = client.register()
        val endpoint = client.registered(alice, "phone", FCM, "alice-token")
        assertEquals(emptyList(), client.endpoints(bob))
        val response = client.deleteEndpoint(bob, endpoint.id)
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("not_found", response.errorCode())
        assertEquals(listOf(endpoint.id), client.endpoints(alice).map { it.id })

        // The same device id under another user is another row.
        client.registered(bob, "phone", FCM, "bob-token")
        assertEquals("alice-token", client.endpoints(alice).single().endpoint)
    }

    @Test
    fun invalidRegistrationsAreRefused() = tungTest {
        val token = client.register()
        val bad = listOf(
            Triple(" ", UNIFIED_PUSH, "https://ntfy.sh/up1"),
            Triple("x".repeat(129), UNIFIED_PUSH, "https://ntfy.sh/up1"),
            Triple("phone", "apns", "token"),
            Triple("phone", FCM, ""),
            Triple("phone", FCM, "a token with spaces"),
            Triple("phone", FCM, "t".repeat(4097)),
            Triple("phone", UNIFIED_PUSH, "not a url"),
            Triple("phone", UNIFIED_PUSH, "ftp://ntfy.sh/up1"),
            Triple("phone", UNIFIED_PUSH, "http://ntfy.sh/up1"),
            Triple("phone", UNIFIED_PUSH, "http://10.0.0.5.example.org/up1"),
            Triple("phone", UNIFIED_PUSH, "https://user:pw@ntfy.sh/up1"),
        )
        for ((device, transport, endpoint) in bad) {
            val response = client.registerEndpoint(token, device, transport, endpoint)
            assertEquals(HttpStatusCode.BadRequest, response.status, "$device / $transport / $endpoint: ${response.bodyAsText()}")
        }
        assertEquals(emptyList(), client.endpoints(token))

        // Plain http is fine on the home network and on this machine.
        client.registered(token, "a", UNIFIED_PUSH, "http://localhost:2586/upA")
        client.registered(token, "b", UNIFIED_PUSH, "http://10.1.2.3/upB")
        client.registered(token, "c", UNIFIED_PUSH, "http://[fd00::1]:2586/upC")
    }

    @Test
    fun aReadTokenMayListButNotRegisterOrRevoke() = tungTest {
        val session = client.register()
        val endpoint = client.registered(session, "phone", FCM, "token-1")
        val reader = client.post("/api/v1/tokens") {
            bearerAuth(session)
            jsonBody("""{"name": "reader", "scope": "read"}""")
        }.json()["token"]!!.str

        val register = client.registerEndpoint(reader, "tablet", FCM, "token-2")
        assertEquals(HttpStatusCode.Forbidden, register.status)
        assertEquals("insufficient_scope", register.errorCode())
        assertEquals(1, client.endpoints(reader).size)
        val revoke = client.deleteEndpoint(reader, endpoint.id)
        assertEquals(HttpStatusCode.Forbidden, revoke.status)
        assertEquals("insufficient_scope", revoke.errorCode())

        assertEquals(HttpStatusCode.Unauthorized, client.registerEndpoint("tung_nope", "x", FCM, "t").status)
    }

    @Test
    fun aUserHasABoundedNumberOfEndpoints() = tungTest {
        val token = client.register()
        repeat(MAX_ENDPOINTS_PER_USER) { client.registered(token, "device-$it", FCM, "token-$it") }
        val tooMany = client.registerEndpoint(token, "one-more", FCM, "token-x")
        assertEquals(HttpStatusCode.Conflict, tooMany.status)
        assertEquals("too_many_endpoints", tooMany.errorCode())
        // A registered device may still replace its own endpoint.
        assertEquals(HttpStatusCode.OK, client.registerEndpoint(token, "device-0", FCM, "token-new").status)
    }
}
