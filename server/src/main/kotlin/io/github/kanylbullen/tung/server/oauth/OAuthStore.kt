package io.github.kanylbullen.tung.server.oauth

import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.server.ApiTokens
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.Tokens
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.sql.Connection
import java.security.SecureRandom
import java.util.Base64

/** The two scopes. `write` includes reading, as for every API token. */
object OAuthScopes {
    const val READ = "read"
    const val WRITE = "write"
    val all = listOf(READ, WRITE)

    /**
     * The scope granted for a requested one: `read write` when write is asked
     * for or nothing we know is (clients often send none, or only their own
     * scopes), `read` when only read is. Unknown values are ignored, as RFC
     * 6749 §3.3 allows.
     */
    fun grant(requested: String?): String {
        val asked = requested.orEmpty().split(' ').filter { it in all }.toSet()
        return if (asked == setOf(READ)) READ else "$READ $WRITE"
    }

    /** The api_tokens scope for a granted OAuth scope. */
    fun tokenScope(granted: String): String = if (WRITE in granted.split(' ')) Tokens.SCOPE_WRITE else Tokens.SCOPE_READ
}

/** A registered client. */
data class OAuthClient(
    val id: String,
    val name: String,
    val redirectUris: List<String>,
    val authMethod: String,
    val secretHash: String?,
    val createdAt: Long,
) {
    val isPublic: Boolean get() = authMethod == AUTH_NONE
}

const val AUTH_NONE = "none"
const val AUTH_SECRET_POST = "client_secret_post"
const val AUTH_SECRET_BASIC = "client_secret_basic"

/** What /oauth/token hands out: an API token, its lifetime, a refresh token and the granted scope. */
data class IssuedTokens(val accessToken: String, val expiresInSeconds: Long, val refreshToken: String, val scope: String)

/** Registered clients, authorization codes and refresh tokens (V5__oauth.sql). */
class OAuthStore(private val db: Db, private val clock: () -> Long = System::currentTimeMillis) {

    private val uris = ListSerializer(String.serializer())

    /** Registers a client and returns it with its secret in plaintext (null for a public client), shown once. */
    suspend fun register(name: String, redirectUris: List<String>, authMethod: String): Pair<OAuthClient, String?> {
        val secret = if (authMethod == AUTH_NONE) null else "tungs_" + random()
        val client = OAuthClient(newId(), name, redirectUris, authMethod, secret?.let(::sha256), clock())
        db.tx {
            OAuthClients.insert {
                it[id] = client.id
                it[secretHash] = client.secretHash
                it[clientName] = client.name
                it[OAuthClients.redirectUris] = Json.encodeToString(uris, client.redirectUris)
                it[tokenEndpointAuthMethod] = client.authMethod
                it[createdAt] = client.createdAt
            }
        }
        return client to secret
    }

    suspend fun client(clientId: String): OAuthClient? = db.tx {
        OAuthClients.selectAll().where { OAuthClients.id eq clientId }.singleOrNull()?.let {
            OAuthClient(
                id = it[OAuthClients.id],
                name = it[OAuthClients.clientName],
                redirectUris = Json.decodeFromString(uris, it[OAuthClients.redirectUris]),
                authMethod = it[OAuthClients.tokenEndpointAuthMethod],
                secretHash = it[OAuthClients.secretHash],
                createdAt = it[OAuthClients.createdAt],
            )
        }
    }

    /** Stamps the client's `last_used_at`: every authorize and token call by it keeps it from expiring. */
    suspend fun markUsed(clientId: String) {
        val now = clock()
        db.tx { OAuthClients.update({ OAuthClients.id eq clientId }) { it[lastUsedAt] = now } }
    }

    /**
     * Deletes the clients nobody has used for [UNUSED_CLIENT_LIFETIME_MS]
     * before [now] (`COALESCE(last_used_at, created_at)`) and that hold no
     * live grant: no access token of theirs that is neither revoked nor
     * expired, and none with a refresh token that still works. A client's
     * tokens are found through its refresh tokens, which name both the client
     * and the `api_tokens` row (`mcp:<client name>`) they renew; names alone
     * are not unique. Codes and refresh tokens go with the client (ON DELETE
     * CASCADE); the API tokens stay in the user's list. Returns how many were
     * deleted.
     */
    suspend fun sweepUnusedClients(now: Long = clock()): Int = db.tx {
        val connection = connection.connection as Connection
        connection.prepareStatement(
            """
            DELETE FROM oauth_clients c
            WHERE COALESCE(c.last_used_at, c.created_at) < ?
              AND NOT EXISTS (
                SELECT 1 FROM oauth_refresh_tokens r
                JOIN api_tokens t ON t.id = r.api_token_id
                WHERE r.client_id = c.id AND t.revoked_at IS NULL
                  AND (t.expires_at > ? OR (r.revoked_at IS NULL AND r.expires_at > ?))
              )
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, now - UNUSED_CLIENT_LIFETIME_MS)
            statement.setLong(2, now)
            statement.setLong(3, now)
            statement.executeUpdate()
        }
    }

    /** Checks a confidential client's secret in constant time. */
    fun secretMatches(client: OAuthClient, secret: String?): Boolean {
        val stored = client.secretHash ?: return false
        if (secret == null) return false
        return MessageDigest.isEqual(stored.toByteArray(), sha256(secret).toByteArray())
    }

    /** A new single-use code for an approved request; expired codes are swept on the way. */
    suspend fun createCode(
        clientId: String,
        userId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
        resource: String?,
    ): String {
        val code = random()
        val now = clock()
        db.tx {
            OAuthCodes.deleteWhere { expiresAt less now - CODE_SWEEP_AFTER_MS }
            OAuthCodes.insert {
                it[codeHash] = sha256(code)
                it[OAuthCodes.clientId] = clientId
                it[OAuthCodes.userId] = userId
                it[OAuthCodes.redirectUri] = redirectUri
                it[OAuthCodes.scope] = scope
                it[OAuthCodes.codeChallenge] = codeChallenge
                it[OAuthCodes.resource] = resource
                it[createdAt] = now
                it[expiresAt] = now + CODE_LIFETIME_MS
            }
        }
        return code
    }

    /**
     * The `authorization_code` grant. The code is spent by any attempt that
     * finds it, right or wrong, so a verifier cannot be guessed at; a spent
     * code presented again revokes what it was exchanged for (RFC 6749 §4.1.2).
     */
    suspend fun exchangeCode(client: OAuthClient, code: String, redirectUri: String?, codeVerifier: String?): IssuedTokens {
        val now = clock()
        val outcome: Result<IssuedTokens> = db.tx {
            val row = OAuthCodes.selectAll().where { OAuthCodes.codeHash eq sha256(code) }.forUpdate().singleOrNull()
                ?: return@tx failure("The code is unknown.")
            if (row[OAuthCodes.clientId] != client.id) return@tx failure("The code was issued to another client.")
            if (row[OAuthCodes.usedAt] != null) {
                row[OAuthCodes.apiTokenId]?.let { revokeGrant(it, now) }
                return@tx failure("The code was already used; the tokens issued for it are revoked.")
            }
            OAuthCodes.update({ OAuthCodes.codeHash eq row[OAuthCodes.codeHash] }) { it[usedAt] = now }
            when {
                row[OAuthCodes.expiresAt] <= now -> failure("The code has expired. Start the authorization again.")
                redirectUri != null && redirectUri != row[OAuthCodes.redirectUri] ->
                    failure("redirect_uri differs from the one the code was issued for.")
                codeVerifier == null -> failure("code_verifier is missing (PKCE is required).")
                !pkceMatches(codeVerifier, row[OAuthCodes.codeChallenge]) -> failure("code_verifier does not match the code_challenge.")
                else -> {
                    val scope = row[OAuthCodes.scope]
                    val (tokenId, tokens) = issue(client, row[OAuthCodes.userId], scope, now)
                    OAuthCodes.update({ OAuthCodes.codeHash eq row[OAuthCodes.codeHash] }) { it[apiTokenId] = tokenId }
                    Result.success(tokens)
                }
            }
        }
        return outcome.getOrElse { throw it }
    }

    /**
     * The `refresh_token` grant: the presented token is revoked and replaced,
     * and the grant's API token gets a new value and 30 more days. A token
     * that was already replaced revokes the whole grant (it leaked, or a
     * client replayed it); a revoked API token means the user ended access.
     */
    suspend fun refresh(client: OAuthClient, refreshToken: String): IssuedTokens {
        val now = clock()
        val outcome: Result<IssuedTokens> = db.tx {
            val row = OAuthRefreshTokens.selectAll().where { OAuthRefreshTokens.tokenHash eq sha256(refreshToken) }.forUpdate().singleOrNull()
                ?: return@tx failure("The refresh token is unknown.")
            if (row[OAuthRefreshTokens.clientId] != client.id) return@tx failure("The refresh token was issued to another client.")
            val tokenId = row[OAuthRefreshTokens.apiTokenId]
            if (row[OAuthRefreshTokens.revokedAt] != null) {
                if (row[OAuthRefreshTokens.replacedBy] != null) revokeGrant(tokenId, now)
                return@tx failure("The refresh token was already used or revoked. Connect again.")
            }
            if (row[OAuthRefreshTokens.expiresAt] <= now) return@tx failure("The refresh token has expired. Connect again.")
            val token = ApiTokens.selectAll().where { ApiTokens.id eq tokenId }.forUpdate().single()
            if (token[ApiTokens.revokedAt] != null) return@tx failure("Access was revoked in Tung. Connect again.")

            val accessToken = Tokens.generate()
            ApiTokens.update({ ApiTokens.id eq tokenId }) {
                it[tokenHash] = Tokens.hash(accessToken)
                it[expiresAt] = now + ACCESS_TOKEN_LIFETIME_MS
            }
            val newRefresh = insertRefreshToken(client.id, row[OAuthRefreshTokens.userId], tokenId, now)
            OAuthRefreshTokens.update({ OAuthRefreshTokens.id eq row[OAuthRefreshTokens.id] }) {
                it[revokedAt] = now
                it[replacedBy] = newRefresh.first
            }
            Result.success(
                IssuedTokens(accessToken, ACCESS_TOKEN_LIFETIME_MS / 1000, newRefresh.second, scopeOf(token[ApiTokens.scope])),
            )
        }
        return outcome.getOrElse { throw it }
    }

    /** A new API token row named `mcp:<client name>` and its first refresh token. */
    private fun Transaction.issue(client: OAuthClient, userId: String, scope: String, now: Long): Pair<String, IssuedTokens> {
        val accessToken = Tokens.generate()
        val tokenId = newId()
        ApiTokens.insert {
            it[id] = tokenId
            it[ApiTokens.userId] = userId
            it[name] = "mcp:${client.name}"
            it[tokenHash] = Tokens.hash(accessToken)
            it[ApiTokens.scope] = OAuthScopes.tokenScope(scope)
            it[createdAt] = now
            it[expiresAt] = now + ACCESS_TOKEN_LIFETIME_MS
        }
        val refresh = insertRefreshToken(client.id, userId, tokenId, now)
        return tokenId to IssuedTokens(accessToken, ACCESS_TOKEN_LIFETIME_MS / 1000, refresh.second, scope)
    }

    /** Inserts a refresh token; returns its row id and plaintext. */
    private fun Transaction.insertRefreshToken(clientId: String, userId: String, apiTokenId: String, now: Long): Pair<String, String> {
        val token = REFRESH_PREFIX + random()
        val id = newId()
        OAuthRefreshTokens.insert {
            it[OAuthRefreshTokens.id] = id
            it[tokenHash] = sha256(token)
            it[OAuthRefreshTokens.clientId] = clientId
            it[OAuthRefreshTokens.userId] = userId
            it[OAuthRefreshTokens.apiTokenId] = apiTokenId
            it[createdAt] = now
            it[expiresAt] = now + REFRESH_TOKEN_LIFETIME_MS
        }
        return id to token
    }

    /** Ends a grant: the API token and every refresh token of it. */
    private fun Transaction.revokeGrant(apiTokenId: String, now: Long) {
        ApiTokens.update({ (ApiTokens.id eq apiTokenId) and ApiTokens.revokedAt.isNull() }) { it[revokedAt] = now }
        OAuthRefreshTokens.update({ (OAuthRefreshTokens.apiTokenId eq apiTokenId) and OAuthRefreshTokens.revokedAt.isNull() }) {
            it[revokedAt] = now
        }
    }

    private fun scopeOf(tokenScope: String): String =
        if (tokenScope == Tokens.SCOPE_WRITE) "${OAuthScopes.READ} ${OAuthScopes.WRITE}" else OAuthScopes.READ

    private fun failure(description: String): Result<IssuedTokens> = Result.failure(OAuthException("invalid_grant", description))

    companion object {
        /** An authorization code lives five minutes. */
        const val CODE_LIFETIME_MS = 5 * 60_000L

        /** Expired codes are deleted a day after they expire. */
        private const val CODE_SWEEP_AFTER_MS = 24 * 3_600_000L

        /** An access token lives 30 days unless refreshed. */
        const val ACCESS_TOKEN_LIFETIME_MS = 30 * 24 * 3_600_000L

        /** A refresh token lives 90 days from when it was issued; each refresh issues a new one. */
        const val REFRESH_TOKEN_LIFETIME_MS = 90 * 24 * 3_600_000L

        /** A client unused for 90 days and without a live grant is deleted by [sweepUnusedClients]. */
        const val UNUSED_CLIENT_LIFETIME_MS = 90 * 24 * 3_600_000L

        /** How often the application runs [sweepUnusedClients], after once at start. */
        const val CLIENT_SWEEP_INTERVAL_MS = 24 * 3_600_000L

        /** Refresh tokens are not API tokens: a different prefix, never accepted as a bearer token. */
        const val REFRESH_PREFIX = "tungr_"

        private val random = SecureRandom()
        private val encoder = Base64.getUrlEncoder().withoutPadding()

        fun random(): String = encoder.encodeToString(ByteArray(32).also(random::nextBytes))

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private val VERIFIER = Regex("^[A-Za-z0-9._~-]{43,128}$")

        /** RFC 7636 S256: BASE64URL(SHA-256(ASCII(code_verifier))) equals the challenge. */
        fun pkceMatches(verifier: String, challenge: String): Boolean {
            if (!VERIFIER.matches(verifier)) return false
            val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            return MessageDigest.isEqual(encoder.encodeToString(digest).toByteArray(), challenge.toByteArray())
        }
    }
}
