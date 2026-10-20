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
  **same protocol as the phone uses with the server**: over Wi-Fi straight to
  the server when it has one, over the Wearable Data Layer to the phone when
  the two are paired and the phone merges on its behalf. One sync protocol,
  two transports, and the watch chooses whichever is available. Decided
  2026-10-20: the watch must work **without a paired phone**. Galaxy Wearable
  refuses to run on a modified Android (LineageOS on both test phones), and a
  Samsung watch cannot be paired any other way, so a companion-only watch
  would exclude exactly the people who self-host. The one-time setup of a
  Galaxy Watch still needs a stock phone once; after that the watch lives on
  its own Wi-Fi and Google account and never needs the phone again.
- **The API is the contract.** The app, the MCP server and third parties go
  through the same REST surface. The MCP tools are thin wrappers over the API,
  not a separate way into the database.

## Decisions taken

| Question | Decision | Why |
|---|---|---|
| App | Kotlin, Jetpack Compose, Room | Already built; native gives the best one-handed input |
| Server | Kotlin, Ktor, Postgres, one Docker image | Shares domain code with the app in a `core` module (volume, records and prefill are calculated the same on both sides). One binary is the simplest to self-host |
| MCP | Built into the server over streamable HTTP, the official Kotlin SDK | One container, one auth, one deploy. Registered in the MCP registry as `io.github.kanylbullen/tung` when it is stable |
| Watch | Wear OS, Compose for Wear OS, own `wear/` module in the same repo; its own log and sync with the server when linked with a code, the Data Layer to the phone otherwise | Same language and the same `core` and `data` modules as the app. Burnwatch already has a Wear module on this machine to start from |
| Auth | Email + password in the server, API tokens for clients and agents; OIDC as an option later | Self-hosters should not need an identity provider. Tokens are how MCP clients and scripts connect |
| Identities | UUIDs generated on the client; catalogue exercises get deterministic UUIDs from `nameKey` | Two devices can create data offline without collisions, and the catalogue has the same id everywhere |
| Sync | Per row: `updatedAt`, last write wins, tombstones for deletions | Training data rarely changes on two devices at the same time; simplicity beats CRDT here |
| Exercise catalogue | Own curated core (70 exercises, sv + en) as open data under CC0; broader English catalogue imported from free-exercise-db (Unlicense), reached via search | The picker should show what you actually do, not 900 exercises. wger was rejected: CC-BY-SA would spread to the catalogue, and it has 3 of 932 exercises in Swedish |
| Import | Adapters in `core/`, Jefit first, then Strong and Hevy; alias table in `catalog/`, review list for unmatched names, deterministic ids, source marked on the session | The import decides whether someone switches; it must be safe to run twice |
| Health Connect | App-side only: write the session as strength training, read heart rate and calories, attach them to the session and sync them as columns | Health Connect has no server API; the server sees the values through sync like all other data |
| AI | MCP first: the user's own assistant reads history and saves programs via `save_routine`. In-app as a second choice with BYOK, operator key or a small free budget | See the AI features section. MCP means we do not pay for tokens and the feature comes along with self-hosting; same validation whichever route |
| Private mode | Optional E2EE per account: content encrypted on the device, structure in the clear for sync; server commands and MCP refuse private accounts, local MCP instead | Protects against the operator of the public instance; the price is hosted assistants, said up front. Planned after the Standard beta |
| Personal data | Never leaves the device unless sync is on. JSON export at any time. No analytics or telemetry in the app | It is the only part that is not open, and it should belong to the user |

## Import from other apps

Someone switching from Jefit, Strong or Hevy decides on the import. It is
built as adapters in `core/`, pure parsing with no Android dependency, one
adapter per source format recognised by its header row. Jefit first: its
export is a single CSV with the whole account history, produced on the
website under My Jefit, Settings, Data Controls, Export Data. Strong and Hevy
have documented CSV formats and follow in the same frame.

- **Exercise names map to catalogue keys** through an alias table in
  `catalog/`, the same mechanism the free-exercise-db import uses. Rows that
  do not match go to a review list where the person picks an exercise, skips
  the row, or creates a custom exercise. Nothing is guessed silently.
- **Idempotent.** Every imported row gets a deterministic UUID from the source
  and the row content, so importing the same file twice adds nothing and an
  interrupted import can be run again.
- **Traceable.** Imported sessions carry their source, so old sets do not
  trigger "new record" and an import can be undone in one step.
- **Two entry points.** From a file in the app, and through the API on the
  server once it exists, so an MCP client can import on request.

The Jefit column layout is not documented anywhere public; the parser is
written against a real export, not a guess.

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

## One command surface: API, MCP, voice, camera

Everything that changes training data goes through one set of **commands**
in `core/`, with the same names and the same validation wherever they are
called from: the app's buttons, the REST API, the MCP tools, voice
dictation, camera recognition and the in-app AI. A command is a plain data
class (`StartWorkout`, `AddExercise(exerciseKey)`, `LogSet(exercise,
weightKg, reps)`, `SetCurrent`, `FinishWorkout`, `SaveRoutine(plan)`,
`StartRoutine`, `CreateProgram`, …) and the result is the state it produced.
The REST API exposes them one to one, the MCP tools are the same list plus
read tools (`history`, `last_time`, `current_workout`) and prompts, and the
app's own UI calls them too, so a button and an MCP call can never drift
apart. A new input (a voice, a camera, a watch) is a translator from signal
to commands, never a new path into the database.

Two commands carry structured plans rather than single actions:
`SaveRoutine(plan)` for a programme and `ApplyPlan(plan)` for a whole workout
dictated or recognised at once ("deadlift, bench press and biceps curl, five
sets of ten, 50, 60 and 20 kg"). A plan names exercises by catalogue key;
anything that does not resolve is returned as a question, not guessed.

Implemented in `core/command/` with the app's handler in
`data/AppCommandHandler.kt` (#21); `CLAUDE.md`, "Commands", is the
reference for the rules.

## Voice

Dictation is the first native AI feature, because it is the one that works
reliably today and in every gym.

- **Speech to text on the device.** Android's `SpeechRecognizer` with
  on-device models where the phone has them; a bundled Whisper model
  (whisper.cpp, small or base, ~75 to 250 MB, downloaded on first use) where
  it does not, which includes de-Googled phones like the test Note9. Audio
  never leaves the phone in either path.
- **Text to commands in two tiers.** A deterministic grammar in `core/`
  handles the patterns used between sets without any model: "80 kilo 8 reps",
  "same again", "next set", "90", "add 2.5", "done", "rest 2 minutes", with
  numbers in Swedish and English and the catalogue's names and aliases. It is
  instant, offline and testable. Free-form sentences that plan a whole
  workout or several exercises go to a language model (the same three modes
  as below: BYOK, operator key, our budget) with the command schema as the
  output format; the result is shown as a plan the user confirms before
  `ApplyPlan` runs. The grammar is tried first; the model is the fallback.
- **Where it lives.** A microphone button in the active workout (press and
  hold, or tap to toggle hands-free for a set), on Home for "set up a
  workout with …", and on the watch for one set at a time. Hands-free mode
  keeps listening between sets, confirms each command with a short haptic
  and the row updating, and stops on "stop" or when the workout is finished.
- **The same sentence through the user's own AI** (Claude, ChatGPT with the
  MCP server connected) ends in the same commands, so dictating to the
  assistant and dictating to the app give the same result. The assistant
  gets the grammar's job done by the model it already is.

## Camera

Recognising a workout from video is possible in parts, and the parts have
very different reliability. The plan is honest about which is which.

| What | How | Reliability | When |
|---|---|---|---|
| **Rep counting for one set** | Phone propped up, on-device pose estimation (MediaPipe or ML Kit Pose), reps counted from joint-angle cycles; nothing recorded, only counts | Good for the big lifts with a clear side view (squat, bench, deadlift, press, curl, row); poor when the view is blocked or the camera is far | First |
| **Exercise recognition** | A small on-device classifier on pose sequences, limited to the core catalogue (~70 exercises) | Usable for distinct movements, confuses close variants (incline vs flat, grips) and must offer a correction | Second, after a dataset exists; the app can collect pose keypoints, never video, from users who opt in |
| **Weight from plate colours** | Colour segmentation of the plates on the bar | Only on calibrated competition plates (red 25, blue 20, yellow 15, green 10). Most gyms have black plates, and bumpers vary by brand, so it is a hint, never a value | Later, as a suggestion the user confirms |
| **Whole-session recording** | Record the session, segment it into sets, run the above over each | Battery, storage, and above all other people in the frame: many gyms forbid filming and bystanders have not consented | Not planned as a default; if ever, on-device only and with the camera pointed at the user |

Rules: video is processed on the device and never stored or uploaded by
default; keypoints, not frames, are what the app keeps if the user opts into
helping the classifier; cloud video analysis (a multimodal model through
BYOK) is an explicit per-session choice, and the app says what is sent.
Weight is dictated or typed; the camera's counts and recognised exercise
arrive as a proposed `LogSet` or `AddExercise` the user confirms with the
same tap as any other set.

The watch does the same job more cheaply for the exercises where the hand
travels: curls, presses, rows, raises, and squats and deadlifts because the
hands follow the bar. Rep detection from the wrist's accelerometer and
gyroscope works there without any camera, in any gym, and feeds the same
`LogSet` proposal. Where the wrist is still it does not: push-ups give only a
weak forearm-tilt signal, chins almost none, leg machines and leg raises
nothing. Those are exactly what the camera sees best, and a phone in the
pocket covers some (chins, leg press) but not others (leg extension, leg
curl). So the catalogue carries a `repSensing` field per exercise (`wrist`,
`pocket`, `camera`, `none`), the app only offers a counter where a source
exists, and everywhere else the watch pre-fills last time's reps for the
user to confirm or to say aloud after the set. The phone merges whichever
signals are present; none of them writes a set without confirmation.

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

## Private mode (optional end-to-end encryption)

Planned, not built; decided 2026-10-20 so that nothing built before it makes it
impossible. An account is either **Standard** or **Private**, chosen when it is
created and changeable later with a re-encryption pass.

- **Standard** is what exists today: the server reads the data, the REST API,
  the server-side commands and the MCP tools work in full, including from
  hosted assistants.
- **Private** encrypts the content on the device: weights, reps, notes, custom
  exercise names, routine and program names. The server keeps only what sync
  needs in the clear: ids, `updated_at`, `deleted_at`, the parent references and
  the catalogue exercise ids, so it sees the shape of the training but not the
  numbers. Sync between the user's own devices works unchanged; export works
  (the device decrypts); the API returns ciphertext; the server-side commands
  and every MCP tool answer with one clear error, "this account is private".
  MCP for a private account runs **locally**, on the user's own computer or in
  the app, with the key, which serves Claude Code and Claude Desktop but not
  Claude.ai or ChatGPT. That trade-off is the point and is stated in the app
  when the mode is chosen.

Keys follow the pattern from bino, which is easier here because the app is
native: one random data key per account, wrapped by a key derived from a
passphrase with Argon2id, and by a passkey through the Credential Manager PRF
extension on Android 14+ where available, plus a recovery key shown once. The
passphrase and the recovery key use **separate** KDF parameters (a lesson from
bino, where a shared iteration count broke recovery keys). The unwrapped key
lives in the Android Keystore per device. A new device gets the key by
entering the passphrase or scanning a QR shown by an unlocked device; the key
never passes through the server, so the six-digit watch link code is not
enough on its own and the watch is given the key over the Data Layer or a QR
on the phone.

Rules for everything built from now on: every synced table gets room for an
encrypted payload column with nullable content columns; every server feature
that reads content must have a defined answer for a private account; no
server feature may become the only way to do something a Standard user can
do on the device.

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
4. **Command surface** in `core/`: the commands and `ApplyPlan`, used by
   the app's own UI first, so the server and MCP in the next steps expose
   something that already runs every day.
5. **Server v0:** account, API token, OpenAPI, CRUD for workouts and sets,
   export. `docker compose` with Postgres. No sync yet; API first.
6. **Sync in the app:** server URL in settings, push/pull of changes since the
   last sync, tombstones.
6b. **Standalone watch:** the sync client from the previous step moves, with
   Room and the repositories, into a shared Android library `data/` (not
   `core/`: it needs Room and WorkManager) so the watch uses it too; `wear/`
   gets its own local log, a server it links to with a six-digit code from
   the phone (`/api/v1/link/start` and `/claim`), `standalone=true`, and keeps
   the Data Layer transport for phones where the companion works (issue #30). Setup of the Galaxy Watch is done once
   against a stock phone, then the watch is reached over Wi-Fi adb.
7. **MCP:** tools over the API: `log_set`, `last_time`, `start_workout`,
   `finish_workout`, `history`, `save_routine`, plus prompts for program,
   next workout and plateaus. Registration in the MCP registry.
8. **Watch v0:** see the current workout, next set with last time as the
   suggestion, weight and reps with the crown or the bezel in large steps,
   confirm with a tap, rest timer with haptics. Start a workout from the watch
   with the latest exercises. Requires that the `core` module (step 3) exists,
   not the server.
9. **Our instance**, the same image that self-hosters get. MCP ships with
   OAuth 2.1 from the first deploy, so hosted assistants can connect. During beta it
   runs on Coolify in the home lab. Once out of beta it moves to a hosted
   server, most likely HostUp in Sweden, decided 2026-10-19. The move must be
   a DNS change and a restore, not a project, which sets three rules from
   day one: Postgres is backed up nightly with `pg_dump` to S3-compatible
   storage outside the home lab and the restore is rehearsed before beta
   ends; nothing is stored on the host except the Postgres volume; and the
   public hostname is ours, never the host's, so clients, API tokens and MCP
   registrations survive the move unchanged.
10. **Import from Jefit** in the app, written against a real export file;
   Strong and Hevy after it. Needs step 3 (done) and the catalogue alias
   table, not the server.
11. **Health Connect:** write sessions after step 5 so the attached heart
   rate and calories sync; read support once the watch exists, since that is
   where the heart rate comes from.
12. **Templates and in-app AI:** `Routine` in the data model already comes in
   step 6 because `save_routine` needs it. In-app suggestions in BYOK mode
   after that, budget mode on our instance last, and only if the MCP route
   turns out to be too narrow for many people.
13. **Voice:** on-device speech to text, the grammar for one set at a time,
   then the model-backed planner behind a confirmation; on the watch after
   the phone.
14. **Camera and wrist:** rep counting for one set from pose on the phone
   and from the IMU on the watch, as proposals; exercise recognition when a
   keypoint dataset exists; plate colours as a hint last.
15. **Private mode:** the key management, the payload column, the device-side
   encryption in the sync planner and applier, the "private account" answers
   on the server, and local MCP. After the Standard beta.

Anything that involves money or a hosted paid service is deliberately out of
scope. If it comes later, it lives in our instance, not in the code everyone
gets.
