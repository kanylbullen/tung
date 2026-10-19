# Tung: architecture and direction

*Decided on 2026-10-19. This document describes where the app is heading;
`CLAUDE.md` describes how it is built right now.*

## Goals

1. **Open source.** Everything except the user's own training data is open:
   app, server, MCP server, exercise catalogue, documentation.
2. **Open API.** Everything the app can do can be done over HTTP, documented
   with an OpenAPI specification. No private endpoints that the app has and
   others do not.
3. **MCP server.** An agent should be able to log a set, read history and
   answer "what did I do on bench press last time" with the same rights as the
   user.
4. **Self-hostable.** A person with a VPS should be able to run the whole
   service with one `docker compose up`. Our hosted instance is just one such
   installation.
5. **Offline first.** Gyms have poor coverage. The app works fully without a
   server; the server is optional and adds sync between devices, the API and
   MCP.
6. **The watch logs.** A Wear OS app starts workouts and takes in sets with
   weight and reps from the wrist, so the phone can stay in the locker.
7. **AI on request, with your own key if you want.** The app can produce
   suggestions for programs, the next workout and progression from your own
   history. Our instance gives a small free monthly budget; beyond that, and
   for those who want it, your own key (BYOK) or a self-hosted model.

## Shape

```
┌──────────────┐ Data Layer ┌──────────────┐  sync (HTTPS, API token)  ┌──────────────────────────┐
│ Wear OS app  │ ◄────────► │ Android app  │ ◄───────────────────────► │ tung-server (one binary) │
│ own local    │ (Bluetooth)│ Room, local  │                           │  REST + OpenAPI          │
│ log          │            │ truth        │                           │  MCP (streamable HTTP)   │
└──────────────┘            └──────────────┘                           │  auth, sync, export      │
                                                                       └────────────┬─────────────┘
                                agents / other clients ─── REST or MCP ─────────────┘
                                                                                    │
                                                                               Postgres
```

- **The app is the truth for its own device.** It always writes locally first
  and syncs when it can. No view waits for the network.
- **The server is optional.** The user points to it with a URL in the
  settings. The default is no server at all. Self-host and our instance are
  the same code and the same setting.
- **The watch is its own client with the same model.** It has its own small
  log, creates sets with its own UUIDs and `updatedAt`, and syncs with the
  phone over the Wearable Data Layer when it is nearby. The phone merges using
  the same rules it uses against the server. One sync protocol, two
  transports. So the watch works even when the phone is in the locker and
  never needs to reach the server itself.
- **The API is the contract.** The app, the MCP server and third parties go
  through the same REST surface. The MCP tools are thin wrappers over the API,
  not a separate way into the database.

## Decisions taken

| Question | Decision | Why |
|---|---|---|
| App | Kotlin, Jetpack Compose, Room | Already built; native gives the best one-handed input |
| Server | Kotlin, Ktor, Postgres, one Docker image | Shares domain code with the app in a `core` module (volume, records and prefill are calculated the same on both sides). One binary is the simplest to self-host |
| MCP | Built into the server over streamable HTTP, the official Kotlin SDK | One container, one auth, one deploy. Registered in the MCP registry as `io.github.kanylbullen/tung` when it is stable |
| Watch | Wear OS, Compose for Wear OS, own `wear/` module in the same repo; Data Layer to the phone, no server contact of its own | Same language and same `core` module as the app. Burnwatch already has a Wear module on this machine to start from |
| Auth | Email + password in the server, API tokens for clients and agents; OIDC as an option later | Self-hosters should not need an identity provider. Tokens are how MCP clients and scripts connect |
| Identities | UUIDs generated on the client; catalogue exercises get deterministic UUIDs from `nameKey` | Two devices can create data offline without collisions, and the catalogue has the same id everywhere |
| Sync | Per row: `updatedAt`, last write wins, tombstones for deletions | Training data rarely changes on two devices at the same time; simplicity beats CRDT here |
| Exercise catalogue | Own curated core (70 exercises, sv + en) as open data under CC0; broader English catalogue imported from free-exercise-db (Unlicense), reached via search | The picker should show what you actually do, not 900 exercises. wger was rejected: CC-BY-SA would spread to the catalogue, and it has 3 of 932 exercises in Swedish |
| Health Connect | App-side only: write the session as strength training, read heart rate and calories, attach them to the session and sync them as columns | Health Connect has no server API; the server sees the values through sync like all other data |
| AI | MCP first: the user's own assistant reads history and saves programs via `save_routine`. In-app as a second choice with BYOK, operator key or a small free budget | See the AI features section. MCP means we do not pay for tokens and the feature comes along with self-hosting; same validation whichever route |
| Personal data | Never leaves the device unless sync is on. JSON export at any time. No analytics or telemetry in the app | It is the only part that is not open, and it should belong to the user |

## Health Connect

Health Connect is an on-device store on the phone with no cloud API, so the
server can never talk to it. The integration therefore lives in the Android
app and the server sees its results through ordinary sync.

- **Write.** When a session ends, the app writes an exercise session of type
  strength training to Health Connect, with one segment per set where Health
  Connect has a matching exercise type (bench press, squat, deadlift and so
  on), so Google Fit, Samsung Health, Fitbit and others show the workout.
  Opt-in, asked once, with the standard permission sheet.
- **Read.** During and after a session the app can read heart rate, active
  calories and, when present, the watch's own exercise record for the same
  interval, and attach them to the session (average and maximum heart rate,
  calories). Opt-in separately from write.
- **Sync.** The attached values are columns on the session and sync like
  everything else, so the API, MCP and the history on another device see
  them. The server stores what the phone sent and never reads Health Connect
  itself.
- **Watch.** The Wear OS app records heart rate through Health Services on the
  watch; it reaches the phone either through the watch's own Health Connect
  sync or through our Data Layer sync, whichever arrives first. The phone
  deduplicates by interval.
- **Self-host and API.** Nothing changes: a self-hosted server receives the same
  columns. A client without Health Connect (iOS one day, a web client) simply
  leaves them empty.

## AI features

**MCP first.** Anyone who already has an AI (Claude, ChatGPT, a local model
with an MCP client) connects Tung's MCP server and asks their own assistant:
"make me a three-day program for a home gym from my history". The assistant
reads the history with the read tools and saves the result with a write tool,
`save_routine`, which only accepts a structured program (days → exercises as
catalogue keys → sets × reps) and validates it against the catalogue. We pay
no tokens, the user uses the model and the subscription they already have, and
the feature works just as well against a self-hosted instance. The MCP server
ships with ready-made *prompts* ("Suggest a program", "Next workout", "Where
have I got stuck") so that the assistant gets the right frame without the user
having to phrase it.

**In-app as a second choice.** For those who have no AI, or who want the
suggestion right in the app between two sets, the same three questions are
available as buttons in the app. The answer must have the same structure that
`save_routine` accepts, so the validation is one and the same code. The model
then comes from one of three places:

| Mode | Key lives | Who pays | Typical user |
|---|---|---|---|
| BYOK | On the phone, in Android Keystore; never sent to our server | The user, directly to the provider | Someone who wants it in the app and has a key |
| Self-host | In the server configuration, for everyone on the instance | The operator | Someone who runs their own server, preferably against a local model |
| Our instance, free budget | On the server | We do, up to a small monthly token quota per account, counted on the server and visible in the app | Someone who has neither an AI nor a key |

BYOK speaks the OpenAI-compatible or the Anthropic API directly from the app,
with a configurable base URL, so that Ollama or another local server works
without us doing anything special. The free budget on our instance is
deliberately last: it is the only one that costs us, and the MCP route makes it
an exception rather than the rule.

**Non-negotiable rules.** The call happens only when the user presses the
button or asks their assistant, never in the background. The app shows what is
sent before it is sent the first time. What is sent is training data and
settings, never email, name or device id. A suggestion that refers to an
exercise that is not in the catalogue is discarded, not shown.

## Open decisions

- **Licence.** AGPL-3.0 is the proposal: a self-hostable service with an API is
  exactly the case AGPL was written for, and the goal is open source in the OSI
  sense (FSL, which ScreenTimeAgent uses, is not). Catalogue data under CC0
  regardless. The owner's decision; no `LICENSE` goes in the repo until it is
  taken.
- **Server storage for the smallest self-host.** Postgres is the default. If it
  turns out that people want to run on a Raspberry Pi without Postgres, SQLite
  can be added behind the same repository interface; that is decided when
  someone asks.
- **Web client.** Not planned. The API makes it possible for someone else.

## Order of work

1. **Now:** UUID keys and `updatedAt` in the app (in progress), licence
   decision, repo public.
2. **Catalogue:** import script from free-exercise-db into `catalog/`, the core
   broken out as an open data file that both app and server read.
3. **`core` module:** the domain logic moves out of `app/` into a Kotlin module
   without Android dependencies so that the server can use it.
4. **Server v0:** account, API token, OpenAPI, CRUD for workouts and sets,
   export. `docker compose` with Postgres. No sync yet; API first.
5. **Sync in the app:** server URL in settings, push/pull of changes since the
   last sync, tombstones.
6. **MCP:** tools over the API: `log_set`, `last_time`, `start_workout`,
   `finish_workout`, `history`, `save_routine`, plus prompts for program,
   next workout and plateaus. Registration in the MCP registry.
7. **Watch v0:** see the current workout, next set with last time as the
   suggestion, weight and reps with the crown or the bezel in large steps,
   confirm with a tap, rest timer with haptics. Start a workout from the watch
   with the latest exercises. Requires that the `core` module (step 3) exists,
   not the server.
8. **Our instance**, the same image that self-hosters get. During beta it
   runs on Coolify in the home lab. Once out of beta it moves to a hosted
   server, most likely HostUp in Sweden, decided 2026-10-19. The move must be
   a DNS change and a restore, not a project, which sets three rules from
   day one: Postgres is backed up nightly with `pg_dump` to S3-compatible
   storage outside the home lab and the restore is rehearsed before beta
   ends; nothing is stored on the host except the Postgres volume; and the
   public hostname is ours, never the host's, so clients, API tokens and MCP
   registrations survive the move unchanged.
9. **Health Connect:** write sessions after step 5 so the attached heart
   rate and calories sync; read support once the watch exists, since that is
   where the heart rate comes from.
10. **Templates and in-app AI:** `Routine` in the data model already comes in
   step 6 because `save_routine` needs it. In-app suggestions in BYOK mode
   after that, budget mode on our instance last, and only if the MCP route
   turns out to be too narrow for many people.

Anything that involves money or a hosted paid service is deliberately out of
scope. If it comes later, it lives in our instance, not in the code everyone
gets.
