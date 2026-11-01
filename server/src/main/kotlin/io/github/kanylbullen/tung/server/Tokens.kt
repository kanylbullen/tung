package io.github.kanylbullen.tung.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll

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

/**
 * Who a request is from: the user and the token they sent, and whether the
 * user's email is confirmed ([RequireConfirmedEmail]).
 */
data class TokenPrincipal(val userId: String, val tokenId: String, val scope: String, val emailVerified: Boolean = true)

/**
 * Inside a transaction that issues a credential because [principal]'s token
 * asked for it (an API token, a link code): takes [lockUser] and refuses
 * unless that token is still live. An account handed to its Google owner
 * revokes every token under the same lock, so a request that authenticated
 * just before cannot leave a new credential behind.
 */
fun Transaction.requireLiveToken(principal: TokenPrincipal) {
    lockUser(principal.userId)
    val live = ApiTokens.selectAll().where {
        (ApiTokens.id eq principal.tokenId) and ApiTokens.revokedAt.isNull()
    }.empty().not()
    if (!live) throw unauthorized("unauthorized", "This token was revoked. Sign in again.")
}
