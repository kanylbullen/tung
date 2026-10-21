package io.github.kanylbullen.tung.server.oauth

import io.ktor.http.Parameters
import io.ktor.http.formUrlEncode
import io.ktor.http.parseUrlEncodedParameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The two values that carry a Google sign-in through the consent page
 * (issue #51), each signed with a key this process makes at start:
 *
 * - **state**: the authorization request, written into the Google button's
 *   `data-state`. Google Identity Services posts it back to `/oauth/google`
 *   with the credential, from accounts.google.com, so our SameSite=Strict
 *   CSRF cookie is not sent with that POST; the signature is how the server
 *   knows the request came from its own page. Valid [STATE_TTL_MS].
 * - **ticket**: who Google signed in, bound to the browser's CSRF token and
 *   the client, carried by the consent form's hidden field to
 *   `POST /oauth/authorize`, where it stands in for the password. Valid
 *   [TICKET_TTL_MS].
 *
 * A restart forgets the key, so a page opened before it shows "expired".
 */
class GoogleConsent(private val clock: () -> Long = System::currentTimeMillis) {
    private val key = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "HmacSHA256")
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** The authorization request as a signed, URL-safe string. */
    fun state(request: ConsentPage.Request): String {
        val params = listOfNotNull(
            "response_type" to "code",
            "client_id" to request.clientId,
            "redirect_uri" to request.redirectUri,
            request.state?.let { "state" to it },
            "scope" to request.scope,
            "code_challenge" to request.codeChallenge,
            "code_challenge_method" to "S256",
            request.resource?.let { "resource" to it },
        ).formUrlEncode()
        val body = encoder.encodeToString(params.toByteArray(Charsets.UTF_8)) + "." + (clock() + STATE_TTL_MS)
        return body + "." + sign("state", body)
    }

    /** The request parameters of a [state] this process signed and that has not expired, else null. */
    fun readState(state: String?): Parameters? {
        val body = verified("state", state) ?: return null
        val encoded = body.substringBefore('.')
        return runCatching { decoder.decode(encoded).decodeToString().parseUrlEncodedParameters() }.getOrNull()
    }

    fun ticket(userId: String, csrf: String, clientId: String): String {
        val body = encoder.encodeToString(userId.toByteArray(Charsets.UTF_8)) + "." + (clock() + TICKET_TTL_MS)
        return body + "." + sign("ticket|$csrf|$clientId", body)
    }

    /** The user id of a valid [ticket] for this CSRF token and client, else null. */
    fun readTicket(ticket: String?, csrf: String, clientId: String): String? {
        val body = verified("ticket|$csrf|$clientId", ticket) ?: return null
        return runCatching { decoder.decode(body.substringBefore('.')).decodeToString() }.getOrNull()
    }

    /** `payload.expiry` of a signed value when the signature matches and it is unexpired. */
    private fun verified(purpose: String, value: String?): String? {
        if (value == null) return null
        val body = value.substringBeforeLast('.', "")
        val signature = value.substringAfterLast('.', "")
        if (body.isEmpty() || signature.isEmpty()) return null
        if (!MessageDigest.isEqual(sign(purpose, body).toByteArray(), signature.toByteArray())) return null
        val expires = body.substringAfterLast('.').toLongOrNull() ?: return null
        return body.takeIf { expires > clock() }
    }

    private fun sign(purpose: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        return encoder.encodeToString(mac.doFinal("$purpose\n$body".toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val STATE_TTL_MS = 30 * 60_000L
        const val TICKET_TTL_MS = 10 * 60_000L
    }
}
