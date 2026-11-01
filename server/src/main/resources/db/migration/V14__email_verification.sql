-- Email verification. A password account gets a link by email; until it is
-- opened the account may sign in but not sync or write training data, so an
-- unverified account holds nothing on the server.
--
-- email_verified_at: when the address was proven (the link, or Google), null
-- while it is not. Accounts from before verification are counted verified
-- with 0, so nobody is locked out; 0 is not proof, so a Google sign-in still
-- hands such an account to the address's owner, and it does not make an
-- admin (see Admins).
ALTER TABLE users ADD COLUMN email_verified_at bigint;
UPDATE users SET email_verified_at = 0 WHERE google_sub IS NULL;
UPDATE users SET email_verified_at = created_at WHERE google_sub IS NOT NULL;

-- One-use links sent by email: 'verify' now, 'reset' (a new password) later.
-- Only the SHA-256 of the token is stored. Go with the account.
CREATE TABLE email_tokens (
    token_hash text   PRIMARY KEY,
    user_id    text   NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    kind       text   NOT NULL,
    created_at bigint NOT NULL,
    expires_at bigint NOT NULL
);
CREATE INDEX email_tokens_by_user ON email_tokens (user_id, kind);
