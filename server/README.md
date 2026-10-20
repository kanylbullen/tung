# tung-server

The optional backend for Tung: accounts, API tokens, a REST API over your
workouts with an OpenAPI specification, row-level sync for the app, and a
JSON export. One Ktor application with Postgres, shipped as one Docker image.
The app works fully without it.

API documentation is served by the server itself: Swagger UI at
[`/docs`](http://localhost:8080/docs), the specification at
[`/openapi.yaml`](http://localhost:8080/openapi.yaml) (source:
[`src/main/resources/openapi/openapi.yaml`](src/main/resources/openapi/openapi.yaml)).
Swagger UI loads its JavaScript from unpkg.com; the API itself makes no
outbound calls.

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
| `TUNG_PUBLIC_URL` | | The public base URL, e.g. `https://tung.example.org`; named as the server in `/openapi.yaml`. |
| `TUNG_TRUST_PROXY` | `false` | `true` behind a reverse proxy that sets `X-Forwarded-For`/`Forwarded`, so the rate limit and the log see the client. Leave `false` when clients reach the server directly, or they can spoof their address. |

## API

Everything is JSON with snake_case fields; timestamps are epoch milliseconds;
errors are `{"error": {"code": "…", "message": "…"}}`. All data is the
caller's own: another user's ids answer 404.

| | |
|---|---|
| `GET /healthz` | No auth. 200 when the database answers, 503 otherwise. |
| `POST /api/v1/auth/register`, `/login` | `{email, password}` → a session token. 10 requests a minute per client address. |
| `POST /api/v1/auth/logout` | Revokes the token used. |
| `GET /api/v1/me` | |
| `GET/POST /api/v1/tokens`, `DELETE /api/v1/tokens/{id}` | List, create (`{name, scope}`, scope `read` or `write`) and revoke API tokens. |
| `GET/POST /api/v1/exercises`, `PATCH /api/v1/exercises/{id}` | The catalogue plus your own; create custom ones; rename yours, set the rest time of any. |
| `GET /api/v1/exercises/{id}/last` | Last time: the previous finished session's sets for that exercise. |
| `GET /api/v1/workouts?limit&before` | Newest first, exercises and sets nested. |
| `GET/PUT/DELETE /api/v1/workouts/{id}` | `PUT` is a full nested upsert by the client's ids. |
| `POST /api/v1/workouts/{id}/finish` | Drops unlogged sets, as the app does. |
| `POST /api/v1/sync` | Push changed rows, pull everything changed since your cursor. See "Sync". |
| `GET /api/v1/export` | Everything you have (exercises, workouts, routines, programs), as one JSON attachment. |

**Tokens.** Register and login return a session token; scripts and agents
get their own named tokens from `/api/v1/tokens`. Both are `tung_` plus 32
random bytes, sent as `Authorization: Bearer tung_…`, shown once and stored
only as a SHA-256. A `read` token can only make GET requests. Passwords are
hashed with Argon2id (Bouncy Castle; 19 MiB, 2 iterations). There is no OIDC
and no OAuth yet: OAuth 2.1 arrives with the MCP server, which hosted
assistants need.

**Writes go by `updated_at`.** Training data keeps the ids the client
generated. Every row (workout, workout exercise, set) carries `updated_at`,
and a write replaces the stored row only when it is strictly newer; equal or
older writes are ignored row by row, so replays are harmless and a stale
device cannot overwrite a newer edit. Deletes are tombstones (`deleted_at`).
Sync uses the same rule.

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

## Not in v0

The app's server setting and sync client (the app half of
[#25](https://github.com/kanylbullen/tung/issues/25)), paging of large sync
answers, REST routes for routines and programs (sync and the export carry
them), the command routes (`core/command/` exposed one to one under their
wire names) and MCP, OAuth/OIDC, password reset, email verification and
account deletion.

## Develop

```sh
./gradlew :server:test --no-daemon
```

The tests start a real Postgres (`postgres:17-alpine`) with Testcontainers,
so they need Docker. `OpenApiCoverageTest` walks the application's routing
tree and fails when a route is missing from `openapi.yaml`, or the spec
describes one the server does not have.
