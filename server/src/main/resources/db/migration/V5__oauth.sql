-- tung-server schema v5: OAuth 2.1 for the MCP server (issue #29).
--
-- tung-server is its own authorization server. Hosted MCP clients register
-- themselves (RFC 7591), send the user through /oauth/authorize (a login and
-- consent page, PKCE S256 only) and swap the code at /oauth/token. What they
-- get is an ordinary API token (api_tokens, named "mcp:<client name>") that
-- now expires unless refreshed, plus a rotating refresh token tied to that
-- token row. Revoking the API token, from the app's token list or with
-- DELETE /api/v1/tokens/{id}, ends the grant: its refresh tokens stop working.
-- Codes, client secrets and refresh tokens are stored as SHA-256, like tokens.

ALTER TABLE api_tokens ADD COLUMN expires_at bigint;  -- null: never expires (sessions and hand-made tokens)

CREATE TABLE oauth_clients (
    id                         text PRIMARY KEY,  -- the client_id
    secret_hash                text,              -- null for public clients (token_endpoint_auth_method none)
    client_name                text   NOT NULL,
    redirect_uris              text   NOT NULL,   -- JSON array of strings, matched exactly
    token_endpoint_auth_method text   NOT NULL
        CHECK (token_endpoint_auth_method IN ('none', 'client_secret_post', 'client_secret_basic')),
    created_at                 bigint NOT NULL
);

-- Authorization codes: short-lived, single use, bound to the client, the
-- redirect URI, the user, the scope and the PKCE challenge. api_token_id is
-- the token issued for the code, revoked if the code is ever presented again.
CREATE TABLE oauth_codes (
    code_hash      text PRIMARY KEY,
    client_id      text   NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    user_id        text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    redirect_uri   text   NOT NULL,
    scope          text   NOT NULL,
    code_challenge text   NOT NULL,  -- BASE64URL(SHA-256(code_verifier)), method S256
    resource       text,             -- RFC 8707 resource indicator, as sent
    created_at     bigint NOT NULL,
    expires_at     bigint NOT NULL,
    used_at        bigint,
    api_token_id   text REFERENCES api_tokens (id) ON DELETE SET NULL
);
CREATE INDEX oauth_codes_expires_idx ON oauth_codes (expires_at);

-- Refresh tokens rotate: each use revokes the presented one (replaced_by
-- names its successor) and issues a new one. A rotated token presented again
-- means it leaked, and revokes the whole grant.
CREATE TABLE oauth_refresh_tokens (
    id           text PRIMARY KEY,
    token_hash   text   NOT NULL UNIQUE,
    client_id    text   NOT NULL REFERENCES oauth_clients (id) ON DELETE CASCADE,
    user_id      text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    api_token_id text   NOT NULL REFERENCES api_tokens (id) ON DELETE CASCADE,
    created_at   bigint NOT NULL,
    expires_at   bigint NOT NULL,
    revoked_at   bigint,
    replaced_by  text
);
CREATE INDEX oauth_refresh_tokens_api_token_idx ON oauth_refresh_tokens (api_token_id);
