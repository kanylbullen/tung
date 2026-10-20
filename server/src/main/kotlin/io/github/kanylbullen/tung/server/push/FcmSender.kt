package io.github.kanylbullen.tung.server.push

import io.github.kanylbullen.tung.core.sync.PushMessage
import io.github.kanylbullen.tung.core.sync.PushTransport
import io.github.kanylbullen.tung.server.ConfigException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/** Where FCM's HTTP v1 API lives; `ServerConfig.fcmBaseUrl` replaces it in tests. */
const val FCM_BASE_URL = "https://fcm.googleapis.com"

/** Google's token endpoint, when a service-account file has no `token_uri`. */
const val GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token"

/** The one scope FCM's v1 API needs. */
const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"

/**
 * A Firebase service-account key (the JSON file from the Firebase console,
 * Project settings, Service accounts). Not a data class: [toString] must not
 * print the private key.
 */
class FcmServiceAccount(
    val projectId: String,
    val clientEmail: String,
    val privateKey: PrivateKey,
    val tokenUri: String,
) {
    override fun toString() = "FcmServiceAccount(project_id=$projectId, client_email=$clientEmail)"

    companion object {
        /** Parses the service-account JSON; [ConfigException] naming what is wrong (never the key). */
        fun parse(json: String, source: String = "TUNG_FCM_SERVICE_ACCOUNT_JSON"): FcmServiceAccount {
            val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
                ?: throw ConfigException("$source is not a JSON object. It must be a Firebase service-account key, as a path to the file or the JSON itself.")
            fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String): String = field(name)
                ?: throw ConfigException("$source has no \"$name\". It must be a Firebase service-account key (type service_account).")
            val type = field("type")
            if (type != null && type != "service_account") {
                throw ConfigException("$source is a \"$type\" key, not a service-account key (type service_account).")
            }
            val projectId = required("project_id")
            val clientEmail = required("client_email")
            val key = try {
                parsePkcs8Pem(required("private_key"))
            } catch (e: ConfigException) {
                throw e
            } catch (e: Exception) {
                throw ConfigException("$source has a private_key that is not a PEM-encoded PKCS#8 RSA key.")
            }
            val tokenUri = field("token_uri") ?: GOOGLE_TOKEN_URL
            val scheme = runCatching { URI(tokenUri).scheme?.lowercase() }.getOrNull()
            if (scheme != "https" && scheme != "http") throw ConfigException("$source has a token_uri that is not an http(s) URL.")
            return FcmServiceAccount(projectId, clientEmail, key, tokenUri)
        }

        private fun parsePkcs8Pem(pem: String): PrivateKey {
            if (!pem.contains("BEGIN PRIVATE KEY")) {
                throw ConfigException("TUNG_FCM_SERVICE_ACCOUNT_JSON has a private_key that is not a PKCS#8 PEM (-----BEGIN PRIVATE KEY-----).")
            }
            val base64 = pem.substringAfter("-----BEGIN PRIVATE KEY-----").substringBefore("-----END PRIVATE KEY-----")
                .filterNot { it.isWhitespace() }
            val der = Base64.getDecoder().decode(base64)
            return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        }
    }
}

/**
 * FCM's HTTP v1 API: `POST {base}/v1/projects/{project}/messages:send` with
 * a data-only, high-priority message `{"type":"sync"}` to the registration
 * token, authorised by an OAuth access token from the service account.
 *
 * The access token comes from Google's service-account JWT flow, done by
 * hand to need no Google library: a JWT (RS256, `iss` the client email,
 * `scope` [FCM_SCOPE], `aud` the token URI, one hour) signed with the
 * account's key is traded at the token URI for an access token, which is
 * kept until a minute before it expires. A 401 from FCM drops it, so the
 * next push fetches a new one.
 *
 * FCM answers: 2xx sent; 404, or 400/404 whose error is `UNREGISTERED` or
 * `NOT_FOUND`, gone (the app was uninstalled or the token rotated); anything
 * else failed.
 */
class FcmSender(
    private val account: FcmServiceAccount,
    private val http: HttpClient,
    baseUrl: String = FCM_BASE_URL,
    private val clock: () -> Long = System::currentTimeMillis,
) : PushSender {
    override val transport = PushTransport.FCM
    override val enabled = true

    private val sendUrl = URI("${baseUrl.trimEnd('/')}/v1/projects/${account.projectId}/messages:send")
    private val mutex = Mutex()
    private var cached: AccessToken? = null

    private class AccessToken(val value: String, val expiresAt: Long)

    override suspend fun send(endpoint: String): PushOutcome {
        val token = try {
            accessToken()
        } catch (e: TokenException) {
            return PushOutcome.Failed(e.message)
        }
        val request = HttpRequest.newBuilder(sendUrl)
            .timeout(PUSH_TIMEOUT)
            .header("Authorization", "Bearer ${token.value}")
            .header("Content-Type", "application/json; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(message(endpoint).toString()))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: HttpTimeoutException) {
            return PushOutcome.Failed("FCM gave no answer within ${PUSH_TIMEOUT.seconds} s")
        } catch (e: IOException) {
            return PushOutcome.Failed("FCM unreachable: ${describe(e, endpoint)}")
        }
        val status = response.statusCode()
        return when {
            status in 200..299 -> PushOutcome.Sent
            status == 401 -> {
                mutex.withLock { if (cached === token) cached = null }
                PushOutcome.Failed("FCM refused the access token (HTTP 401); the next push fetches a new one")
            }
            status == 404 -> PushOutcome.Gone
            status == 400 && errorCodes(response.body()).any { it == "UNREGISTERED" || it == "NOT_FOUND" } -> PushOutcome.Gone
            else -> PushOutcome.Failed("FCM answered HTTP $status${errorCodes(response.body()).takeIf { it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty()}")
        }
    }

    private fun message(token: String): JsonObject = buildJsonObject {
        putJsonObject("message") {
            put("token", token)
            putJsonObject("data") { put("type", PushMessage.TYPE_SYNC) }
            putJsonObject("android") { put("priority", "high") }
        }
    }

    /** The cached access token, or a new one when it expires within a minute. */
    private suspend fun accessToken(): AccessToken = mutex.withLock {
        cached?.takeIf { clock() < it.expiresAt - TOKEN_MARGIN_MS }?.let { return@withLock it }
        fetchToken().also { cached = it }
    }

    private suspend fun fetchToken(): AccessToken {
        val now = clock()
        val form = "grant_type=" + URLEncoder.encode(JWT_BEARER_GRANT, Charsets.UTF_8) +
            "&assertion=" + URLEncoder.encode(assertion(now), Charsets.UTF_8)
        val request = HttpRequest.newBuilder(URI(account.tokenUri))
            .timeout(PUSH_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = try {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: HttpTimeoutException) {
            throw TokenException("the token endpoint gave no answer within ${PUSH_TIMEOUT.seconds} s")
        } catch (e: IOException) {
            throw TokenException("the token endpoint is unreachable: ${e.javaClass.simpleName}")
        }
        if (response.statusCode() !in 200..299) {
            throw TokenException("the token endpoint answered HTTP ${response.statusCode()}; check the service-account key")
        }
        val body = runCatching { Json.parseToJsonElement(response.body()) as? JsonObject }.getOrNull()
        val value = body?.get("access_token")?.jsonPrimitive?.contentOrNull
            ?: throw TokenException("the token endpoint's answer has no access_token")
        val expiresIn = body["expires_in"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TOKEN_SECONDS
        return AccessToken(value, now + expiresIn * 1000)
    }

    /** The signed JWT for the token request. */
    private fun assertion(nowMillis: Long): String {
        val iat = nowMillis / 1000
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
        }
        val claims = buildJsonObject {
            put("iss", account.clientEmail)
            put("scope", FCM_SCOPE)
            put("aud", account.tokenUri)
            put("iat", iat)
            put("exp", iat + JWT_LIFETIME_SECONDS)
        }
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val signingInput = encoder.encodeToString(header.toString().toByteArray()) + "." +
            encoder.encodeToString(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(account.privateKey)
            update(signingInput.toByteArray())
            sign()
        }
        return signingInput + "." + encoder.encodeToString(signature)
    }

    private class TokenException(override val message: String) : Exception(message)

    private companion object {
        const val JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val JWT_LIFETIME_SECONDS = 3600L
        const val DEFAULT_TOKEN_SECONDS = 3600L
        const val TOKEN_MARGIN_MS = 60_000L
    }
}

/** The error codes in an FCM error body: `error.status` and every `error.details[].errorCode`. */
internal fun errorCodes(body: String): List<String> {
    val error = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
        ?: return emptyList()
    val status = (error["status"] as? JsonPrimitive)?.contentOrNull
    val details = (error["details"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("errorCode") as? JsonPrimitive)?.contentOrNull }
    return listOfNotNull(status) + details
}
