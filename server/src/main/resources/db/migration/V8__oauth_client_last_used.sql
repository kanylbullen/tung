-- tung-server schema v8: expiry of unused OAuth clients (issue #42).
--
-- Registration is open (RFC 7591), and an MCP client registers again
-- whenever it loses its client_id, so oauth_clients only grows. last_used_at
-- is stamped on every /oauth/authorize and /oauth/token call by the client;
-- a sweep at start and then daily deletes the clients not used for 90 days
-- (COALESCE(last_used_at, created_at)) that hold no live grant. Their codes
-- and refresh tokens go with them (ON DELETE CASCADE, V5); the API tokens
-- they were issued stay in the user's token list, expired or revoked.

ALTER TABLE oauth_clients ADD COLUMN last_used_at bigint;  -- null: never used since V8
