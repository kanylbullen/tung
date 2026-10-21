package io.github.kanylbullen.tung.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.server.auth.GoogleIdTokens
import io.github.kanylbullen.tung.server.auth.GoogleIdentity
import org.jetbrains.exposed.sql.ResultRow

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+$")
private const val MIN_PASSWORD = 8
private const val MAX_PASSWORD = 1024

/** last_used_at is written at most this often per token, so reads do not each cost a write. */
private const val LAST_USED_RESOLUTION_MS = 60_000L

/**
 * Users, passwords, Google sign-in, session tokens and API tokens. One table
 * holds both kinds of token. [google] is null when the instance has no
 * Google client id (`TUNG_GOOGLE_CLIENT_ID`).
 */
class Accounts(private val db: Db, val google: GoogleIdTokens? = null) {

    suspend fun register(credentials: Credentials): SessionDto {
        val email = normalizeEmail(credentials.email)
        val password = credentials.password
        if (password.length !in MIN_PASSWORD..MAX_PASSWORD) {
            throw badRequest("The password must be $MIN_PASSWORD to $MAX_PASSWORD characters.", "weak_password")
        }
        val hash = withContext(Dispatchers.Default) { Passwords.hash(password) }
        val now = System.currentTimeMillis()
        return try {
            db.tx {
                if (!Users.selectAll().where { Users.email eq email }.empty()) throw emailTaken()
                val userId = newId()
                Users.insert {
                    it[id] = userId
                    it[Users.email] = email
                    it[passwordHash] = hash
                    it[createdAt] = now
                }
                SessionDto(
                    insertToken(userId, Tokens.SESSION_NAME, Tokens.SCOPE_WRITE, now).token,
                    UserDto(userId, email, now, hasPassword = true, googleLinked = false),
                )
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") throw emailTaken() else throw e
        }
    }

    suspend fun login(credentials: Credentials): SessionDto {
        val user = verifyCredentials(credentials) ?: throw unauthorized("invalid_credentials", "Wrong email or password.")
        return session(user)
    }

    /**
     * `POST /auth/google`: verifies the ID token, finds or links or creates
     * the user ([googleUser]) and answers what [login] answers.
     */
    suspend fun signInWithGoogle(idToken: String): SessionDto = session(googleUser(verifyGoogle(idToken)))

    /** A new session token for [user]: the one way login, Google sign-in and registration end. */
    private suspend fun session(user: UserDto): SessionDto {
        val token = db.tx { insertToken(user.id, Tokens.SESSION_NAME, Tokens.SCOPE_WRITE, System.currentTimeMillis()) }
        return SessionDto(token.token, user)
    }

    /** The identity in a Google ID token; 404 `not_configured` when the instance has no Google client id. */
    suspend fun verifyGoogle(idToken: String): GoogleIdentity {
        val google = google ?: throw ApiException(
            HttpStatusCode.NotFound,
            "not_configured",
            "Sign in with Google is not set up on this server.",
        )
        return google.verify(idToken)
    }

    /**
     * The user a verified Google identity signs in as. By `google_sub` first;
     * else, when Google says the email is verified, the account with that
     * email, which gets the `google_sub` (the link: its password keeps
     * working); else a new account without a password. An unverified email
     * that matches no linked account is refused: it proves nothing about who
     * owns the address. The consent page and `POST /auth/google` both use it.
     */
    suspend fun googleUser(identity: GoogleIdentity): UserDto {
        repeat(2) { attempt ->
            try {
                return db.tx { resolveGoogleUser(identity) }
            } catch (e: ExposedSQLException) {
                // Two first sign-ins at once: the loser hits the unique index and finds the winner's row next time.
                if (e.sqlState != "23505" || attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    private fun Transaction.resolveGoogleUser(identity: GoogleIdentity): UserDto {
        Users.selectAll().where { Users.googleSub eq identity.sub }.singleOrNull()?.let { return it.toUserDto() }
        val email = identity.email
        if (email == null || !identity.emailVerified) {
            throw unauthorized(
                "email_not_verified",
                "Google has not verified this account's email address, so it cannot sign in to Tung. Use your password, or a Google account with a verified email.",
            )
        }
        val existing = Users.selectAll().where { Users.email eq email }.singleOrNull()
        if (existing != null) {
            if (existing[Users.googleSub] != null) {
                throw conflict("google_account_mismatch", "This email belongs to an account linked to another Google account. Sign in with that one or with your password.")
            }
            Users.update({ (Users.id eq existing[Users.id]) and Users.googleSub.isNull() }) { it[googleSub] = identity.sub }
            return user(existing[Users.id])
        }
        if (email.length > 254 || !EMAIL_PATTERN.matches(email)) throw unauthorized("invalid_google_token", "The Google account has no usable email address.")
        val userId = newId()
        val now = System.currentTimeMillis()
        Users.insert {
            it[id] = userId
            it[Users.email] = email
            it[passwordHash] = null
            it[googleSub] = identity.sub
            it[createdAt] = now
        }
        return UserDto(userId, email, now, hasPassword = false, googleLinked = true)
    }

    /**
     * The user with these credentials, or null. Takes as long for an unknown
     * email as for a wrong password. Login and the OAuth consent page use it.
     */
    suspend fun verifyCredentials(credentials: Credentials): UserDto? {
        val email = credentials.email.trim().lowercase()
        val user = db.tx { Users.selectAll().where { Users.email eq email }.singleOrNull() }
        // A user without a password (Google only) is refused like a wrong password, after the same work.
        val hash = user?.get(Users.passwordHash)
        val ok = withContext(Dispatchers.Default) { Passwords.verify(credentials.password, hash ?: Passwords.dummyHash) && hash != null }
        if (!ok || user == null) return null
        return user.toUserDto()
    }

    /** The principal for a bearer token, or null when it is unknown, revoked or expired. */
    suspend fun authenticate(token: String): TokenPrincipal? {
        if (!token.startsWith(Tokens.PREFIX)) return null
        val hash = Tokens.hash(token)
        val now = System.currentTimeMillis()
        return db.tx {
            val row = ApiTokens.selectAll()
                .where {
                    (ApiTokens.tokenHash eq hash) and ApiTokens.revokedAt.isNull() and
                        (ApiTokens.expiresAt.isNull() or (ApiTokens.expiresAt greater now))
                }
                .singleOrNull() ?: return@tx null
            val tokenId = row[ApiTokens.id]
            ApiTokens.update({
                (ApiTokens.id eq tokenId) and
                    (ApiTokens.lastUsedAt.isNull() or (ApiTokens.lastUsedAt less now - LAST_USED_RESOLUTION_MS))
            }) { it[lastUsedAt] = now }
            TokenPrincipal(row[ApiTokens.userId], tokenId, row[ApiTokens.scope])
        }
    }

    suspend fun me(userId: String): UserDto = db.tx { user(userId) }

    fun Transaction.user(userId: String): UserDto {
        return Users.selectAll().where { Users.id eq userId }.single().toUserDto()
    }

    private fun ResultRow.toUserDto() = UserDto(
        id = this[Users.id],
        email = this[Users.email],
        createdAt = this[Users.createdAt],
        hasPassword = this[Users.passwordHash] != null,
        googleLinked = this[Users.googleSub] != null,
    )

    suspend fun listTokens(principal: TokenPrincipal): TokenList = db.tx {
        val tokens = ApiTokens.selectAll()
            .where { (ApiTokens.userId eq principal.userId) and ApiTokens.revokedAt.isNull() }
            .orderBy(ApiTokens.createdAt, SortOrder.DESC)
            .map {
                TokenDto(
                    id = it[ApiTokens.id],
                    name = it[ApiTokens.name],
                    scope = it[ApiTokens.scope],
                    createdAt = it[ApiTokens.createdAt],
                    lastUsedAt = it[ApiTokens.lastUsedAt],
                    expiresAt = it[ApiTokens.expiresAt],
                    current = it[ApiTokens.id] == principal.tokenId,
                )
            }
        TokenList(tokens)
    }

    suspend fun createToken(userId: String, request: NewToken): CreatedToken {
        val name = validName(request.name)
        val scope = request.scope.trim().lowercase()
        if (scope != Tokens.SCOPE_READ && scope != Tokens.SCOPE_WRITE) {
            throw badRequest("scope must be 'read' or 'write', got '${request.scope}'.", "invalid_scope")
        }
        return db.tx { insertToken(userId, name, scope, System.currentTimeMillis()) }
    }

    /** Revokes one of the user's tokens; a token of another user, or one already revoked, is not found. */
    suspend fun revokeToken(userId: String, tokenId: String) {
        val changed = db.tx {
            ApiTokens.update({
                (ApiTokens.id eq tokenId) and (ApiTokens.userId eq userId) and ApiTokens.revokedAt.isNull()
            }) { it[revokedAt] = System.currentTimeMillis() }
        }
        if (changed == 0) throw notFound("Token")
    }

    suspend fun exportTokens(userId: String): List<ExportTokenDto> = db.tx {
        ApiTokens.selectAll()
            .where { (ApiTokens.userId eq userId) and ApiTokens.revokedAt.isNull() }
            .orderBy(ApiTokens.createdAt, SortOrder.ASC)
            .map { ExportTokenDto(it[ApiTokens.name], it[ApiTokens.scope], it[ApiTokens.createdAt]) }
    }

    private fun Transaction.insertToken(userId: String, name: String, scope: String, now: Long): CreatedToken {
        val token = Tokens.generate()
        val tokenId = newId()
        ApiTokens.insert {
            it[id] = tokenId
            it[ApiTokens.userId] = userId
            it[ApiTokens.name] = name
            it[tokenHash] = Tokens.hash(token)
            it[ApiTokens.scope] = scope
            it[createdAt] = now
        }
        return CreatedToken(tokenId, name, scope, now, token)
    }

    private fun normalizeEmail(raw: String): String {
        val email = raw.trim().lowercase()
        if (email.length > 254 || !EMAIL_PATTERN.matches(email)) throw badRequest("That is not an email address.", "invalid_email")
        return email
    }

    private fun emailTaken() = conflict("email_taken", "An account with that email already exists. Log in instead.")
}
