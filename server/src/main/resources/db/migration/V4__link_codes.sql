-- Link codes (issue #30): a signed-in device asks for a short numeric code
-- (POST /api/v1/link/start), and a device that cannot type a password, a
-- watch, trades it for an API token of the same user (POST /api/v1/link/claim).
-- A code lives ten minutes and can be claimed once. Rows are short-lived:
-- expired ones are deleted the next time a code is made, so a six-digit code
-- is unique among the unexpired ones and the primary key never collides
-- with an old one.
--
-- V4, not V3: V3 is taken by the OAuth tables of the MCP work.
CREATE TABLE link_codes (
    code       text PRIMARY KEY,
    user_id    text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL,
    claimed_at bigint
);
CREATE INDEX link_codes_expires_idx ON link_codes (expires_at);
