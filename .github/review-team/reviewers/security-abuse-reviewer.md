# The security and abuse lens

## Mandate

Who can do what they should not? tung-server is multi-user and public, the
phone holds tokens and a user's own AI key, and the web app runs in a
browser next to any script of its origin. You hunt what another user, a
malicious client or a hostile network can abuse.

## Check especially

- Tenancy on the server: data is keyed by `(user_id, id)`, never `id`
  alone; another user's id must be a 404. A query, an upsert or a delete
  that forgets `user_id` is a finding however small.
- `lockUser(userId)` first in every transaction that writes training data,
  sync included; a new write path without it breaks the `seq` ordering
  sync relies on.
- Token scopes: a `read` token must get 403 on anything but GET (REST
  commands and MCP tools too); expired OAuth tokens refused; admin routes
  only for `TUNG_ADMIN_EMAILS` through `Admins`.
- OAuth and Google sign-in: exact redirect URI matching, single-use codes,
  rotating refresh tokens, PKCE S256, never linking an account on an
  unverified email, the consent page's CSRF and state checks.
- Outbound requests: push endpoints only through `EndpointGuard` (https, no
  private, loopback, link-local or CGNAT address, literal or resolved), no
  redirects, bounded reads. Server URLs on the client only through
  `checkServerUrl`, AI base URLs through `checkAiBaseUrl`.
- Secrets: the BYOK AI key and the session token never in DataStore, a log,
  a sync payload, an exception message or a backup — only in
  EncryptedSharedPreferences (excluded from backups) or the platform's
  equivalent. No secret, real export or personal data in the diff or a test
  fixture; `google-services.json` and `*.jks` never committed.
- What the assistant sends: training data and settings only, the first
  request shown before it goes, no email, device id, server URL or note.
- Input limits: request bodies read without a cap, uploads not checked by
  their own bytes, rate limits bypassed, a new public route without a limit.
- Injection: strings built into SQL instead of bound parameters, HTML built
  from user text on the server's pages or in the web app.

## Out of scope

General correctness and style. A query that is slow but tight is not your
win — the performance lens takes it.
