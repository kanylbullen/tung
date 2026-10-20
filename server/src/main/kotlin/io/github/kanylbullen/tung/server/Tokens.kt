package io.github.kanylbullen.tung.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * API tokens: `tung_` + 32 random bytes in base64url. The plaintext is shown
 * once, when the token is created; the database keeps its SHA-256 (hex), which
 * is enough for a value with 256 bits of entropy.
 */
object Tokens {
    const val PREFIX = "tung_"
    const val SCOPE_READ = "read"
    const val SCOPE_WRITE = "write"
    const val SESSION_NAME = "session"

    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String = PREFIX + encoder.encodeToString(ByteArray(32).also(random::nextBytes))

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Who a request is from: the user and the token they sent. */
data class TokenPrincipal(val userId: String, val tokenId: String, val scope: String)
