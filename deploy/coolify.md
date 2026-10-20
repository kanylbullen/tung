# Tung on Coolify (beta) – runbook and hand-off

Written 2026-10-20 for the home-lab beta. Coolify runs at `192.168.12.155`
(`https://coolify1.xuper.fun` on the LAN only). The Coolify host is **not**
reachable from the internet, so public HTTPS goes through a **Cloudflare
Tunnel**, not a port forward. Once out of beta the same image moves to a hosted
server (HostUp); see `docs/architecture.md`, step 9.

## Target layout

| Resource | Name in Coolify | Notes |
|---|---|---|
| Project | `tung` | environment `production` |
| Database | `tung-postgres` | `postgres:17-alpine`, Coolify-managed, named volume, **not** public |
| Application | `tung-server` | Dockerfile build pack from the public repo `kanylbullen/tung`, `server/Dockerfile`, port 8080, health check `/healthz` |
| Public hostname | `tung.xuper.fun` (proposed) | Cloudflare DNS → Cloudflare Tunnel → `tung-server:8080` on Coolify's Docker network |
| Backups | nightly `pg_dump` to S3-compatible storage outside the home lab | Coolify's scheduled database backup to an S3 target |

Environment variables on `tung-server` (set by Claude through the Coolify API;
values live only in Coolify; resource uuids: project `xcdaw5urzre2d7nqmlbesior`,
database `9sbb7bqayjvy4ye3lhj9t1po`, application `svyizo94fhk6uvipggo4tp2h`):

```
DATABASE_URL=jdbc:postgresql://<tung-postgres uuid>:5432/tung
DATABASE_USER=tung
DATABASE_PASSWORD=<generated>
PORT=8080
TUNG_PUBLIC_URL=https://tung.xuper.fun        # must be the exact URL clients use: it is the OAuth issuer
TUNG_TRUST_PROXY=true                         # behind Traefik/cloudflared: rate limits see the client
TUNG_PUSH_ALLOW_PRIVATE=false                 # public instance: never POST to internal addresses
TUNG_FCM_SERVICE_ACCOUNT_JSON=                # later, when the Firebase project exists
```

## Who does what

### Claude (done or does itself through the Coolify API)

- [x] Project `tung`, database `tung-postgres`, application `tung-server` created.
- [x] Environment variables set, first deploy, `GET /healthz` and
      `/.well-known/oauth-authorization-server` verified on the LAN
      (2026-10-20: `running:healthy`, schema v7, issuer `https://tung.xuper.fun`;
      reached through Traefik on `192.168.12.155` with the app's `Host` header).
- [x] Public hostname live (ops, 2026-10-20): `https://tung.xuper.fun` through
      Cloudflare; `/healthz`, OAuth metadata and the `/mcp` challenge verified.
      The full OAuth 2.1 code flow with PKCE (register → authorize form →
      token → `initialize` → `tools/list` → `current_workout`) passes from a
      script. Note: Cloudflare returns 403 to python-urllib's default
      `User-Agent`; real clients and curl are fine.
- [x] MCP server re-added in Claude Code on the owner's machine with the
      public URL (`claude mcp add --transport http tung https://tung.xuper.fun/mcp`);
      the browser authentication step is the owner's.
- [x] Devserver stack stopped (`docker compose down`, volume kept; a
      `pg_dump` of its 1 user / 2 workouts is in `/home/xup/tung-backups/`),
      localhost SSH tunnel closed.
- [ ] Phone pointed at the public server: the phone was off USB at the time;
      sign in once in Settings with the test account (or your own) against
      `https://tung.xuper.fun`.
- [ ] Nightly backup schedule in Coolify once the S3 target exists, and one
      rehearsed restore into a scratch database.

### Ops agent / owner (needs access Claude does not have, or a decision)

1. **Decide the hostname.** Proposal: `tung.xuper.fun`. Anything else is fine;
   it is the OAuth issuer, so changing it later invalidates every connected
   assistant's authorization (tokens survive, consents must be redone).

2. **Public ingress: Cloudflare Tunnel.** Two options; pick one and report
   back which:
   - **(a) Reuse the existing tunnel** (`CLOUDFLARED_TUNNEL_TOKEN_CT120` in
     Phase `homelab/Production` suggests a tunnel running in container 120). If
     that host can reach `192.168.12.155`, add a public hostname route in
     Zero Trust → Networks → Tunnels → *that tunnel* → Public Hostname:
     `tung.xuper.fun` → `http://192.168.12.155:<published port of tung-server>`.
     Tell Claude the port to publish, or let Claude publish one (e.g. `8765`).
   - **(b) A dedicated tunnel as a Coolify service** next to `tung-server`
     (`cloudflare/cloudflared:latest`, `tunnel run`, token from a new tunnel
     created in Zero Trust), with the public hostname route
     `tung.xuper.fun` → `http://tung-server:8080` (Docker-network name, no
     published port needed). Put the tunnel token in Phase as
     `CLOUDFLARED_TUNNEL_TOKEN_TUNG` and tell Claude; Claude creates the
     service in Coolify and wires the network.
   In both cases: DNS record is the CNAME Cloudflare creates for the tunnel
   (proxied). Claude has `CLOUDFLARE_DNS_TOKEN` and `CLOUDFLARE_ZEROTRUST_TOKEN`
   in Phase and **can** do the Zero Trust and DNS steps itself if you prefer;
   say so and Claude does (b) end to end.

3. **LAN name (optional).** A UDMP DNS record `tung.xuper.fun → 192.168.12.155`
   so LAN clients skip the tunnel; then Coolify also needs the domain on the
   app with a DNS-01 certificate like `challengo.xuper.fun`. Not needed for
   the beta; skip unless you want it.

4. **S3-compatible bucket for backups** outside the home lab (Cloudflare R2 on
   the XUPER account, or a Hetzner Storage Box; `HETZNER_API_TOKEN` exists in
   Phase). Needed: endpoint, bucket name, access key, secret, region. Put them
   in Phase as `TUNG_BACKUP_S3_*` and add the S3 storage in Coolify
   (Settings → S3 Storages) or tell Claude to add it through the API.

5. **Firebase project** (separate from Coolify, for push over FCM): a project
   with an Android app `io.github.kanylbullen.tung`; download
   `google-services.json` (goes into `app/` and `wear/` locally and as a CI
   secret, never committed) and a service-account JSON for the server (goes
   into Coolify as `TUNG_FCM_SERVICE_ACCOUNT_JSON`). Without it push falls back
   to UnifiedPush and periodic sync; nothing breaks.

6. **Coolify API token expiry.** `COOLIFY1_ROOT_API_TOKEN` in Phase has expired
   once before after 30 days. If Claude reports `401` from the API, create a
   new token without expiry (Settings → API Tokens) and update Phase.

## Verification (Claude runs these, anyone can)

```sh
curl -s https://tung.xuper.fun/healthz                       # {"status":"ok"}
curl -s https://tung.xuper.fun/.well-known/oauth-authorization-server | jq .issuer   # "https://tung.xuper.fun"
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://tung.xuper.fun/mcp   # 401 with WWW-Authenticate
```

Then in Claude Code: `claude mcp add --transport http tung https://tung.xuper.fun/mcp`,
`/mcp` → Authenticate, sign in, ask "what did I bench last time".

## Rollback

The devserver stack (`/home/xup/tung/deploy`, port 8080 on `192.168.12.114`)
stays up until the Coolify instance has synced the phone once and MCP works;
then it is stopped (`docker compose down`, volume kept for a week).
