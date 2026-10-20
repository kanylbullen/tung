# tung-server

The optional backend for Tung: accounts, API tokens, a REST API over your
workouts with an OpenAPI specification, and a JSON export. One Ktor
application with Postgres, shipped as one Docker image. The app works fully
without it; sync (the app talking to the server) is the next step and is not
in this version.

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
| `GET /api/v1/export` | Everything you have, as one JSON attachment. |

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
This is the rule sync will use.

## Not in v0

Routines and programs (no tables yet; the schema leaves room), sync and the
app's server setting, the command routes (`core/command/` exposed one to one
under their wire names) and MCP, OAuth/OIDC, password reset, email
verification and account deletion.

## Develop

```sh
./gradlew :server:test --no-daemon
```

The tests start a real Postgres (`postgres:17-alpine`) with Testcontainers,
so they need Docker. `OpenApiCoverageTest` walks the application's routing
tree and fails when a route is missing from `openapi.yaml`, or the spec
describes one the server does not have.
