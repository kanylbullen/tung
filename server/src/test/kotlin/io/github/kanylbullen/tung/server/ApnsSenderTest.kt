package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.PushTransport.APNS
import io.github.kanylbullen.tung.core.sync.PushTransport.UNIFIED_PUSH
import io.github.kanylbullen.tung.server.push.APNS_PRODUCTION_URL
import io.github.kanylbullen.tung.server.push.APNS_SANDBOX_URL
import io.github.kanylbullen.tung.server.push.ApnsConfig
import io.github.kanylbullen.tung.server.push.ApnsSender
import io.github.kanylbullen.tung.server.push.PushOutcome
import io.github.kanylbullen.tung.server.push.pushHttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import java.security.KeyPair
import java.security.Signature
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** APNs: the request, the ES256 provider token, the answers, the configuration. */
class ApnsSenderTest {

    private fun config(keys: KeyPair, topic: String = "io.github.kanylbullen.tung") =
        ApnsConfig("ABC123DEFG", "TEAM456789", ApnsConfig.parseKey(p8Pem(keys)), topic)

    private fun decode(part: String) = Json.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()).jsonObject

    @Test
    fun aSilentBackgroundPushWithAnEs256ProviderToken() = FakeHttp().use { apple ->
        val keys = ecKeyPair()
        val now = 1_800_000_000_000L
        val device = apnsDeviceToken()
        val sender = ApnsSender(config(keys), pushHttpClient(), apple.url, clock = { now })
        runBlocking { assertEquals(PushOutcome.Sent, sender.send(device)) }

        val request = apple.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/3/device/$device", request.path)
        assertEquals("io.github.kanylbullen.tung", request.headers["apns-topic"])
        assertEquals("background", request.headers["apns-push-type"])
        assertEquals("5", request.headers["apns-priority"])
        assertEquals("0", request.headers["apns-expiration"])
        assertEquals("application/json", request.headers["content-type"])
        assertEquals(Json.parseToJsonElement("""{"aps":{"content-available":1}}"""), Json.parseToJsonElement(request.body))

        val authorization = assertNotNull(request.headers["authorization"])
        assertTrue(authorization.startsWith("bearer "), authorization)
        val (header, claims, signature) = authorization.removePrefix("bearer ").split('.')
        val headerJson = decode(header)
        assertEquals("ES256", headerJson["alg"]!!.jsonPrimitive.content)
        assertEquals("ABC123DEFG", headerJson["kid"]!!.jsonPrimitive.content)
        val claimsJson = decode(claims)
        assertEquals("TEAM456789", claimsJson["iss"]!!.jsonPrimitive.content)
        assertEquals(now / 1000, claimsJson["iat"]!!.jsonPrimitive.long)

        // JOSE's ES256 signature is r||s, 64 bytes, not DER; it verifies with the key's public half.
        val raw = Base64.getUrlDecoder().decode(signature)
        assertEquals(64, raw.size)
        val verified = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initVerify(keys.public)
            update("$header.$claims".toByteArray())
            verify(raw)
        }
        assertTrue(verified, "signed with the auth key")
        // The same signature as DER verifies with the plain algorithm too.
        val der = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public)
            update("$header.$claims".toByteArray())
            verify(toDer(raw))
        }
        assertTrue(der)
    }

    @Test
    fun answers() = FakeHttp().use { apple ->
        var answer = 200 to ""
        apple.respond = { answer }
        val sender = ApnsSender(config(ecKeyPair()), pushHttpClient(), apple.url)
        val device = apnsDeviceToken()
        runBlocking {
            assertEquals(PushOutcome.Sent, sender.send(device))
            answer = 410 to """{"reason":"Unregistered","timestamp":1800000000000}"""
            assertEquals(PushOutcome.Gone, sender.send(device))
            answer = 400 to """{"reason":"BadDeviceToken"}"""
            assertEquals(PushOutcome.Gone, sender.send(device))
            answer = 400 to """{"reason":"DeviceTokenNotForTopic"}"""
            assertEquals(PushOutcome.Gone, sender.send(device))
            answer = 400 to """{"reason":"BadPriority"}"""
            val bad = assertIs<PushOutcome.Failed>(sender.send(device))
            assertTrue("BadPriority" in bad.reason && device !in bad.reason, bad.reason)
            answer = 500 to """{"reason":"InternalServerError"}"""
            assertIs<PushOutcome.Failed>(sender.send(device))
            answer = 429 to """{"reason":"TooManyRequests"}"""
            assertIs<PushOutcome.Failed>(sender.send(device))
            answer = 200 to "x".repeat(4 * 1024 * 1024)
            assertEquals(PushOutcome.Sent, sender.send(device), "a long answer is not read to the end")
        }
        assertEquals(1, apple.requests.map { it.headers["authorization"] }.toSet().size, "one provider token for all of these")
    }

    @Test
    fun anEndpointThatIsNotADeviceTokenIsNeverSent() = FakeHttp().use { apple ->
        val sender = ApnsSender(config(ecKeyPair()), pushHttpClient(), apple.url)
        runBlocking {
            assertIs<PushOutcome.Failed>(sender.send("../../evil"))
            assertIs<PushOutcome.Failed>(sender.send("ab".repeat(16)))
        }
        assertEquals(emptyList(), apple.requests)
    }

    @Test
    fun theProviderTokenIsReusedFor50MinutesAndRenewedAfterAnExpiredOne() = FakeHttp().use { apple ->
        var now = 1_800_000_000_000L
        var answer = 200 to ""
        apple.respond = { answer }
        val sender = ApnsSender(config(ecKeyPair()), pushHttpClient(), apple.url, clock = { now })
        val device = apnsDeviceToken()
        fun jwts() = apple.requests.map { assertNotNull(it.headers["authorization"]) }
        runBlocking {
            sender.send(device)
            now += 49 * 60 * 1000L
            sender.send(device)
            assertEquals(1, jwts().toSet().size, "reused within 50 minutes")

            now += 2 * 60 * 1000L
            sender.send(device)
            assertEquals(2, jwts().toSet().size, "a new one after 50 minutes")
            assertEquals((now / 1000), decode(jwts().last().removePrefix("bearer ").split('.')[1])["iat"]!!.jsonPrimitive.long)

            now += 1000
            answer = 403 to """{"reason":"ExpiredProviderToken"}"""
            assertIs<PushOutcome.Failed>(sender.send(device))
            answer = 200 to ""
            now += 1000
            assertEquals(PushOutcome.Sent, sender.send(device))
            val all = jwts()
            assertNotEquals(all[all.size - 2], all.last(), "the refused token is dropped")

            answer = 403 to """{"reason":"InvalidProviderToken"}"""
            now += 1000
            assertIs<PushOutcome.Failed>(sender.send(device))
            answer = 200 to ""
            now += 1000
            sender.send(device)
            val after = jwts()
            assertNotEquals(after[after.size - 2], after.last())

            answer = 403 to """{"reason":"BadCertificateEnvironment"}"""
            now += 1000
            assertIs<PushOutcome.Failed>(sender.send(device))
            answer = 200 to ""
            sender.send(device)
            val last = jwts()
            assertEquals(last[last.size - 2], last.last(), "another 403 keeps the token")
        }
    }

    @Test
    fun configuration() {
        val keys = ecKeyPair()
        val pem = p8Pem(keys)
        val base = mapOf("DATABASE_URL" to "jdbc:postgresql://x/y")
        val apnsEnv = mapOf("TUNG_APNS_KEY" to pem, "TUNG_APNS_KEY_ID" to "ABC123DEFG", "TUNG_APNS_TEAM_ID" to "TEAM456789")
        assertNull(ServerConfig.fromEnv(base).apns, "absent: APNs off")

        val inline = assertNotNull(ServerConfig.fromEnv(base + apnsEnv).apns)
        assertEquals("ABC123DEFG" to "TEAM456789", inline.keyId to inline.teamId)
        assertEquals("io.github.kanylbullen.tung", inline.topic)
        assertEquals(APNS_PRODUCTION_URL, inline.baseUrl)
        assertTrue("PRIVATE" !in ServerConfig.fromEnv(base + apnsEnv).toString(), "the key is never printed")

        val sandbox = assertNotNull(ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_SANDBOX" to "true") + ("TUNG_APNS_TOPIC" to "org.example.tung")).apns)
        assertEquals(APNS_SANDBOX_URL, sandbox.baseUrl)
        assertEquals("org.example.tung", sandbox.topic)

        val file = File.createTempFile("AuthKey_ABC123DEFG", ".p8").apply { deleteOnExit(); writeText(pem) }
        assertNotNull(ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_KEY" to file.path)).apns)
        val oneLine = pem.trimEnd().replace("\n", "\\n")
        assertNotNull(ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_KEY" to oneLine)).apns, "an env file's one-line PEM")

        val partial = assertFailsWith<ConfigException> { ServerConfig.fromEnv(base + apnsEnv - "TUNG_APNS_TEAM_ID") }
        assertTrue("TUNG_APNS_TEAM_ID" in partial.message.orEmpty(), partial.message)
        assertFailsWith<ConfigException> { ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_SANDBOX" to "maybe")) }
        val missing = assertFailsWith<ConfigException> { ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_KEY" to "/no/such/AuthKey.p8")) }
        assertTrue("/no/such/AuthKey.p8" in missing.message.orEmpty())
        val notEc = assertFailsWith<ConfigException> { ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_KEY" to p8Pem(rsaKeyPair()))) }
        assertTrue("BEGIN" !in notEc.message.orEmpty(), "the key never appears in a message")
        assertFailsWith<ConfigException> {
            ServerConfig.fromEnv(base + apnsEnv + ("TUNG_APNS_KEY" to "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"))
        }
    }

    @Test
    fun aChangeWakesAnIphone() = FakeHttp().use { apple ->
        FakeHttp().use { distributor ->
            pushTest(apns = config(ecKeyPair()), apnsBaseUrl = apple.url) { push ->
                val token = client.register()
                client.registered(token, "iphone", APNS, apnsDeviceToken(1))
                client.registered(token, "stale-iphone", APNS, apnsDeviceToken(2))
                client.registered(token, "android", UNIFIED_PUSH, "${distributor.url}/up/A")
                apple.respond = { request ->
                    if (request.path.endsWith(apnsDeviceToken(2))) 410 to """{"reason":"Unregistered"}""" else 200 to ""
                }

                client.done(token, "start_workout")
                push.awaitIdle()

                assertEquals(setOf("/3/device/${apnsDeviceToken(1)}", "/3/device/${apnsDeviceToken(2)}"), apple.requests.map { it.path }.toSet())
                assertEquals(listOf("/up/A"), distributor.requests.map { it.path })
                assertEquals(setOf("iphone", "android"), client.endpoints(token).map { it.deviceId }.toSet(), "the unregistered token is revoked")
            }
        }
    }

    @Test
    fun withoutAKeyApnsEndpointsAreKeptButNeverSentTo() = FakeHttp().use { apple ->
        pushTest(apns = null, apnsBaseUrl = apple.url) { push ->
            val token = client.register()
            client.registered(token, "iphone", APNS, apnsDeviceToken())
            client.done(token, "start_workout")
            push.awaitIdle()
            assertEquals(emptyList(), apple.requests)
            assertEquals(listOf("iphone"), client.endpoints(token).map { it.deviceId })
        }
    }

    /** A raw r||s ECDSA signature as ASN.1 DER. */
    private fun toDer(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val trimmed = bytes.dropWhile { it == 0.toByte() }.toByteArray().let { if (it.isEmpty()) byteArrayOf(0) else it }
            val value = if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, 32)) + integer(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
