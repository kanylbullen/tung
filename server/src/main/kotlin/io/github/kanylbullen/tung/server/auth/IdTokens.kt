package io.github.kanylbullen.tung.server.auth

import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.unauthorized
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64

/** Google's signing keys for ID tokens, as a JWK set. `ServerConfig.googleJwksUrl` replaces it in tests. */
const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"

/** Apple's signing keys for Sign in with Apple's ID tokens. `ServerConfig.appleJwksUrl` replaces it in tests. */
const val APPLE_JWKS_URL = "https://appleid.apple.com/auth/keys"

/**
 * A provider whose ID tokens sign users in: Google (issue #51) and Apple.
 * [code] makes the error codes (`invalid_google_token`, `apple_unavailable`)
 * and [issuers] are the `iss` values its tokens may carry.
 */
enum class IdProvider(val label: String, val code: String, val issuers: Set<String>, val jwksUrl: String) {
    GOOGLE("Google", "google", setOf("accounts.google.com", "https://accounts.google.com"), GOOGLE_JWKS_URL),
    APPLE("Apple", "apple", setOf("https://appleid.apple.com"), APPLE_JWKS_URL),
}

/** What a verified ID token says about the user. [email] is trimmed and lower-cased. */
data class IdIdentity(val provider: IdProvider, val sub: String, val email: String?, val emailVerified: Boolean, val name: String?)

/**
 * Verifies [provider]'s ID tokens without a provider library: the JWT
 * must be RS256, issued by the provider to one of [clientIds], unexpired (with
 * [LEEWAY_MS] for clock skew) and carry `iat`; its signature is checked with
 * the key of the provider's JWK set whose `kid` it names. For Apple the client
 * ids are the web's Services ID and the iPhone app's bundle id.
 *
 * The key set is fetched from [jwksUrl] and kept as long as the response's
 * `Cache-Control: max-age` says (an hour without one). A `kid` not in the
 * set fetches it once more, because keys rotate, but at most once a
 * minute, so tokens with made-up kids cannot make the server hammer the provider.
 *
 * Every rejection is a 401 `invalid_<code>_token`; a key set that cannot be
 * fetched is a 503 `<code>_unavailable`, since that is not the token's fault.
 */
class IdTokens(
    val provider: IdProvider,
    val clientIds: List<String>,
    private val jwksUrl: String = provider.jwksUrl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
) {
    init {
        require(clientIds.isNotEmpty()) { "IdTokens needs at least one client id" }
    }

    private class KeySet(val keys: Map<String, PublicKey>, val expiresAt: Long)

    private val mutex = Mutex()
    private var cached: KeySet? = null
    private var lastForcedFetch: Long? = null

    suspend fun verify(idToken: String): IdIdentity {
        val label = provider.label
        val parts = idToken.trim().split('.')
        if (parts.size != 3 || parts.any { it.isEmpty() }) throw invalid("The $label token is not a JWT.")
        val header = decodeJson(parts[0]) ?: throw invalid("The $label token's header cannot be read.")
        val claims = decodeJson(parts[1]) ?: throw invalid("The $label token's claims cannot be read.")
        if (header.string("alg") != "RS256") throw invalid("The $label token is not signed with RS256.")
        val kid = header.string("kid") ?: throw invalid("The $label token names no signing key.")

        if (claims.string("iss") !in provider.issuers) throw invalid("The $label token was not issued by $label.")
        val audiences = when (val aud = claims["aud"]) {
            is JsonPrimitive -> listOfNotNull(aud.contentOrNull)
            is JsonArray -> aud.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptyList()
        }
        if (audiences.none { it in clientIds }) throw invalid("The $label token is for another app.")
        val now = clock()
        val exp = claims.long("exp") ?: throw invalid("The $label token has no expiry.")
        if (exp * 1000 + LEEWAY_MS <= now) throw invalid("The $label token has expired. Sign in again.")
        val iat = claims.long("iat") ?: throw invalid("The $label token has no issue time.")
        if (iat * 1000 - LEEWAY_MS > now) throw invalid("The $label token was issued in the future.")
        val sub = claims.string("sub")?.takeIf { it.isNotBlank() } ?: throw invalid("The $label token names no account.")

        val signature = runCatching { Base64.getUrlDecoder().decode(parts[2]) }.getOrNull()
            ?: throw invalid("The $label token's signature cannot be read.")
        val key = key(kid) ?: throw invalid("The $label token is signed with an unknown key.")
        val valid = runCatching {
            Signature.getInstance("SHA256withRSA").run {
                initVerify(key)
                update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
                verify(signature)
            }
        }.getOrDefault(false)
        if (!valid) throw invalid("The $label token's signature is not valid.")

        val verified = when (val v = claims["email_verified"]) {
            is JsonPrimitive -> v.booleanOrNull ?: (v.contentOrNull == "true")
            else -> false
        }
        return IdIdentity(
            provider = provider,
            sub = sub,
            email = claims.string("email")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() },
            emailVerified = verified,
            name = claims.string("name"),
        )
    }

    /** The key for [kid]: from the cache while it is fresh, else fetched; an unknown kid refetches once (throttled). */
    private suspend fun key(kid: String): PublicKey? = mutex.withLock {
        val now = clock()
        val current = cached?.takeIf { it.expiresAt > now } ?: fetch(now)
        current.keys[kid]?.let { return@withLock it }
        lastForcedFetch?.let { if (now - it < FORCED_REFETCH_INTERVAL_MS) return@withLock null }
        lastForcedFetch = now
        fetch(now).keys[kid]
    }

    private suspend fun fetch(now: Long): KeySet {
        val response = try {
            http.sendAsync(
                HttpRequest.newBuilder(URI(jwksUrl)).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).await()
        } catch (e: Exception) {
            throw unavailable()
        }
        if (response.statusCode() !in 200..299) throw unavailable()
        val keys = parseJwks(response.body()) ?: throw unavailable()
        val maxAge = response.headers().firstValue("Cache-Control").orElse(null)
            ?.let { MAX_AGE.find(it)?.groupValues?.get(1)?.toLongOrNull() }
        val set = KeySet(keys, now + (maxAge?.times(1000) ?: DEFAULT_CACHE_MS))
        cached = set
        return set
    }

    companion object {
        const val LEEWAY_MS = 60_000L
        const val DEFAULT_CACHE_MS = 3_600_000L
        const val FORCED_REFETCH_INTERVAL_MS = 60_000L
        private val MAX_AGE = Regex("""max-age=(\d+)""")
        private val json = Json { ignoreUnknownKeys = true }

        /** The RSA keys of a JWK set by kid; null when the document is not one. */
        fun parseJwks(text: String): Map<String, PublicKey>? {
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val keys = root["keys"] as? JsonArray ?: return null
            val factory = KeyFactory.getInstance("RSA")
            return keys.mapNotNull { element ->
                val jwk = element as? JsonObject ?: return@mapNotNull null
                if (jwk.string("kty") != "RSA") return@mapNotNull null
                val kid = jwk.string("kid") ?: return@mapNotNull null
                val n = jwk.string("n")?.let(::unsigned) ?: return@mapNotNull null
                val e = jwk.string("e")?.let(::unsigned) ?: return@mapNotNull null
                runCatching { kid to factory.generatePublic(RSAPublicKeySpec(n, e)) }.getOrNull()
            }.toMap()
        }

        private fun unsigned(base64url: String): BigInteger? =
            runCatching { BigInteger(1, Base64.getUrlDecoder().decode(base64url)) }.getOrNull()

        private fun decodeJson(part: String): JsonObject? = runCatching {
            json.parseToJsonElement(Base64.getUrlDecoder().decode(part).decodeToString()) as? JsonObject
        }.getOrNull()

        private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong() }
    }

    private fun invalid(message: String) = unauthorized("invalid_${provider.code}_token", message)

    private fun unavailable() = ApiException(
        HttpStatusCode.ServiceUnavailable,
        "${provider.code}_unavailable",
        "${provider.label}'s signing keys could not be fetched. Try again in a minute.",
    )
}
