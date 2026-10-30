# Tung for the web

The web app: Kotlin/JS in the browser, installable as a PWA, working
without a network. It runs the same `core` as the phone, the watch and
the server (the command rules, the sync protocol, progress, the catalogue),
keeps the log in the browser (in memory, persisted to IndexedDB) and syncs
with a tung-server over `POST /api/v1/sync` like the phone does. The UI is
Compose HTML: DOM elements as composables, the Compose runtime the phone's
screens use, no React.

What it does today: sign in with email and password, pull the account's
log, show History. The rest follows in the frame below; the design is
`CLAUDE.md`, "Web".

## Build

```sh
cd /home/xup/tung
export JAVA_HOME=/home/xup/android/jdk PATH=/home/xup/android/jdk/bin:$PATH
./gradlew :web:jsBrowserDistribution --no-daemon
```

The site lands in `web/build/dist/js/productionExecutable/`: `index.html`,
`tung.js`, `styles.css`, `sw.js`, `manifest.webmanifest`, the icons, the
typeface and `catalog/core.json`. Static files, any host serves them; the
service worker and the manifest need https (or `localhost`). The first build
downloads Node.js and Yarn into `~/.gradle` (their repositories are declared
in `settings.gradle.kts`, see the root build file) and the npm packages into
`build/js`; `kotlin-js-store/yarn.lock` at the root pins them.

A dev server with reload on change:

```sh
./gradlew :web:jsBrowserDevelopmentRun --continuous --no-daemon
```

Tests run in headless Chrome through Karma (`CHROME_BIN` if it is not on
the path):

```sh
./gradlew :web:jsBrowserTest --no-daemon
```

## Where it runs

`main` is published to **https://kanylbullen.github.io/tung/** by
`.github/workflows/web-pages.yml` (on a push that touches the web, core or
the catalogue, or by hand from the Actions tab). Open it on a phone and
"Add to Home Screen" / "Install app"; it signs in to `https://tung.xuper.fun`
by default. The server must be a version that allows `X-Tung-Device` in CORS,
or sign-in works and every sync says the server cannot be reached.

## Try it against a server

The sign-in form offers `https://tung.xuper.fun` (`DEFAULT_SERVER_URL` in
`WebContainer.kt`); "Use another server" takes any https address, or http
to localhost or a private IP. The server's CORS allows any origin with a
bearer token, so the site can be served from anywhere. To serve the built
site locally:

```sh
python3 -m http.server 8000 -d web/build/dist/js/productionExecutable
```

then open `http://localhost:8000/`. Install it from the browser's menu
("Install app" / "Add to Home Screen"); once it has loaded, it opens offline.

## Layout

| Path (`web/src/jsMain/kotlin/io/github/kanylbullen/tung/web/`) | What |
|---|---|
| `Main.kt`, `WebContainer.kt` | Entry, the service worker's registration, and the container (store, IndexedDB, settings, auth, sync) |
| `store/` | `TungStore` (the log in memory, sync's merge, the catalogue's seed), `Tables.kt` (the eight tables as wire rows), `IndexedDb.kt` (the browser's copy) |
| `sync/` | `WebSync` (the sync loop), `WebAuth` (sign in and out), `WebSettings` (server, account, token, cursor), `Http.kt` (`fetch`) |
| `history/` | History's rows, pure |
| `ui/` | The screens (Compose HTML) and the browser's formatters |
| `i18n/` | The strings, English and Swedish |
| `../resources/` | `index.html`, `styles.css`, `sw.js`, `manifest.webmanifest`, `icons/` |
