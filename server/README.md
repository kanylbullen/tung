# tung-server

The optional backend for Tung: accounts, API tokens, a REST API over your
workouts with an OpenAPI specification, row-level sync for the app, a JSON
export, and an MCP server so an assistant (Claude, ChatGPT, Cursor) can read
and log your training. One Ktor application with Postgres, shipped as one Docker image.
The app works fully without it.

API documentation is served by the server itself: Swagger UI at
[`/docs`](http://localhost:8080/docs), the specification at
[`/openapi.yaml`](http://localhost:8080/openapi.yaml) (source:
[`src/main/resources/openapi/openapi.yaml`](src/main/resources/openapi/openapi.yaml)).
Swagger UI loads its JavaScript from unpkg.com; the only outbound calls the
server makes are push notifications to your devices (see "Push").

## Run it

With Docker, the whole thing including Postgres: see [`../deploy/`](../deploy/README.md).

From the repository, against a throwaway Postgres:

```sh
docker run -d --name tung-pg -e POSTGRES_DB=tung -e POSTGRES_PASSWORD=dev -p 5432:5432 postgres:17-alpine
DATABASE_URL=jdbc:postgresql://localhost:5432/tung DATABASE_USER=postgres DATABASE_PASSWORD=dev \
  ./gradlew :server:run
```

The schema is created and migrated by Flyway on start
(`src/main/resources/db/migration/`), and the exercise catalogue is seeded
from [`catalog/core.json`](../catalog/core.json) on every start.

## Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | required | JDBC URL, `jdbc:postgresql://host:5432/db`. The server refuses to start without it, or when it cannot reach the database. |
| `DATABASE_USER` | | |
| `DATABASE_PASSWORD` | | |
| `PORT` | `8080` | |
| `TUNG_PUBLIC_URL` | | The public base URL, e.g. `https://tung.example.org`, exactly as clients reach it (scheme, host, port, no trailing slash). It is the OAuth issuer and the base of every OAuth and MCP URL, and is named as the server in `/openapi.yaml`. Set it whenever the server is used beyond localhost; see "MCP". |
| `TUNG_FCM_SERVICE_ACCOUNT_JSON` | | A Firebase service-account key for FCM push: the path to the JSON file (inside the container) or the JSON itself (starting with `{`). Empty: FCM push is off. An unreadable or invalid key stops the server at start. See "Push". |
| `TUNG_PUSH_ALLOW_PRIVATE` | `false` | `true` lets UnifiedPush endpoints be plain `http://` and private, loopback or link-local addresses: for a self-hosted instance whose push distributor (ntfy) is on the same LAN. Leave `false` on any instance others can register on: the server would then POST to addresses inside your network. See "Push". |
| `TUNG_GOOGLE_CLIENT_ID` | | The Google OAuth client id (a Web application client, `….apps.googleusercontent.com`) whose ID tokens sign users in; several comma-separated are accepted, and the first is the one clients are told to use. Empty: nothing Google-related is shown or served. See "Sign in with Google". |
| `TUNG_TRUST_PROXY` | `false` | `true` behind a reverse proxy that sets `X-Forwarded-For`/`Forwarded`, so the rate limit and the log see the client. Leave `false` when clients reach the server directly, or they can spoof their address. |

## API

Everything is JSON with snake_case fields; timestamps are epoch milliseconds;
errors are `{"error": {"code": "…", "message": "…"}}`. All data is the
caller's own: another user's ids answer 404.

| | |
|---|---|
| `GET /healthz` | No auth. 200 when the database answers, 503 otherwise. |
| `POST /api/v1/auth/register`, `/login` | `{email, password}` → a session token. 10 requests a minute per client address. |
| `POST /api/v1/auth/google` | `{id_token}` → a session token, as `/login` answers. Only with `TUNG_GOOGLE_CLIENT_ID`, else 404 `not_configured`. Rate-limited like login. See "Sign in with Google". |
| `GET /api/v1/auth/methods` | No auth. `{"password": true, "google": {"client_id": "…"}}`, or `"google": null` without a Google client id. |
| `POST /api/v1/auth/logout` | Revokes the token used. |
| `GET /api/v1/me` | The account, with `has_password` and `google_linked`. |
| `GET/POST /api/v1/tokens`, `DELETE /api/v1/tokens/{id}` | List, create (`{name, scope}`, scope `read` or `write`) and revoke API tokens. |
| `POST /api/v1/link/start`, `POST /api/v1/link/claim` | Sign a watch in with a six-digit code instead of a password. See "Linking a device". |
| `GET/POST /api/v1/exercises`, `PATCH /api/v1/exercises/{id}` | The catalogue plus your own; create custom ones; rename yours, set the rest time of any. |
| `GET /api/v1/exercises/{id}/last` | Last time: the previous finished session's sets for that exercise. |
| `GET /api/v1/workouts?limit&before` | Newest first, exercises and sets nested. |
| `GET/PUT/DELETE /api/v1/workouts/{id}` | `PUT` is a full nested upsert by the client's ids. |
| `POST /api/v1/workouts/{id}/finish` | Drops unlogged sets, as the app does. |
| `GET/POST /api/v1/routines`, `GET/PUT/DELETE /api/v1/routines/{id}` | Routines with their exercises. `POST`/`PUT` by the same `updated_at` rule as workouts; `DELETE` also removes the program days that use the routine, as the app does. |
| `GET/POST /api/v1/programs`, `GET/PUT/DELETE /api/v1/programs/{id}` | Programs with their days. At most one program is active: the newest activation wins. |
| `POST /api/v1/sync` | Push changed rows, pull everything changed since your cursor. See "Sync". |
| `GET/POST /api/v1/push/endpoints`, `DELETE /api/v1/push/endpoints/{id}` | Where the server wakes a device when another client changes your data. See "Push". |
| `GET /api/v1/export` | Everything you have (exercises, workouts, routines, programs), as one JSON attachment. |
| `POST /api/v1/commands/{name}` | Runs a command from core by its wire name (`start_workout`, `log_set`, `apply_plan`, `save_routine_plan`, …), the same ones the app's buttons and the MCP tools use. See "Commands". |
| `/.well-known/oauth-*`, `/oauth/*` | The OAuth 2.1 authorization server for MCP clients. See "MCP". |
| `POST /mcp` | The MCP server (streamable HTTP). See "MCP". |

**Tokens.** Register and login return a session token; scripts and agents
get their own named tokens from `/api/v1/tokens`. Both are `tung_` plus 32
random bytes, sent as `Authorization: Bearer tung_…`, shown once and stored
only as a SHA-256. A `read` token can only make GET requests. Passwords are
hashed with Argon2id (Bouncy Castle; 19 MiB, 2 iterations). MCP clients get
their tokens through OAuth (see "MCP"); those are ordinary API tokens named
`mcp:<client>` that expire after 30 days unless the client refreshes them
(`expires_at` in the token list). Besides the password, an instance can
sign users in with Google (see "Sign in with Google"); there is no other
external identity provider.

**Linking a device.** A watch cannot reasonably take an email and a
password, so a signed-in device (the phone's Settings, "Link a watch") calls
`POST /api/v1/link/start` with a `write` token and shows the six-digit code it
gets, valid for ten minutes. The watch sends it to `POST /api/v1/link/claim
{code}`, which needs no token, and receives `{token, user}`: a new `write`
token named `watch`, revocable like any other under `/api/v1/tokens`. A code
works once; an unknown, expired or used code is 404 `invalid_code`. Codes are
limited to 5 a minute per user, claims to 10 a minute per client address
(counted in the process, in `routes/LinkRoutes.kt`), and live in
`link_codes` (migration V4), where expired rows are deleted as new codes are
made.

**Writes go by `updated_at`.** Training data keeps the ids the client
generated. Every row (workout, workout exercise, set; routine, routine
exercise, program, program day) carries `updated_at`,
and a write replaces the stored row only when it is strictly newer; equal or
older writes are ignored row by row, so replays are harmless and a stale
device cannot overwrite a newer edit. Deletes are tombstones (`deleted_at`).
Sync uses the same rule.

## Sign in with Google

Optional, next to email and password, in the app (Settings) and on the
OAuth consent page that MCP clients send you to. Off until the operator sets
`TUNG_GOOGLE_CLIENT_ID`; the app learns the client id from
`GET /api/v1/auth/methods` and never hardcodes one, so every instance uses
its own.

**Verification** (`auth/GoogleIdTokens.kt`, no Google library): the ID
token must be RS256, issued by `accounts.google.com`, for one of the
configured client ids, unexpired (60 s leeway for clock skew) and carry
`iat`; its signature is checked against Google's key set
(`https://www.googleapis.com/oauth2/v3/certs`), kept as long as its
`Cache-Control: max-age` says (an hour without one) and fetched again, at
most once a minute, when a token names a key it does not have. A rejected
token is 401 `invalid_google_token`; a key set that cannot be fetched is 503
`google_unavailable`.

**Which account.** The account already linked to that Google account
(`users.google_sub`, migration V10). Else, when Google says the email is
verified, the account with that email: it gets linked and keeps its
password. Else a new account without a password, which can only sign in with
Google (password login answers 401 like a wrong password). An email Google
has not verified that is linked to nothing is refused (401
`email_not_verified`); an email whose account is linked to another Google
account is 409 `google_account_mismatch`. `/api/v1/me` says `has_password`
and `google_linked`.

**The consent page** shows "Continue with Google" (Google Identity Services,
`https://accounts.google.com/gsi/client`, redirect mode) above the password
form, and only then loads anything from elsewhere; its Content Security
Policy allows exactly GIS's script, styles, iframe and fetches under
`accounts.google.com/gsi/`, and the referrer policy sends Google the origin.
Google posts the credential to `POST /oauth/google` with its `g_csrf_token`
(cookie and field must match) and the button's `state`, the authorization
request signed by this server; the token is verified and the account chosen
exactly as above, and the page asks Allow or Deny, then the client gets its
code as after a password. The signing key is made at start, so a page opened
before a restart has to be reopened.

**Setting it up.** In the Google Cloud console (the Firebase project's own
GCP project works), *APIs & Services → Credentials → Create credentials →
OAuth client ID*, type **Web application**:

- *Authorised JavaScript origins*: the public URL, exactly
  `TUNG_PUBLIC_URL` (e.g. `https://tung.example.org`), for the button on the
  consent page.
- *Authorised redirect URIs*: `https://tung.example.org/oauth/google`, where
  Google posts the credential in redirect mode.
- The OAuth consent screen (*Google Auth Platform → Branding*) needs an app
  name and a support email; the scopes are only `openid`, `email` and
  `profile`, which need no verification.

Set its client id as `TUNG_GOOGLE_CLIENT_ID` and redeploy. The Android app
uses **the same web client id** as Credential Manager's `serverClientId`;
it also needs an *Android* OAuth client in the same project, with the
package name `io.github.kanylbullen.tung` and the SHA-1 of the signing key
(`keytool -list -v -keystore …`, or Play Console's app signing page), or
Google refuses to hand the app a token. The Android client's id is never
configured anywhere; Google matches it by package and signature.

## Sync

`POST /api/v1/sync` keeps the app's eight tables (exercises, workouts,
workout exercises, sets, routines, routine exercises, programs, program days)
in step with the server, row by row. The protocol types are shared with the
app in [`core/.../sync/SyncProtocol.kt`](../core/src/main/kotlin/io/github/kanylbullen/tung/core/sync/SyncProtocol.kt);
the full description is in the OpenAPI spec.

```json
{"cursor": 0, "changes": {"sets": [{"id": "…", "workout_exercise_id": "…", "position": 0,
  "weight_kg": 80.0, "reps": 8, "completed": true, "updated_at": 1700000000000, "deleted_at": null}]}}
```

The answer has the same shape: `{"cursor": …, "changes": {…}}`.

- **Newer wins, per row.** A pushed row is written only when its
  `updated_at` is strictly newer than the stored one (or nothing is stored).
  Equal or older rows are ignored, and the stored version comes back in the
  answer, so the client converges on it.
- **Tombstones.** A row with `deleted_at` set deletes a stored, older row by
  setting `deleted_at` and `updated_at`; the row stays, so the delete can be
  synced to the next device and an older edit cannot bring it back. A newer
  live row can. A tombstone for a row the server never had is dropped.
- **`seq` and the cursor.** Every insert and every update of a synced table,
  through any endpoint, takes a new `seq` from one Postgres sequence
  (`sync_seq`), stamped by a trigger so no write path can forget it. The
  answer holds every row of yours with `seq` above the request's `cursor`
  (including what you just pushed) and the new `cursor`, the highest `seq` in
  it; the request's cursor when nothing changed. Send 0 the first time.
- **One user, one writer at a time.** Every transaction that writes training
  data, and every sync, first takes a Postgres advisory lock on the user.
  Sequence values are handed out in call order, not commit order; without
  the lock a pull could see seq 11 before seq 10 commits and skip 10 for good.
- **Order inside a request does not matter.** Parents are written first, and
  the foreign keys between training-data tables are `DEFERRABLE INITIALLY
  DEFERRED`, checked at commit. A child whose parent (or exercise) does not
  exist at the end fails the whole request with 409 `missing_parent`, naming
  the rows. A live child of a tombstoned parent is fine: the parent row still
  exists.
- **One active program.** Two devices can each activate a different
  program offline. After a push that carried programs, the server keeps the
  active program with the newest `updated_at` (a tie goes to the one just
  pushed) and switches the others off with a newer `updated_at`, so the
  device that lost gets the correction on its next pull. `PUT` and `POST
  /api/v1/programs` apply the same rule.
- **`started_by`** on a workout says who started it: `phone`, `watch`,
  `agent` (a REST command or an MCP tool; the server stamps it) or `server`
  (reserved), null when unknown. It is set when the workout is made and never
  changed: a newer row that carries null keeps the stored value.
- **The catalogue is never written.** An exercise row with a catalogue id is
  your own copy of it (for its rest time) and takes only `rest_seconds` from
  the client, like `PATCH /exercises/{id}`.
- **Validation** is core's, as for every other write (weight 0–999 kg, reps
  0–999, rest 0–3600 s, target sets 1–20, names not blank), plus the columns
  a live row needs. Any invalid row rejects the whole request with 400
  `invalid_rows`, naming every offending row; nothing is written.
- **Limits:** 20 000 rows (`SYNC_MAX_ROWS`) and 8 MiB per request, else 413.
  A `read` token can pull (empty `changes`) but not push (403).
- One transaction per request. 10 000 sets in one request take about 3 s
  end to end on the dev server (`SyncTest.tenThousandSetsInOneRequest`).

## Commands

`POST /api/v1/commands/{name}` runs one command of core's command surface
(`core/command/`) under its stable wire name, with the command's fields as a
snake_case JSON body. It goes through the server's command handler, the same
one every MCP tool uses: core's validation, the app's rules (one workout in
progress, log set only ticks unless `add_next` asks for the next set (never
past a routine's target), finish
drops unlogged sets, …), one transaction, and the change reaches the phone on
its next sync. A `write` token is needed.

```sh
curl -X POST https://tung.example.org/api/v1/commands/apply_plan \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"exercises": [{"exercise": {"key": "deadlift"}, "set_count": 5, "reps": 5, "weight_kg": 120},
                     {"exercise": {"name": "Bänkpress"}, "sets": [{"weight_kg": 80, "reps": 8}]}]}'
```

The answer is `{"status": "done", "ids": {"workoutId": "…"}}` (200),
`{"status": "needs_answers", "questions": [...]}` (200) when an exercise name
did not resolve exactly (nothing is written; each question has up to five
suggestions with the `ref` that picks it), or `{"status": "rejected",
"reasons": [...]}` (422). Every command and its fields are in the OpenAPI
spec.

## MCP

The server is an MCP server at `/mcp` (streamable HTTP; protocol versions
2025-06-18, 2025-03-26 and 2024-11-05), with OAuth 2.1 built in, so an
assistant can read your history and log for you.

**Connect.** Add `https://tung.example.org/mcp` as a remote MCP server (a
"custom connector" in Claude.ai or ChatGPT, `claude mcp add --transport http
tung https://tung.example.org/mcp` in Claude Code, a `url` entry in Cursor's
`mcp.json`). The client registers itself, opens Tung's login page in your
browser, and you log in and allow it there; nothing to copy. Scripts and
clients without OAuth can send an API token from `/api/v1/tokens` as
`Authorization: Bearer tung_…` instead.

**Scopes.** `read` lets the assistant read; `write` (the default) also lets it
change your training data. With a read connection, tools that change data
answer with an error and change nothing. Every connection is an API token
named `mcp:<client>` in your token list: revoking it there (or with `DELETE
/api/v1/tokens/{id}`) ends that client's access, refresh included.

**Self-hosting.** Set `TUNG_PUBLIC_URL` to the URL clients use, e.g.
`https://tung.example.org`: it is the OAuth issuer, and clients check that the
issuer and endpoints they discover match the server they connected to. Without
it the server uses the host each request reached, which is fine on localhost
and breaks behind a proxy. Serve it over https; behind a reverse proxy also set
`TUNG_TRUST_PROXY=true`.

**How it works.** A 401 from `/mcp` carries `WWW-Authenticate: Bearer
resource_metadata="…/.well-known/oauth-protected-resource"` (RFC 9728), which
names this server as the authorization server
(`/.well-known/oauth-authorization-server`, RFC 8414). Clients register at
`/oauth/register` (RFC 7591; open, rate limited; redirect URIs must be https,
http on localhost, or an app's own scheme, and are matched exactly), send you
to `/oauth/authorize` (PKCE S256 required; a server-rendered login and consent
page with a CSRF token), and swap the code at `/oauth/token`. Codes live five
minutes and work once; access tokens live 30 days; refresh tokens rotate on
every use, and one presented twice revokes the grant. Codes, secrets and
refresh tokens are stored as SHA-256.

**Unused clients expire.** Registration is open, and a client that loses
its `client_id` simply registers again, so the server cleans up: every
`/oauth/authorize` and `/oauth/token` call by a client stamps its
`last_used_at` (migration V8), and once at start and then every 24 hours the
server deletes the clients not used for 90 days (counting from registration
when never used) that hold no live grant, meaning no access token of theirs
that is unrevoked and either unexpired or still refreshable. Their codes and
refresh tokens go with them; the expired `mcp:<client>` tokens stay in your
token list until you revoke them. A client connected and in use is never
touched, and one that is deleted just registers again on its next connect.
There is nothing to configure.

**Tools.** One per command, with the same name and fields as
`/api/v1/commands/{name}`: `start_workout`, `start_routine`, `add_exercise`,
`remove_exercise`, `add_set`, `remove_set`, `set_weight`, `set_reps`,
`tick_set`, `log_set`, `select_current`, `finish_workout`, `delete_workout`,
`apply_plan`, `set_rest`, `create_exercise`, `save_routine`,
`save_routine_plan`, `rename_routine`, `delete_routine`,
`move_routine_exercise`, `adjust_target_sets`, `create_program`,
`rename_program`, `activate_program`, `deactivate_program`, `delete_program`,
`add_program_day`, `remove_program_day`, `move_program_day`,
`toggle_program_weekday`. And the read tools: `history(limit, before)`,
`last_time(exercise)`, `current_workout()`, `routines()`, `programs()`,
`exercises(query)`. Weights are kilograms; exercises are named by catalogue
key, id or exact name in any catalogue language, and an unknown name comes
back as a question with suggestions, never a guess.

**Prompts.** `suggest_program(goal, days_per_week, equipment)`,
`next_workout()` and `plateaus()`: each tells the assistant to read your
history first, to answer in your language, and to save a routine only after
you confirm. **Resource:** `tung://catalog/core`, the core catalogue.

## Push

A workout an assistant starts through MCP should be on your phone now, not
at its next 15-minute sync. So after every change to your training data
(a sync that pushed rows, a command, an MCP tool that writes, the REST
writes), the server sends your **other** devices an empty "sync now",
`{"type":"sync"}`, and they sync to see what changed. Never content, so
nothing about your training passes through Google or a push distributor.

Two transports; the app picks one per device and registers it:

- **UnifiedPush** needs nothing on the server. The device's distributor
  (ntfy is the usual one, and self-hostable) gives it a URL, the app
  registers that URL, and the server POSTs to it. Because that URL comes
  from a user, the server refuses to be pointed inward (SSRF): by default an
  endpoint must be `https://`, and its host, an IP literal or every address
  its name resolves to, must not be loopback, link-local (including the
  cloud metadata address 169.254.169.254), private (10/8, 172.16/12,
  192.168/16, fc00::/7), carrier-grade NAT (100.64/10), multicast or
  broadcast. That is checked at registration (400 `invalid_endpoint`) and
  again before every push, redirects are never followed, the body is always
  `{"type":"sync"}`, at most 1 KiB of the answer is read, and a push gives up
  after 5 s. A distributor on your own LAN (`http://192.168.1.20:2586/…`)
  needs `TUNG_PUSH_ALLOW_PRIVATE=true`, which lifts both rules; set it only
  on an instance where you trust everyone who can register. One gap remains:
  a name that resolves to a public address when checked and to a private one
  a moment later (DNS rebinding).
- **FCM** needs a Firebase project. Create one, download a service-account
  key (Project settings, Service accounts, Generate new private key), and set
  `TUNG_FCM_SERVICE_ACCOUNT_JSON` to the path of that file inside the
  container or to its JSON. The app build must carry the `google-services.json`
  of the same Firebase project.
  Without the variable FCM is off: the server says so once at start, keeps
  devices' FCM registrations and sends them nothing.

With neither, nothing changes: devices sync every 15 minutes and when the
app opens, as before.

**An app build with FCM.** The open-source build has no Firebase app, so it
never picks FCM. To build one that does (a self-hoster with their own
Firebase project, or the project's own release builds): in the Firebase
console add an Android app with the package name `io.github.kanylbullen.tung`,
download its `google-services.json` into `app/` (and `wear/`, if the watch
build should carry it too), and build as usual. The Gradle build applies the
google-services plugin only when that file exists. The file is in
`.gitignore`: never commit it, every instance brings its own. The server's
`TUNG_FCM_SERVICE_ACCOUNT_JSON` must be a key of the same Firebase project.

**Endpoints API.** `POST /api/v1/push/endpoints {device_id, transport,
endpoint}` (write token) registers a device: `transport` is `fcm` (endpoint
= the registration token) or `unifiedpush` (endpoint = the distributor's
URL). One endpoint per `device_id`: registering again replaces it in place
and keeps its id (200; new devices get 201). `GET` lists your active
endpoints, `DELETE /api/v1/push/endpoints/{id}` revokes one. At most 20 per
user. A client sends its own `device_id` as the `X-Tung-Device` header with
its writes, so it is not woken by its own change; a request without the
header (a script, an MCP client) counts as a client of its own, and every
device is woken.

**Rules.** Pushes are debounced 2 s per user: the first change opens the
window, the rest fold into it, and one push per device goes out when it
closes, skipping only a device that made every change in it. A pull-only
sync wakes nobody. An endpoint that answers 404 or 410 (FCM: `UNREGISTERED`)
is revoked; any other failure is logged at warn (endpoint id, transport and
host only, never the URL or token) and dropped, never retried. One push per
endpoint is in flight at a time; the timeout is 5 s. Endpoints live in
`push_endpoints` (migration V6).

## Not in v0

The app's server setting and sync client (the app half of
[#25](https://github.com/kanylbullen/tung/issues/25)), paging of large sync
answers, a server-to-client MCP event stream (GET `/mcp`
answers 405; the server sends nothing unasked), identity providers other
than Google, setting a password on a Google-only account, unlinking Google,
password reset, email verification and account deletion.

## Develop

```sh
./gradlew :server:test --no-daemon
```

The tests start a real Postgres (`postgres:17-alpine`) with Testcontainers,
so they need Docker. `OpenApiCoverageTest` walks the application's routing
tree and fails when a route is missing from `openapi.yaml`, or the spec
describes one the server does not have.
