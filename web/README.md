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

## Behind a CDN

`index.html` asks for `tung.js?v=<build>` and `styles.css?v=<build>` (the build's id, as in the
service worker's cache name), so a CDN that keeps static files for hours, such as the instance's
Cloudflare, never pairs a new page with an old bundle. The landing pages do the same with a hash of
`site.css` and `site.js`. What a CDN can still hold is `sw.js` itself: the server sends it with
`Cache-Control: no-cache`, so set the CDN to respect the origin's headers for `/app/sw.js` (on
Cloudflare: Browser Cache TTL "Respect Existing Headers", or a cache rule that bypasses it), or a
new build reaches installed apps only when the CDN's copy expires.

## Sign in with Google

When the app is served by the tung-server it signs in to (`https://tung.xuper.fun/app/`, or a
self-hosted instance's `/app/`), the sign-in screen shows Google's own button above the email
form. The client id comes from the server (`GET /api/v1/auth/methods`), Google Identity Services
runs as a popup, and its ID token goes to `POST /api/v1/auth/google`, which answers a session as a
password sign-in does (the phone's flow, `server/README.md`, "Sign in with Google"). Elsewhere
(GitHub Pages, a dev server) there is no button: Google only runs it on origins the client id
authorises, and that is the server's own, which the consent page already uses.

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

## The typeface

`src/jsMain/resources/fonts/archivo_latin.woff2` is the app's
`app/src/main/res/font/archivo_variable.ttf` cut to Latin (Basic, Latin-1,
Latin Extended-A, general punctuation, €, ™, −, ×) with both axes, weight
and width, kept: about 116 kB instead of 659 kB. The landing page uses the
same file. After a change to the app's font, make it again with fontTools
(`pip install fonttools brotli`):

```sh
pyftsubset app/src/main/res/font/archivo_variable.ttf \
  --unicodes="U+0000-00FF,U+0100-017F,U+2010-2027,U+2030-203A,U+20AC,U+2122,U+2212,U+00D7" \
  --layout-features='*' --flavor=woff2 \
  --output-file=web/src/jsMain/resources/fonts/archivo_latin.woff2
```
