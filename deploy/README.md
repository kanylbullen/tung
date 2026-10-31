# Self-hosting tung-server

One container for the server, one for Postgres, one named volume for the
data. Our hosted instance runs the same image.

## Run

Needs Docker with Compose. From a checkout of the repository:

```sh
cd deploy
cp .env.example .env
# Set POSTGRES_PASSWORD (openssl rand -base64 32) and TUNG_PUBLIC_URL in .env
docker compose up -d --build
curl http://127.0.0.1:8080/healthz          # {"status":"ok"}
```

The server listens on `127.0.0.1:8080` by default (`TUNG_BIND`, `TUNG_PORT`).
Put a reverse proxy with TLS in front of it (Caddy, Traefik, nginx) and set
`TUNG_TRUST_PROXY=true` so the rate limit sees real client addresses. Then
open `https://your-host/docs`, register with `POST /api/v1/auth/register`,
and create API tokens for your scripts under `/api/v1/tokens`.

Everything the server stores is in Postgres; the server container keeps no
state and can be replaced at any time.

## Back up and restore

```sh
# Back up (run from deploy/)
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > tung-$(date +%F).dump

# Restore into an empty database
docker compose exec -T postgres sh -c 'pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists' < tung-2026-10-20.dump
```

Run the backup from cron and copy the file off the machine; a backup on the
same disk as the volume is not a backup.

## Upgrade

```sh
git pull
docker compose up -d --build
```

Database migrations run when the server starts.

## The apps

Every client syncs with the server once signed in; without an account each
keeps its log on its own device.

- **Android:** Settings → Account → "Use another server", then your server's
  address (`TUNG_PUBLIC_URL`), and sign in or create an account. Sync runs
  after each workout, when the app opens and every 15 minutes.
- **Wear OS:** link the watch from the phone (Settings → Devices → "Link a
  watch") with a six-digit code, or run it next to the phone without an
  account of its own.
- **Web:** the server serves the web app at `/app/` and a landing page at
  `/`. Served by your server, the web app offers that server at sign-in.
- **Assistants:** the API (`/docs`) and MCP at `/mcp` serve scripts, agents
  and other clients, and the export gives you everything at once.

Sign in with Google and push to phones are optional and need their own
settings (`TUNG_GOOGLE_CLIENT_ID`, `TUNG_FCM_SERVICE_ACCOUNT_JSON`); see
[`server/README.md`](../server/README.md).
