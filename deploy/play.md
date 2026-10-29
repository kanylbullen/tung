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
(API 36); Wear OS apps Android 15 (API 35). The phone app targets 35 today,
so the console refuses its bundle until compileSdk and targetSdk are 36,
which needs AGP 8.9.1 or later (CLAUDE.md, "Building"). The watch app's 35 is
fine until next year's step.

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
   - **Privacy policy:** a URL is required. One page: training data stays on
     the phone unless sync is turned on, then it goes to the server the user
     chose; email and password for the account, or Google sign-in; no
     analytics, no ads, no third-party SDKs that collect anything. Host it on
     `tung.xuper.fun` or GitHub Pages.
   - **Data safety:** what the policy says, in the form's terms. Personal
     info (email) and fitness info (workouts, and heart rate and calories
     when Health Connect is on), collected only with an account, transmitted
     with encryption, deletable (sign out keeps local data; the server has
     the account's deletion).
   - **Health Connect:** the `android.permission.health.*` permissions need
     the Health Connect declaration form, which Google reviews. Fill it in
     with the two opt-ins (write sessions, read heart rate and calories) and
     the rationale page the app already shows (`HealthPermissionsActivity`).
   - **Permissions:** `RECORD_AUDIO` is on-device speech for logging sets,
     nothing recorded or sent; `SCHEDULE_EXACT_ALARM` is the rest timer
     (`USE_EXACT_ALARM`, which Play reserves for alarm apps, is not used);
     `POST_NOTIFICATIONS` is the rest alert and the ongoing workout.
3. **Store listing:** screenshots from a real phone (the flow tests' PNGs are
   411 dp Robolectric renders, not store material), the icon, a short and a
   full description in both languages.

## The watch

Later. The Wear OS track takes `wear-release.aab` under the same app, with
its own screenshots and a Wear OS review. Not before issue #84: the round
screen layouts are the first thing a reviewer sees.
