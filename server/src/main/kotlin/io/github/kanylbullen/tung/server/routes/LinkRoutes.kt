package io.github.kanylbullen.tung.server.routes

import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.server.Accounts
import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.ApiTokens
import io.github.kanylbullen.tung.server.BEARER
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.SessionDto
import io.github.kanylbullen.tung.server.TokenPrincipal
import io.github.kanylbullen.tung.server.Tokens
import io.github.kanylbullen.tung.server.forbidden
import io.github.kanylbullen.tung.server.unauthorized
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/** How long a link code can be claimed. */
const val LINK_CODE_TTL_MS = 10 * 60 * 1000L

/** Codes a user may ask for per minute. */
const val LINK_START_PER_MINUTE = 5

/** Claims one client address may try per minute: ten guesses at a one-in-a-million code. */
const val LINK_CLAIM_PER_MINUTE = 10

/**
 * Failed claims, from every client together, per [LINK_CODE_TTL_MS]; past
 * that every claim is refused until the window moves on. The per-address
 * limit alone does not bound guessing: an attacker with many addresses (an
 * IPv6 /64 is enough) gets ten guesses a minute from each, against a code
 * space of a million. With this budget, one code's ten-minute life allows at
 * most this many guesses in all, so a guess hits a live code with a chance
 * of about [LINK_CLAIM_FAILURES_PER_TTL] in a million per window. The price:
 * someone guessing on purpose can keep linking refused for everyone while
 * they keep at it.
 */
const val LINK_CLAIM_FAILURES_PER_TTL = 50

/** The name of the token a claim creates, as listed under /api/v1/tokens. */
const val LINK_TOKEN_NAME = "watch"

object LinkCodes : Table("link_codes") {
    val code = text("code")
    val userId = text("user_id")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val claimedAt = long("claimed_at").nullable()
    override val primaryKey = PrimaryKey(code)
}

@Serializable
data class LinkCode(val code: String, val expiresAt: Long)

@Serializable
data class LinkClaim(val code: String)

/**
 * Sign-in without typing a password, for the watch (issue #30): a signed-in
 * device asks for a six-digit code, the watch sends it back and gets an API
 * token of the same user.
 *
 * - `POST /api/v1/link/start` (bearer, scope `write`): a new code, valid
 *   [LINK_CODE_TTL_MS], unique among the unexpired ones. [LINK_START_PER_MINUTE] per user.
 * - `POST /api/v1/link/claim {code}` (no auth): a valid, unclaimed code is
 *   marked claimed and answered with a new `write` token named
 *   [LINK_TOKEN_NAME] and the user; anything else is 404 `invalid_code`,
 *   whether the code never existed, expired or was claimed already.
 *   [LINK_CLAIM_PER_MINUTE] per client address, and at most
 *   [LINK_CLAIM_FAILURES_PER_TTL] failed claims from all clients together
 *   per code lifetime.
 *
 * The limits are counted here, per application instance, not by Ktor's
 * RateLimit plugin, whose limiters are all registered in Application.kt.
 * Mounted under `/api/v1` from `apiRoutes`.
 */
fun Route.linkRoutes(db: Db, accounts: Accounts, clock: () -> Long = System::currentTimeMillis) {
    val startLimit = SlidingWindowLimiter(LINK_START_PER_MINUTE, WINDOW_MS, clock)
    val claimLimit = SlidingWindowLimiter(LINK_CLAIM_PER_MINUTE, WINDOW_MS, clock)
    val claimFailures = SlidingWindowLimiter(LINK_CLAIM_FAILURES_PER_TTL, LINK_CODE_TTL_MS, clock)
    val random = SecureRandom()

    authenticate(BEARER) {
        post("/link/start") {
            val principal = call.principal<TokenPrincipal>()
                ?: throw unauthorized("unauthorized", "Send a valid API token as 'Authorization: Bearer tung_…'.")
            if (principal.scope != Tokens.SCOPE_WRITE) {
                throw forbidden("insufficient_scope", "This token is read-only. Use a token with scope 'write' to link a device.")
            }
            if (!startLimit.tryAcquire(principal.userId)) throw rateLimited()
            call.respond(HttpStatusCode.Created, startCode(db, principal.userId, random, clock()))
        }
    }

    post("/link/claim") {
        if (!claimLimit.tryAcquire(call.request.origin.remoteAddress)) throw rateLimited()
        if (claimFailures.isExhausted(ALL_CLIENTS)) throw rateLimited()
        val code = call.receive<LinkClaim>().code.trim()
        if (!CODE_PATTERN.matches(code)) throw invalidCode()
        val now = clock()
        val claimed = db.tx {
            // One statement decides: a concurrent second claim waits on the row lock, then matches nothing.
            val changed = LinkCodes.update({
                (LinkCodes.code eq code) and LinkCodes.claimedAt.isNull() and (LinkCodes.expiresAt greater now)
            }) { it[claimedAt] = now }
            if (changed == 0) return@tx null
            val userId = LinkCodes.selectAll().where { LinkCodes.code eq code }.single()[LinkCodes.userId]
            val token = Tokens.generate()
            ApiTokens.insert {
                it[id] = newId()
                it[ApiTokens.userId] = userId
                it[name] = LINK_TOKEN_NAME
                it[tokenHash] = Tokens.hash(token)
                it[scope] = Tokens.SCOPE_WRITE
                it[createdAt] = now
            }
            userId to token
        } ?: run {
            // A guess that missed counts against everyone's budget; see LINK_CLAIM_FAILURES_PER_TTL.
            claimFailures.tryAcquire(ALL_CLIENTS)
            throw invalidCode()
        }
        call.respond(SessionDto(claimed.second, accounts.me(claimed.first)))
    }
}

/** Deletes expired codes, then inserts a fresh one for [userId]; draws again on the rare collision. */
private suspend fun startCode(db: Db, userId: String, random: SecureRandom, now: Long): LinkCode {
    repeat(MAX_ATTEMPTS) {
        val code = "%06d".format(random.nextInt(1_000_000))
        val inserted = try {
            db.tx {
                LinkCodes.deleteWhere { expiresAt less now }
                if (!LinkCodes.selectAll().where { LinkCodes.code eq code }.empty()) return@tx false
                LinkCodes.insert {
                    it[LinkCodes.code] = code
                    it[LinkCodes.userId] = userId
                    it[createdAt] = now
                    it[expiresAt] = now + LINK_CODE_TTL_MS
                }
                true
            }
        } catch (e: ExposedSQLException) {
            // Two requests drew the same code at the same moment: draw again.
            if (e.sqlState == "23505") false else throw e
        }
        if (inserted) return LinkCode(code, now + LINK_CODE_TTL_MS)
    }
    error("No free link code after $MAX_ATTEMPTS attempts")
}

private val CODE_PATTERN = Regex("^[0-9]{6}$")
private const val MAX_ATTEMPTS = 20
private const val WINDOW_MS = 60_000L
private const val ALL_CLIENTS = "*"

private fun invalidCode() =
    ApiException(HttpStatusCode.NotFound, "invalid_code", "That code is not valid. A code works once, for ten minutes; ask for a new one.")

private fun rateLimited() =
    ApiException(HttpStatusCode.TooManyRequests, "rate_limited", "Too many requests. Wait a minute and try again.")

/**
 * At most [limit] acquisitions per key in any [windowMillis]. In memory, so
 * per server process, which is enough for codes that live ten minutes. Keys
 * with no recent use are dropped as the map grows.
 */
internal class SlidingWindowLimiter(
    private val limit: Int,
    private val windowMillis: Long,
    private val clock: () -> Long,
) {
    private val hits = ConcurrentHashMap<String, ArrayDeque<Long>>()

    fun tryAcquire(key: String): Boolean {
        val now = clock()
        if (hits.size > PRUNE_AT) prune(now)
        val times = hits.computeIfAbsent(key) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && times.peekFirst() <= now - windowMillis) times.pollFirst()
            if (times.size >= limit) return false
            times.addLast(now)
            return true
        }
    }

    /** True when [key] has no acquisition left in the current window; acquires nothing. */
    fun isExhausted(key: String): Boolean {
        val now = clock()
        val times = hits[key] ?: return false
        synchronized(times) {
            while (times.isNotEmpty() && times.peekFirst() <= now - windowMillis) times.pollFirst()
            return times.size >= limit
        }
    }

    private fun prune(now: Long) {
        hits.entries.removeIf { (_, times) -> synchronized(times) { times.peekLast()?.let { it <= now - windowMillis } ?: true } }
    }

    private companion object {
        const val PRUNE_AT = 10_000
    }
}
