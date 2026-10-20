package io.github.kanylbullen.tung.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import io.github.kanylbullen.tung.core.newId

private val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+$")
private const val MIN_PASSWORD = 8
private const val MAX_PASSWORD = 1024

/** last_used_at is written at most this often per token, so reads do not each cost a write. */
private const val LAST_USED_RESOLUTION_MS = 60_000L

/** Users, passwords, session tokens and API tokens. One table holds both kinds of token. */
class Accounts(private val db: Db) {

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
                SessionDto(insertToken(userId, Tokens.SESSION_NAME, Tokens.SCOPE_WRITE, now).token, UserDto(userId, email, now))
            }
        } catch (e: ExposedSQLException) {
            if (e.sqlState == "23505") throw emailTaken() else throw e
        }
    }

    suspend fun login(credentials: Credentials): SessionDto {
        val email = credentials.email.trim().lowercase()
        val user = db.tx { Users.selectAll().where { Users.email eq email }.singleOrNull() }
        val ok = withContext(Dispatchers.Default) {
            Passwords.verify(credentials.password, user?.get(Users.passwordHash) ?: Passwords.dummyHash) && user != null
        }
        if (!ok || user == null) throw unauthorized("invalid_credentials", "Wrong email or password.")
        val now = System.currentTimeMillis()
        val userId = user[Users.id]
        val token = db.tx { insertToken(userId, Tokens.SESSION_NAME, Tokens.SCOPE_WRITE, now) }
        return SessionDto(token.token, UserDto(userId, user[Users.email], user[Users.createdAt]))
    }

    /** The principal for a bearer token, or null when it is unknown or revoked. */
    suspend fun authenticate(token: String): TokenPrincipal? {
        if (!token.startsWith(Tokens.PREFIX)) return null
        val hash = Tokens.hash(token)
        val now = System.currentTimeMillis()
        return db.tx {
            val row = ApiTokens.selectAll()
                .where { (ApiTokens.tokenHash eq hash) and ApiTokens.revokedAt.isNull() }
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
        val row = Users.selectAll().where { Users.id eq userId }.single()
        return UserDto(row[Users.id], row[Users.email], row[Users.createdAt])
    }

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
