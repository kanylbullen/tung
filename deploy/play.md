# Google Play: the test release

How a build of the phone app (and later the watch app) gets to Google Play's
internal testing track, and what the Play Console asks for on the way. The
server side of our instance is `coolify.md`; this file is the app's.

## The key

One release key signs both apps. It lives outside the checkout, by convention
`~/.config/tung/release.jks`; `*.jks` and `*.keystore` are gitignored, and
the key is never in CI. Made once:

```sh
keytool -genkeypair -v -keystore ~/.config/tung/release.jks -alias tung \
  -keyalg RSA -keysize 4096 -validity 36500
```

Back it up where the backups of `coolify.md` go. The watch app **must** be
signed with the same key as the phone app: the Wearable Data Layer only pairs
apps with the same signing key.

**Play App Signing, decided at the first upload and hard to change later.**
Two choices:

- **Use this key as the app signing key** (export it with Play's PEPK tool
  when the console asks). Then what Play installs is signed exactly like what
  `scripts/release.sh` and a dogfood build with the key produce, so a
  sideloaded build updates a Play install and the other way round, and
  Google needs one SHA-1 for Sign in with Google. This is what ScreenTimeAgent
  did, and the recommendation.
- **Let Google generate the app signing key.** Play re-signs every upload;
  this key is then only the upload key, which Play can reset if it is lost.
  The price: a dogfood build can never install over a Play install, and
  Sign in with Google needs the app signing key's SHA-1 from the console as
  well.

## Sign in with Google

The Android OAuth client in Google Cloud (package
`io.github.kanylbullen.tung`) must carry the SHA-1 of **the key that signs
the installed app**, or Credential Manager answers with no credential and the
button fails only on the phone that got the app from Play. Add the release
key's fingerprint next to the debug key's:

```sh
keytool -list -v -keystore ~/.config/tung/release.jks -alias tung | grep SHA1
```

With a Google-generated app signing key, add the SHA-1 the console shows
under Setup → App signing too.

## targetSdk

Since 2026-08-31 Play requires new apps and updates to target Android 16
(API 36); Wear OS apps Android 15 (API 35). Both are met (PR #95): the phone
app targets 36 (AGP 8.9.1, compileSdk 36) and the watch app 35, where it
stays on purpose until Play raises the Wear OS bar (Android 16's behaviour
changes are not wanted on the watch yet; `wear/build.gradle.kts`). CI reads
both numbers back from the built APKs with `aapt2 dump badging`.

What Android 16 changes for an app targeting 36 (its behaviour-changes page,
and the page for all apps), checked against the code when it moved:

| Change | Tung |
|---|---|
| Edge-to-edge enforced; the opt-out attribute disabled | Already on: `enableEdgeToEdge()` in both activities, the root `Scaffold` has no content insets, and every screen pads for the bars itself (Material 3 top and navigation bars, `statusBarsPadding` on Home, `navigationBarsPadding` on the sticky Log set bar and the sheets). The attribute was never set. Live on Android 15 phones since targetSdk 35. |
| Predictive back on by default; `onBackPressed` and `KEYCODE_BACK` no longer delivered | Nothing to do: back is Compose Navigation and one `BackHandler`, both on `OnBackPressedDispatcher`; no `onBackPressed` override, no `KEYCODE_BACK`, `enableOnBackInvokedCallback` not set. |
| Orientation, resizability and aspect-ratio restrictions ignored on large screens | None declared, no `setRequestedOrientation`. |
| `BODY_SENSORS` replaced by `android.permission.health.*` | Not used; heart rate comes through Health Connect's `READ_HEART_RATE`, and the privacy-policy activity it requires exists (`HealthPermissionsActivity`). |
| Elegant font APIs disabled; `scheduleAtFixedRate` missed runs coalesced | Neither used. |
| Intent redirection hardening (all apps) | No nested Intents in extras: the notification actions are immutable `PendingIntent`s and the receivers read string extras only. |
| Safer Intents, local network permission, photo picker, Bluetooth, MediaStore | Opt-in or not applicable. The local network permission, once a later release enforces it, will touch sync to a private-IP server. |
| 16 KB page size (a Play rule) | No native code of our own. The two AndroidX libraries that ship one (`graphics-path`, `datastore-core`) pass `build-tools/36.0.0/zipalign -c -P 16 -v 4 app/build/outputs/apk/release/app-release.apk`, and their load segments are 16 KB (`llvm-readelf -lW`). |

The flow tests run under Robolectric SDK 34 and see none of this; insets and
back are checked on a phone.

## The build

```sh
TUNG_KEYSTORE=~/.config/tung/release.jks TUNG_KEYSTORE_PASSWORD=… \
TUNG_KEY_ALIAS=tung TUNG_KEY_PASSWORD=… scripts/release.sh
```

From the tip of `main`, with a clean tree. It builds and checks four files:

| Upload | File |
|---|---|
| Phone, Play | `app/build/outputs/bundle/release/app-release.aab` |
| Phone, sideload | `app/build/outputs/apk/release/app-release.apk` |
| Watch, Play (Wear OS track) | `wear/build/outputs/bundle/release/wear-release.aab` |
| Watch, sideload | `wear/build/outputs/apk/release/wear-release.apk` |

**versionCode.** The build number is the number of commits on `main`
(`git rev-list --count`), which grows with every merge and never backwards.
The phone's versionCode is `number × 10 + 1` and the watch's `number × 10 + 2`,
because both apps share one applicationId and Play wants every artefact of an
app to have its own code. `versionName` is `0.1.0.<number>`, so App info
tells two test builds apart. A build without a build number (debug, dogfood)
is number 1: versionCode 11 and 12, versionName `0.1.0`.
`TUNG_VERSION_CODE=…` sets the number by hand, for a build that is not the
tip of `main`.

**The gate.** `assembleRelease` and `bundleRelease` run the root build
file's `releaseGate` before packaging, which refuses without the four key
inputs or the build number. The release variant's unit tests are untouched,
so CI, which has no key, is too.

**Upload with the Play Developer API.** `scripts/release.sh --upload production
--notes <dir>` builds, checks, and then sends both bundles to Play in one edit
(`scripts/play_upload.py`, standard library and `openssl` only): the phone to
the track, the watch to `wear:<track>`, each with the versionCode the build
number gives, checked against what Play reads in the bundle. `<dir>` holds one
`<language>.txt` per language (`en-GB.txt`, `sv-SE.txt`, at most 500
characters each), required for production. Committing the edit sends a
completed release for review, since managed publishing is off; `--draft`
saves it for the console instead, `--replace-in-review` cancels changes
already in review and sends them along (without it the commit refuses), and
`--phone-only` leaves the watch out (there is no `wear:alpha`). Before the
build a dry run checks the key and both tracks, so a bad key costs seconds,
not a build. On its own, `scripts/play_upload.py --track production --dry-run`
signs in, prints the tracks' releases and throws the edit away.

The account is the service account `play-publisher@tung-514709.iam.gserviceaccount.com`
of Google Cloud project `tung` (tung-514709), with the Google Play Android
Developer API enabled and no project roles. In Play Console (Users and
permissions) it has Tung only: view, release to testing tracks, and release to
production. Its JSON key is `~/.config/tung/play-publisher.json`, mode 600
(`TUNG_PLAY_KEY` points elsewhere), outside the checkout and never committed:
whoever holds it can publish Tung to every user. A lost or leaked key is
deleted in the Cloud console (IAM → Service accounts → Keys) and replaced.

**Dogfood after Play.** `:app:assembleDogfood` with the key in the
environment is signed with the release key, so it installs over a Play
install and keeps the data; its versionCode is lower, so `adb install -r -d`.
Without the key it is signed with the debug key, as before.

## The console, first time

1. Create the app (`io.github.kanylbullen.tung`, free, English and Swedish),
   decide Play App Signing (above), upload the phone AAB to **Internal
   testing** and add the testers' emails. Internal testing has no review
   wait; closed and open testing and production do.
2. **App content**, before the first release goes out:
   - **Privacy policy:** a URL is required: `https://tung.xuper.fun/privacy`
     (`server/src/main/resources/site/privacy.html`, which is the source of
     truth; change it with the code). In short: training data stays on the
     phone unless sync is turned on, then it goes to the server the user
     chose; email and password for the account, or Google sign-in; on a tap,
     the assistant sends the user's own provider (OpenAI, Anthropic,
     OpenRouter, a self-hosted model) the last 90 days of finished workouts
     (at most 30, with exercise names, custom ones included), the routines and
     programs with their names, and the equipment list when narrowed, or for
     an import's matching only the file's names and the catalogue; voice goes
     to the phone's recogniser, which may send audio online when it has no
     on-device model for the language; the hosted instance's processors
     (Cloudflare, Hetzner backups, Google, Apple, Resend); no analytics, no
     ads. Firebase Cloud Messaging is active only in a build with a
     `google-services.json`.
   - **Data safety:** what the policy says, in the form's terms. Personal
     info (email) and fitness info (workouts, and heart rate and calories
     when Health Connect is on), collected only with an account, transmitted
     with encryption, deletable (sign out keeps local data; the account is
     deleted in the app, on the web, or by email, see `/delete-account`).
     The assistant's request is sent by the user's tap, with the user's own
     key, to a provider the user chose, never to the developer: declare it as
     the form's guidance for user-initiated transfers says (the owner decides
     how). Audio: the app records nothing and sends nothing itself; the
     system recogniser may send audio to its provider.
   - **Health Connect:** the `android.permission.health.*` permissions need
     the Health Connect declaration form, which Google reviews. Fill it in
     with the two opt-ins (write sessions, read heart rate and calories) and
     the rationale page the app already shows (`HealthPermissionsActivity`).
   - **Permissions:** `RECORD_AUDIO` is speech for logging sets through the
     phone's `SpeechRecognizer`: offline is preferred, and when the phone has
     no on-device model for the language the recogniser may send the audio to
     its provider (usually Google) to be transcribed; the app keeps no audio
     and sends none itself; `SCHEDULE_EXACT_ALARM` is the rest timer
     (`USE_EXACT_ALARM`, which Play reserves for alarm apps, is not used);
     `POST_NOTIFICATIONS` is the rest alert and the ongoing workout;
     `POST_PROMOTED_NOTIFICATIONS` (Android 16, granted by default) makes the
     ongoing workout a Live Update.
3. **Store listing:** screenshots from a real phone (the flow tests' PNGs are
   411 dp Robolectric renders, not store material), the icon, a short and a
   full description in both languages.

## The watch

Later. The Wear OS track takes `wear-release.aab` under the same app, with
its own screenshots and a Wear OS review. Not before issue #84: the round
screen layouts are the first thing a reviewer sees.
