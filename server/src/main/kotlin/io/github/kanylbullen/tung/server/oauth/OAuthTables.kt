package io.github.kanylbullen.tung.server.oauth

import org.jetbrains.exposed.sql.Table

// Exposed mappings of V5__oauth.sql (and V8's last_used_at); Flyway owns the schema.

object OAuthClients : Table("oauth_clients") {
    val id = text("id")
    val secretHash = text("secret_hash").nullable()
    val clientName = text("client_name")
    /** A JSON array of strings. */
    val redirectUris = text("redirect_uris")
    val tokenEndpointAuthMethod = text("token_endpoint_auth_method")
    val createdAt = long("created_at")
    /** V8: the last /oauth/authorize or /oauth/token call by this client; null when there was none since V8. */
    val lastUsedAt = long("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object OAuthCodes : Table("oauth_codes") {
    val codeHash = text("code_hash")
    val clientId = text("client_id")
    val userId = text("user_id")
    val redirectUri = text("redirect_uri")
    val scope = text("scope")
    val codeChallenge = text("code_challenge")
    val resource = text("resource").nullable()
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val usedAt = long("used_at").nullable()
    val apiTokenId = text("api_token_id").nullable()
    override val primaryKey = PrimaryKey(codeHash)
}

object OAuthRefreshTokens : Table("oauth_refresh_tokens") {
    val id = text("id")
    val tokenHash = text("token_hash")
    val clientId = text("client_id")
    val userId = text("user_id")
    val apiTokenId = text("api_token_id")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    val revokedAt = long("revoked_at").nullable()
    val replacedBy = text("replaced_by").nullable()
    override val primaryKey = PrimaryKey(id)
}
