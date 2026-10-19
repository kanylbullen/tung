# Tung

**Tung** is Swedish for *heavy*. It is a strength training log for Android:
start a session, add exercises, enter weight and reps with one hand between
sets, and see what you did last time next to every set. Think Jefit, but
calmer, faster, and yours.

Early days: the app logs workouts and keeps history, locally on the phone.
Everything below the "Today" line works now. Everything below "Direction" is
planned and described in detail in [`docs/architecture.md`](docs/architecture.md).

## Principles

- **The numbers are the content.** Weight and reps are set large; everything
  else stays quiet. One accent colour, no cards, no decoration.
- **Last time, right there.** Every set shows the matching set from your
  previous session with that exercise, and new sets are pre-filled from it.
  That is the feature Jefit buries; here it is the point.
- **Offline first.** Gyms have bad reception. The app works fully without a
  network or an account. A server is optional and adds sync, an API and MCP.
- **Your data is yours.** It never leaves the device unless you turn sync on,
  and it can always be exported.
- **Everything except personal data is open.** Code, server, exercise
  catalogue, documentation.

## Today

- Home: start or continue a session, recent sessions with duration and volume.
- Active session: exercises and sets, "last time" under each set, a large
  input sheet for weight (steps of 2.5 kg) and reps.
- Exercise picker with search, grouped by muscle, plus custom exercises.
- History with session detail, and a summary after each session with best
  sets and new records.
- Swedish and English. Dark theme by default, light follows the system.
- 70 curated exercises seeded on first start.

Stack: Kotlin, Jetpack Compose, Material 3, Room. minSdk 26, targetSdk 35.
No backend, no analytics, no network permission.

## Direction

- **Open API.** Everything the app can do is available over HTTP with an
  OpenAPI spec. No private endpoints.
- **MCP server** built into the same server binary, so the AI you already use
  (Claude, ChatGPT, a local model) can log a set, answer "what did I bench
  last time", and save a training programme it wrote for you from your own
  history. The programme goes through `save_routine`, which only accepts a
  structured plan validated against the catalogue.
- **Self-hosting** with one Docker image and Postgres. Our hosted instance is
  just one such installation; the app lets you point at any server URL.
- **Wear OS app** to start a session and enter sets from the wrist while the
  phone stays in the locker. The watch syncs with the phone using the same
  rules the phone uses with the server.
- **AI in the app** as a second option for people without an assistant:
  bring your own key (OpenAI-compatible or Anthropic, including a local
  Ollama), an operator key on self-hosted servers, or a small free monthly
  budget on our instance. Always on request, never in the background, and the
  app shows what it sends before it sends it.
- **Open exercise catalogue.** The curated core is open data under CC0 in
  [`catalog/core.json`](catalog/core.json); the app's seed list and strings
  are generated from it. A broader English catalogue is imported from
  [free-exercise-db](https://github.com/yuhonas/free-exercise-db) (Unlicense)
  into `catalog/extended.json` and reachable through search in the picker.
  See [`catalog/README.md`](catalog/README.md).

## Building

Requirements: JDK 21 and an Android SDK with platform 35. Nothing else; the
Gradle wrapper fetches the rest.

```sh
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties   # once, gitignored
./gradlew assembleDebug test
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`. The domain
logic (volume, best set, records, pre-fill, weight parsing, search) lives in
the Android-free `core/` module, and `test` runs its plain JVM tests together
with the app's; reports land under `core/build/reports/tests/test/` and
`app/build/reports/tests/`. CI runs the same command on every pull request.

If Gradle cannot reach Maven on a machine that prefers IPv6, add
`-Djava.net.preferIPv4Stack=true` to `org.gradle.jvmargs` in
`gradle.properties`; the repo already does this.

The Archivo typeface in `app/src/main/res/font/` is under the SIL Open Font
License; the licence text ships in the APK as `assets/licenses/archivo-OFL.txt`.

## Licence

The code is free software under **GNU AGPL-3.0-or-later**, see
[`LICENSE`](LICENSE). Anyone who runs a modified version as a service must
offer its source to the users. The exercise catalogue (names, muscle groups,
equipment) is open data under CC0 and may be used without conditions. Your
training data is yours.
