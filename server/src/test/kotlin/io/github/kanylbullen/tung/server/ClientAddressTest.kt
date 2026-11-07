package io.github.kanylbullen.tung.server

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ClientAddressTest {
    @Test
    fun anIpv4AddressIsItsOwnKey() {
        assertEquals("203.0.113.7", addressKey("203.0.113.7"))
        assertEquals("127.0.0.1", addressKey("127.0.0.1"))
    }

    @Test
    fun anIpv6AddressCountsByItsSlash64() {
        assertEquals("2001:db8:1:2::/64", addressKey("2001:0db8:0001:0002:aaaa:bbbb:cccc:dddd"))
        assertEquals("2001:db8:1:2::/64", addressKey("2001:db8:1:2::1"))
        assertEquals("2001:db8:1:2::/64", addressKey("2001:DB8:1:2:ffff::"))
        assertEquals("2001:db8:0:0::/64", addressKey("2001:db8::7"))
        assertEquals("0:0:0:0::/64", addressKey("::1"))
        assertEquals("2001:db8:1:2::/64", addressKey("[2001:db8:1:2::1]"), "bracketed")
        assertEquals("fe80:0:0:0::/64", addressKey("fe80::1%eth0"), "with a scope")
    }

    @Test
    fun anIpv4MappedAddressIsTheIpv4Address() {
        assertEquals("203.0.113.7", addressKey("::ffff:203.0.113.7"))
        assertEquals("203.0.113.7", addressKey("::ffff:cb00:7107"))
    }

    @Test
    fun anythingElseIsUsedAsItIs() {
        assertEquals("localhost", addressKey("localhost"))
        assertEquals("not an address", addressKey("not an address"))
        assertEquals("1:2:3", addressKey("1:2:3"))
        assertEquals("gg::1", addressKey("gg::1"))
        assertEquals("", addressKey(""))
    }

    @Test
    fun theHeaderIsAHeaderNameAndNeedsTheProxy() {
        val base = mapOf("DATABASE_URL" to "jdbc:postgresql://localhost/tung")
        assertNull(ServerConfig.fromEnv(base).clientIpHeader)
        assertEquals(
            "CF-Connecting-IP",
            ServerConfig.fromEnv(base + ("TUNG_TRUST_PROXY" to "true") + ("TUNG_CLIENT_IP_HEADER" to "CF-Connecting-IP")).clientIpHeader,
        )
        assertFailsWith<ConfigException> { ServerConfig.fromEnv(base + ("TUNG_CLIENT_IP_HEADER" to "CF-Connecting-IP")) }
        assertFailsWith<ConfigException> {
            ServerConfig.fromEnv(base + ("TUNG_TRUST_PROXY" to "true") + ("TUNG_CLIENT_IP_HEADER" to "CF Connecting IP"))
        }
        assertFailsWith<ConfigException> {
            ServerConfig.fromEnv(base + ("TUNG_TRUST_PROXY" to "true") + ("TUNG_CLIENT_IP_HEADER" to "X-Real-IP:"))
        }
    }

    private suspend fun ApplicationTestBuilder.login(setup: HttpRequestBuilder.() -> Unit) =
        client.post("/api/v1/auth/login") {
            setup(this)
            jsonBody("""{"email": "${uniqueEmail()}", "password": "whatever123"}""")
        }.status

    @Test
    fun withTheHeaderEachClientHasItsOwnBudgetWhateverXForwardedForSays() =
        tungTest(authRequestsPerMinute = 2, trustProxy = true, clientIpHeader = "CF-Connecting-IP") {
            // Behind a tunnel every request has the same X-Forwarded-For: the tunnel's own address.
            fun HttpRequestBuilder.from(client: String) {
                header("X-Forwarded-For", "172.18.0.5")
                header("CF-Connecting-IP", client)
            }
            assertEquals(HttpStatusCode.Unauthorized, login { from("203.0.113.20") })
            assertEquals(HttpStatusCode.Unauthorized, login { from("203.0.113.20") })
            assertEquals(HttpStatusCode.TooManyRequests, login { from("203.0.113.20") })
            assertEquals(HttpStatusCode.Unauthorized, login { from("198.51.100.20") })
            // Another address in the same IPv6 /64 is the same client.
            assertEquals(HttpStatusCode.Unauthorized, login { from("2001:db8:5:6::1") })
            assertEquals(HttpStatusCode.Unauthorized, login { from("2001:db8:5:6::2") })
            assertEquals(HttpStatusCode.TooManyRequests, login { from("2001:db8:5:6:ffff::9") })
            assertEquals(HttpStatusCode.Unauthorized, login { from("2001:db8:5:7::1") })
        }

    @Test
    fun withoutTheHeaderXForwardedForIsNotBelieved() =
        tungTest(authRequestsPerMinute = 2, trustProxy = true, clientIpHeader = "CF-Connecting-IP") {
            // A request that bypassed Cloudflare counts under the connection's address, whatever it forwards.
            assertEquals(HttpStatusCode.Unauthorized, login { header("X-Forwarded-For", "203.0.113.31") })
            assertEquals(HttpStatusCode.Unauthorized, login { header("X-Forwarded-For", "203.0.113.32") })
            assertEquals(HttpStatusCode.TooManyRequests, login { header("X-Forwarded-For", "203.0.113.33") })
            // A client behind Cloudflare still has its own budget.
            assertEquals(HttpStatusCode.Unauthorized, login { header("CF-Connecting-IP", "203.0.113.34") })
        }
}
