package io.github.kanylbullen.tung.server.push

import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.server.ConfigException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** APNs for TestFlight and App Store builds. */
const val APNS_PRODUCTION_URL = "https://api.push.apple.com"

/** APNs for development builds installed from Xcode (`TUNG_APNS_SANDBOX=true`). */
const val APNS_SANDBOX_URL = "https://api.sandbox.push.apple.com"

/** The iPhone app's bundle id, the default `apns-topic`. */
const val DEFAULT_APNS_TOPIC = "io.github.kanylbullen.tung"

/** The body of every APNs push: a silent background push, no alert, no content. */
internal const val APNS_BODY = """{"aps":{"content-available":1}}"""

/** An APNs device token: hex, as the iPhone registers it. */
private val DEVICE_TOKEN = Regex("[0-9a-fA-F]{64,200}")

/** Whether [endpoint] has the shape of an APNs device token. */
fun isApnsDeviceToken(endpoint: String): Boolean = DEVICE_TOKEN.matches(endpoint)

/**
 * The APNs settings: the auth key (a `.p8` from the Apple Developer account,
 * Keys, with Apple Push Notifications service enabled), its key id, the team
 * id, the topic (the app's bundle id) and whether to use the sandbox. Not a
 * data class: [toString] must not print the key.
 */
class ApnsConfig(
    val keyId: String,
    val teamId: String,
    val privateKey: PrivateKey,
    val topic: String = DEFAULT_APNS_TOPIC,
    val sandbox: Boolean = false,
) {
    val baseUrl: String get() = if (sandbox) APNS_SANDBOX_URL else APNS_PRODUCTION_URL

    override fun toString() = "ApnsConfig(key_id=$keyId, team_id=$teamId, topic=$topic, sandbox=$sandbox)"

    companion object {
        /**
         * Reads the `.p8` [pem] (PKCS#8, an EC P-256 key); a
         * [ConfigException] naming [source] when it is not one, never
         * quoting the key.
         */
        fun parseKey(pem: String, source: String = "TUNG_APNS_KEY"): PrivateKey {
            // An env file cannot hold a multi-line value; accept the PEM with literal \n too.
            val text = pem.replace("\\n", "\n")
            if (!text.contains("-----BEGIN PRIVATE KEY-----") || !text.contains("-----END PRIVATE KEY-----")) {
                throw ConfigException("$source is not a PKCS#8 PEM (-----BEGIN PRIVATE KEY-----). It must be the APNs auth key (.p8), as a path to the file or its contents.")
            }
            val base64 = text.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----")
                .filterNot { it.isWhitespace() }
            val key = try {
                KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)))
            } catch (e: Exception) {
                throw ConfigException("$source is not an EC private key. It must be the APNs auth key (.p8) from the Apple Developer account.")
            }
            if ((key as? ECPrivateKey)?.params?.curve?.field?.fieldSize != 256) {
                throw ConfigException("$source is not a P-256 key. It must be the APNs auth key (.p8) from the Apple Developer account.")
            }
            return key
        }
    }
}

/**
 * Apple Push Notification service: `POST {base}/3/device/{token}` over
 * HTTP/2 with a silent background push, `{"aps":{"content-available":1}}`
 * (`apns-push-type: background`, priority 5, no expiry), so the iPhone wakes
 * and syncs, as an Android phone does on an FCM data message.
 *
 * Token-based authentication: an ES256 JWT (`kid` the key id; `iss` the
 * team id, `iat` now) signed with the auth key, done by hand with
 * `java.security`. Apple refuses one older than an hour and throttles one
 * renewed more often than every 20 minutes, so it is kept for
 * [JWT_REUSE_MS] and dropped on a 403 `ExpiredProviderToken` or
 * `InvalidProviderToken`, so the next push makes a new one.
 *
 * APNs answers: 200 sent; 410, or a reason `BadDeviceToken`,
 * `DeviceTokenNotForTopic` or `Unregistered`, gone (the app was removed, or
 * the token belongs to another environment or app); anything else failed.
 */
class ApnsSender(
    private val config: ApnsConfig,
    private val http: HttpClient,
    baseUrl: String = config.baseUrl,
    private val clock: () -> Long = System::currentTimeMillis,
) : PushSender {
    override val transport = PushTransport.APNS
    override val enabled = true

    private val base = baseUrl.trimEnd('/')
    private val mutex = Mutex()
    private var cached: ProviderToken? = null

    private class ProviderToken(val value: String, val issuedAt: Long)

    override suspend fun send(endpoint: String): PushOutcome {
        // Registration checks the shape; checked again because the token goes into the path.
        if (!isApnsDeviceToken(endpoint)) return PushOutcome.Failed("the endpoint is not an APNs device token")
        val token = providerToken()
        val request = HttpRequest.newBuilder(URI("$base/3/device/$endpoint"))
            .timeout(PUSH_TIMEOUT)
            .header("authorization", "bearer ${token.value}")
            .header("apns-topic", config.topic)
            .header("apns-push-type", "background")
            .header("apns-priority", "5")
            .header("apns-expiration", "0")
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(APNS_BODY))
            .build()
        val (status, body) = try {
            withTimeoutOrNull(PUSH_TIMEOUT.toMillis()) {
                val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
                // The answer is `{"reason": "..."}` at most; no more than 1 KiB of it is read.
                val bytes = runInterruptible(Dispatchers.IO) { response.body().use { it.readNBytes(MAX_RESPONSE_BYTES) } }
                response.statusCode() to bytes.decodeToString()
            } ?: return PushOutcome.Failed("APNs gave no answer within ${PUSH_TIMEOUT.seconds} s")
        } catch (e: HttpTimeoutException) {
            return PushOutcome.Failed("APNs gave no answer within ${PUSH_TIMEOUT.seconds} s")
        } catch (e: IOException) {
            return PushOutcome.Failed("APNs unreachable: ${describe(e, endpoint)}")
        }
        val reason = apnsReason(body)
        return when {
            status == 200 -> PushOutcome.Sent
            status == 410 || reason in GONE_REASONS -> PushOutcome.Gone
            status == 403 && (reason == "ExpiredProviderToken" || reason == "InvalidProviderToken") -> {
                mutex.withLock { if (cached === token) cached = null }
                PushOutcome.Failed("APNs refused the provider token ($reason); the next push makes a new one")
            }
            else -> PushOutcome.Failed("APNs answered HTTP $status${reason?.let { " ($it)" }.orEmpty()}")
        }
    }

    /** The cached provider token, or a new one once it is [JWT_REUSE_MS] old. */
    private suspend fun providerToken(): ProviderToken = mutex.withLock {
        val now = clock()
        cached?.takeIf { now - it.issuedAt < JWT_REUSE_MS }?.let { return@withLock it }
        ProviderToken(jwt(now), now).also { cached = it }
    }

    /** The ES256 JWT: the JOSE signature is r||s, 64 bytes, which the P1363 format gives instead of DER. */
    private fun jwt(nowMillis: Long): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("kid", config.keyId)
        }
        val claims = buildJsonObject {
            put("iss", config.teamId)
            put("iat", nowMillis / 1000)
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val signingInput = encoder.encodeToString(header.toString().toByteArray()) + "." +
            encoder.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withECDSAinP1363Format").run {
            initSign(config.privateKey)
            update(signingInput.toByteArray())
            sign()
        }
        return signingInput + "." + encoder.encodeToString(signature)
    }

    companion object {
        /** How long one provider token is used: under Apple's hour, over its 20-minute throttle. */
        const val JWT_REUSE_MS = 50 * 60 * 1000L

        private val GONE_REASONS = setOf("BadDeviceToken", "DeviceTokenNotForTopic", "Unregistered")
    }
}

/** The `reason` of an APNs error body, or null. */
internal fun apnsReason(body: String): String? =
    runCatching { ((Json.parseToJsonElement(body) as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull }.getOrNull()
