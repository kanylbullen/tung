package io.github.kanylbullen.tung.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.server.auth.GoogleIdTokens
import io.github.kanylbullen.tung.server.auth.GoogleIdentity
import io.github.kanylbullen.tung.server.oauth.OAuthCodes
import io.github.kanylbullen.tung.server.oauth.OAuthRefreshTokens
import io.github.kanylbullen.tung.server.routes.LinkCodes
import io.github.kanylbullen.tung.server.routes.SlidingWindowLimiter
import org.jetbrains.exposed.sql.ResultRow

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+$")
private const val MIN_PASSWORD = 8
private const val MAX_PASSWORD = 1024

/** How long an emailed confirmation link works. */
const val EMAIL_CONFIRM_LIFETIME_MS = 7 * 24 * 60 * 60 * 1000L

/**
 * An address registered but never confirmed blocks a new registration of it
 * this long; after that the unconfirmed account (which holds nothing on the
 * server, as it could not sync) gives way, so nobody can hold an address
 * they do not own.
 */
const val UNCONFIRMED_HOLD_MS = 24 * 60 * 60 * 1000L

/** Confirmation emails one account may ask for again, per hour. */
const val CONFIRM_RESENDS_PER_HOUR = 3

/** last_used_at is written at most this often per token, so reads do not each cost a write. */
private const val LAST_USED_RESOLUTION_MS = 60_000L

/**
 * Users, passwords, Google sign-in, session tokens and API tokens. One table
 * holds both kinds of token. [google] is null when the instance has no
 * Google client id (`TUNG_GOOGLE_CLIENT_ID`).
 */
class Accounts(
    private val db: Db,
    val google: GoogleIdTokens? = null,
    private val welcome: WelcomeMail? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Whether a new password account must confirm its email before it may
     * sync: only when this server can send the link (`TUNG_RESEND_API_KEY`).
     * Without mail every account counts as confirmed, as before.
     */
    val verifiesEmail: Boolean get() = welcome?.canSend == true

    private val resends = SlidingWindowLimiter(CONFIRM_RESENDS_PER_HOUR, 60 * 60 * 1000L, clock)

    suspend fun register(credentials: Credentials): SessionDto {
        val email = normalizeEmail(credentials.email)
        val password = credentials.password
        if (password.length !in MIN_PASSWORD..MAX_PASSWORD) {
            throw badRequest("The password must be $MIN_PASSWORD to $MAX_PASSWORD characters.", "weak_password")
        }
        val hash = withContext(Dispatchers.Default) { Passwords.hash(password) }
        val now = clock()
        val confirming = verifiesEmail
        return try {
            val (session, confirmToken) = db.tx {
                Users.selectAll().where { Users.email eq email }.singleOrNull()?.let { held ->
                    // An address someone registered and never confirmed gives way after a day: see UNCONFIRMED_HOLD_MS.
                    val unconfirmed = held[Users.emailVerifiedAt] == null && held[Users.googleSub] == null
                    if (!(confirming && unconfirmed && held[Users.createdAt] <= now - UNCONFIRMED_HOLD_MS)) throw emailTaken()
                    Users.deleteWhere { Users.id eq held[Users.id] }
                }
                val userId = newId()
                Users.insert {
                    it[id] = userId
                    it[Users.email] = email
                    it[passwordHash] = hash
                    it[createdAt] = now
                    // Without mail nothing is confirmed: 0 counts for syncing and proves nothing (V14).
                    it[emailVerifiedAt] = if (confirming) null else 0L
                }
                SessionDto(
                    insertToken(userId, Tokens.SESSION_NAME, Tokens.SCOPE_WRITE, now).token,
                    UserDto(userId, email, now, hasPassword = true, googleLinked = false, emailVerified = !confirming),
                ) to (if (confirming) newConfirmToken(userId, now) else null)
            }
            welcome?.account(email, credentials.language, confirmToken)
            session
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") throw emailTaken() else throw e
        }
    }

    suspend fun login(credentials: Credentials): SessionDto {
        val proof = verifyCredentials(credentials) ?: throw unauthorized("invalid_credentials", "Wrong email or password.")
        return session(proof.user) { requirePassword(proof) }
    }

    /**
     * `POST /auth/google`: verifies the ID token, finds or links or creates
     * the user ([googleUser]) and answers what [login] answers.
     */
    suspend fun signInWithGoogle(idToken: String, language: String? = null): SessionDto =
        session(googleUser(verifyGoogle(idToken), language))

    /**
     * A new session token for [user]: the one way login and Google sign-in
     * end. [guard] runs first in the same transaction (see [requirePassword]).
     */
    private suspend fun session(user: UserDto, guard: Transaction.() -> Unit = { lockUser(user.id) }): SessionDto {
        val token = db.tx {
            guard()
            insertToken(user.id, Tokens.SESSION_NAME, Tokens.SCOPE_WRITE, System.currentTimeMillis())
        }
        return SessionDto(token.token, user)
    }

    /**
     * Inside a transaction that is about to issue a credential on the
     * strength of [proof]: takes [lockUser] and refuses unless the account
     * still has the password that was checked. Argon2 runs outside any
     * transaction, so without this a login that checked the password just
     * before the account was handed to its Google owner
     * ([evictUnverifiedHolders], under the same lock) would get a session
     * after the eviction, and keep it.
     */
    fun Transaction.requirePassword(proof: PasswordProof) {
        lockUser(proof.user.id)
        val current = Users.selectAll().where { Users.id eq proof.user.id }.singleOrNull()?.get(Users.passwordHash)
        if (current == null || current != proof.hash) throw unauthorized("invalid_credentials", "Wrong email or password.")
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
     * email, which gets the `google_sub` (the link); else a new account
     * without a password.
     *
     * A password account whose email was never confirmed by its link (an
     * account from before verification, or one on a server that sends no
     * mail): anyone could have registered someone else's address and waited
     * for its owner to sign in with Google. So the link hands such an
     * account to the verified owner and
     * shuts out whoever else may hold it: the password is removed, and every
     * token, OAuth grant and link code of the account is revoked
     * ([evictUnverifiedHolders]). The owner signs in with Google from then on,
     * and signs the other devices in again. An unverified email
     * that matches no linked account is refused: it proves nothing about who
     * owns the address. The consent page and `POST /auth/google` both use it.
     */
    suspend fun googleUser(identity: GoogleIdentity, language: String? = null): UserDto {
        repeat(2) { attempt ->
            try {
                val (user, created) = db.tx { resolveGoogleUser(identity) }
                // A new account, after its commit: the welcome email, as after registering.
                if (created) welcome?.account(user.email, language)
                return user
            } catch (e: ExposedSQLException) {
                // Two first sign-ins at once: the loser hits the unique index and finds the winner's row next time.
                if (e.sqlState != "23505" || attempt == 1) throw e
            }
        }
        error("unreachable")
    }

    /** The user, and whether this call created it. */
    private fun Transaction.resolveGoogleUser(identity: GoogleIdentity): Pair<UserDto, Boolean> {
        Users.selectAll().where { Users.googleSub eq identity.sub }.singleOrNull()?.let { row ->
            // Google verified the address when it was linked: an account confirmed by nothing else is confirmed now.
            if (!proven(row[Users.emailVerifiedAt])) {
                Users.update({ Users.id eq row[Users.id] }) { it[emailVerifiedAt] = clock() }
                return user(row[Users.id]) to false
            }
            return row.toUserDto() to false
        }
        val email = identity.email
        if (email == null || !identity.emailVerified) {
            throw unauthorized(
                "email_not_verified",
                "Google has not verified this account's email address, so it cannot sign in to Tung. Use your password, or a Google account with a verified email.",
            )
        }
        val found = Users.selectAll().where { Users.email eq email }.singleOrNull()
        if (found != null) {
            // Under the user's lock, so no credential is issued half-way through the hand-over, and read again
            // under it: a concurrent first sign-in may have linked the account meanwhile.
            val userId = found[Users.id]
            lockUser(userId)
            val linked = Users.selectAll().where { Users.id eq userId }.single()[Users.googleSub]
            if (linked == identity.sub) return user(userId) to false
            if (linked != null) {
                throw conflict("google_account_mismatch", "This email belongs to an account linked to another Google account. Sign in with that one or with your password.")
            }
            val now = clock()
            if (proven(Users.selectAll().where { Users.id eq userId }.single()[Users.emailVerifiedAt])) {
                // Its holder confirmed the address by its link: the same person, who keeps the password.
                Users.update({ (Users.id eq userId) and Users.googleSub.isNull() }) { it[googleSub] = identity.sub }
                return user(userId) to false
            }
            Users.update({ (Users.id eq userId) and Users.googleSub.isNull() }) {
                it[googleSub] = identity.sub
                it[passwordHash] = null
                it[emailVerifiedAt] = now
            }
            evictUnverifiedHolders(userId, now)
            return user(userId) to false
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
            it[emailVerifiedAt] = now
        }
        return UserDto(userId, email, now, hasPassword = false, googleLinked = true) to true
    }

    /**
     * Ends every way into [userId] that did not come from the Google owner:
     * its API and session tokens (OAuth access tokens are API tokens, and a
     * refresh token of a revoked one is refused), its refresh tokens, its
     * unused OAuth codes, its link codes and its push endpoints. The session the caller is about
     * to create comes after this. The caller holds [lockUser], which every path that issues a
     * credential takes too, so nothing issued on an older password or token outlives this.
     */
    private fun Transaction.evictUnverifiedHolders(userId: String, now: Long) {
        ApiTokens.update({ (ApiTokens.userId eq userId) and ApiTokens.revokedAt.isNull() }) { it[revokedAt] = now }
        OAuthRefreshTokens.update({ (OAuthRefreshTokens.userId eq userId) and OAuthRefreshTokens.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
        OAuthCodes.deleteWhere { (OAuthCodes.userId eq userId) and OAuthCodes.usedAt.isNull() }
        LinkCodes.deleteWhere { LinkCodes.userId eq userId }
        // Their devices' push endpoints too, or they would still hear when the account changes.
        PushEndpoints.update({ (PushEndpoints.userId eq userId) and PushEndpoints.revokedAt.isNull() }) { it[revokedAt] = now }
    }

    /**
     * The user with these credentials, or null. Takes as long for an unknown
     * email as for a wrong password. Login and the OAuth consent page use it.
     */
    suspend fun verifyCredentials(credentials: Credentials): PasswordProof? {
        val email = credentials.email.trim().lowercase()
        val user = db.tx { Users.selectAll().where { Users.email eq email }.singleOrNull() }
        // A user without a password (Google only) is refused like a wrong password, after the same work.
        val hash = user?.get(Users.passwordHash)
        val ok = withContext(Dispatchers.Default) { Passwords.verify(credentials.password, hash ?: Passwords.dummyHash) && hash != null }
        if (!ok || user == null || hash == null) return null
        return PasswordProof(user.toUserDto(), hash)
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
            val verified = Users.select(Users.emailVerifiedAt).where { Users.id eq row[ApiTokens.userId] }.single()[Users.emailVerifiedAt] != null
            TokenPrincipal(row[ApiTokens.userId], tokenId, row[ApiTokens.scope], emailVerified = verified)
        }
    }

    /**
     * `GET /verify-email?token=…`: the account of an unexpired confirmation
     * link is confirmed. False for a link that is unknown, expired, or
     * replaced by a newer one. The link keeps working until it expires: a
     * mail scanner may open it before the person does, who must then still
     * see "confirmed".
     */
    suspend fun confirmEmail(token: String): Boolean {
        val now = clock()
        return db.tx {
            val row = EmailTokens.selectAll().where {
                (EmailTokens.tokenHash eq Tokens.hash(token)) and (EmailTokens.kind eq EmailTokens.KIND_VERIFY)
            }.singleOrNull() ?: return@tx false
            val userId = row[EmailTokens.userId]
            if (row[EmailTokens.expiresAt] <= now) return@tx false
            Users.update({ (Users.id eq userId) and Users.emailVerifiedAt.isNull() }) { it[emailVerifiedAt] = now }
            true
        }
    }

    /**
     * `POST /auth/verify/resend`: a new confirmation link to [principal]'s
     * address, the earlier ones spent. Nothing for an account already
     * confirmed. [CONFIRM_RESENDS_PER_HOUR] per account; 404
     * `not_configured` on a server that sends no mail.
     */
    suspend fun resendConfirmation(principal: TokenPrincipal, language: String?) {
        val welcome = welcome?.takeIf { verifiesEmail } ?: throw ApiException(
            HttpStatusCode.NotFound,
            "not_configured",
            "This server sends no email, so there is nothing to confirm.",
        )
        val now = clock()
        val (email, token) = db.tx {
            val row = Users.selectAll().where { Users.id eq principal.userId }.single()
            if (row[Users.emailVerifiedAt] != null) return@tx null
            if (!resends.tryAcquire(principal.userId)) throw rateLimited()
            EmailTokens.deleteWhere { (EmailTokens.userId eq principal.userId) and (EmailTokens.kind eq EmailTokens.KIND_VERIFY) }
            row[Users.email] to newConfirmToken(principal.userId, now)
        } ?: return
        welcome.confirmAgain(email, language, token)
    }

    /** A fresh confirmation link's token for [userId]; only its hash is stored. */
    private fun Transaction.newConfirmToken(userId: String, now: Long): String {
        val token = Tokens.generate()
        EmailTokens.insert {
            it[tokenHash] = Tokens.hash(token)
            it[EmailTokens.userId] = userId
            it[kind] = EmailTokens.KIND_VERIFY
            it[createdAt] = now
            it[expiresAt] = now + EMAIL_CONFIRM_LIFETIME_MS
        }
        return token
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
        emailVerified = this[Users.emailVerifiedAt] != null,
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

    suspend fun createToken(principal: TokenPrincipal, request: NewToken): CreatedToken {
        val name = validName(request.name)
        // The names the server gives its own tokens: a made token never passes for a sign-in (account deletion
        // trusts "session"), a watch or an OAuth grant.
        if (name.equals(Tokens.SESSION_NAME, ignoreCase = true) || name.equals("watch", ignoreCase = true) || name.startsWith("mcp:", ignoreCase = true)) {
            throw badRequest("'$name' is a name the server keeps for its own tokens. Choose another.", "reserved_name")
        }
        val scope = request.scope.trim().lowercase()
        if (scope != Tokens.SCOPE_READ && scope != Tokens.SCOPE_WRITE) {
            throw badRequest("scope must be 'read' or 'write', got '${request.scope}'.", "invalid_scope")
        }
        return db.tx {
            requireLiveToken(principal)
            insertToken(principal.userId, name, scope, System.currentTimeMillis())
        }
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

    /**
     * Deletes the account of [principal] and everything that is the user's:
     * training data, tokens, link codes, push endpoints, OAuth grants and
     * feedback, all through `ON DELETE CASCADE` from `users`. Only a
     * session token (a sign-in from an app or the web) may, never an API,
     * watch or OAuth token, and [confirmEmail] must be the account's email.
     * Under [lockUser], so no sync of the user's is half-way when the rows go.
     */
    suspend fun deleteAccount(principal: TokenPrincipal, confirmEmail: String) {
        db.tx {
            lockUser(principal.userId)
            val tokenName = ApiTokens.selectAll().where { ApiTokens.id eq principal.tokenId }.single()[ApiTokens.name]
            if (tokenName != Tokens.SESSION_NAME) {
                throw forbidden("session_required", "Delete the account from the app or the web, signed in with your password or Google.")
            }
            val email = Users.selectAll().where { Users.id eq principal.userId }.single()[Users.email]
            if (!email.equals(confirmEmail.trim(), ignoreCase = true)) {
                throw badRequest("Type the account's email to confirm.", code = "email_mismatch")
            }
            Users.deleteWhere { Users.id eq principal.userId }
        }
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

/**
 * A password that was checked, and the hash it was checked against: what
 * [Accounts.requirePassword] compares with the account before anything is
 * issued on it.
 */
class PasswordProof(val user: UserDto, internal val hash: String)

/**
 * Whether `email_verified_at` proves the address: the link or Google, not
 * null (unconfirmed) and not 0 (an account from before verification, which
 * counts as confirmed for syncing but proves nothing; see V14).
 */
internal fun proven(emailVerifiedAt: Long?): Boolean = emailVerifiedAt != null && emailVerifiedAt > 0
