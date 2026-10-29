#!/usr/bin/env bash
# The Play build: a signed app bundle (AAB) and a signed APK of the phone app and of
# the watch app, from one commit on main, all four signed with the release key.
# The AABs are what Play takes; the APKs are the sideloaded reserve. How the pieces
# fit, and the Play Console side: deploy/play.md.
#
#   TUNG_KEYSTORE=~/.config/tung/release.jks TUNG_KEYSTORE_PASSWORD=… \
#   TUNG_KEY_ALIAS=tung TUNG_KEY_PASSWORD=… scripts/release.sh
#
# It refuses rather than builds:
# - without the key. `assembleDogfood` makes a release-code APK signed with the debug
#   key, which looks the same and can neither be uploaded nor update a Play install.
# - when HEAD is not origin/main, or the tree has uncommitted changes. The build number
#   is the number of commits on main (linear history, never force-pushed), so it grows
#   with every merge and never backwards; a branch counts its own commits and could
#   pass the next release's number, and an older commit would come out below what is
#   installed. TUNG_VERSION_CODE sets it by hand, for whoever knows why.
# The passwords travel in the environment and never on a command line, where `ps` reads them.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"

: "${JAVA_HOME:=/home/xup/android/jdk}"
: "${ANDROID_HOME:=/home/xup/android/sdk}"
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

fail() { echo "release: $*" >&2; exit 1; }

# --- Inputs, all at once, so one run shows everything that is missing.
missing=()
for v in TUNG_KEYSTORE TUNG_KEYSTORE_PASSWORD TUNG_KEY_ALIAS TUNG_KEY_PASSWORD; do
  [ -n "${!v:-}" ] || missing+=("$v")
done
if [ ${#missing[@]} -gt 0 ]; then
  {
    echo "release: refusing to build, missing in the environment:"
    printf '  - %s\n' "${missing[@]}"
    echo
    echo "A release without the key cannot be uploaded or update an install. See deploy/play.md."
  } >&2
  exit 1
fi
# Absolute, and exported so: a relative path means one thing here (where the script was
# started) and another to Gradle (rootProject.file), so the check could pass a file the
# build then does not find.
KS="$(realpath -e -- "$TUNG_KEYSTORE" 2>/dev/null)" && [ -f "$KS" ] \
  || fail "TUNG_KEYSTORE is not a file: $TUNG_KEYSTORE"
export TUNG_KEYSTORE="$KS"

# --- The build number
if [ -n "${TUNG_VERSION_CODE:-}" ]; then
  [[ "$TUNG_VERSION_CODE" =~ ^[1-9][0-9]*$ ]] \
    || fail "TUNG_VERSION_CODE is not a positive integer: $TUNG_VERSION_CODE"
  echo "build number $TUNG_VERSION_CODE, set by hand (TUNG_VERSION_CODE)"
else
  [ "$(git -C "$REPO" rev-parse --is-shallow-repository)" = "false" ] \
    || fail "the clone is shallow, so the commit count is wrong. Run git fetch --unshallow."
  # The explicit refspec updates origin/main whatever the clone's fetch refspec says; a
  # bare `fetch origin main` only does so through Git's opportunistic update.
  git -C "$REPO" fetch -q origin +refs/heads/main:refs/remotes/origin/main \
    || fail "could not fetch origin/main to derive the build number"
  [ "$(git -C "$REPO" rev-parse HEAD)" = "$(git -C "$REPO" rev-parse origin/main)" ] \
    || fail "HEAD is not origin/main. A release is built from the tip of main: a branch can get a higher build number than the next real release, and an older commit a lower one than what is installed. Set TUNG_VERSION_CODE if you know why."
  [ -z "$(git -C "$REPO" status --porcelain --untracked-files=no)" ] \
    || fail "the working tree has uncommitted changes, and the build number would describe a commit that is not what is built."
  TUNG_VERSION_CODE="$(git -C "$REPO" rev-list --count HEAD)"
  echo "build number $TUNG_VERSION_CODE, from $(git -C "$REPO" rev-parse --short HEAD) on main"
fi
export TUNG_VERSION_CODE
echo "versionCode $((TUNG_VERSION_CODE * 10 + 1)) (phone), $((TUNG_VERSION_CODE * 10 + 2)) (watch)"

# --- Both apps, APK and AAB, in one Gradle call, so they cannot describe different builds.
cd "$REPO"
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease :wear:assembleRelease :wear:bundleRelease

# --- Check what was built, not what was asked for: each file is signed by *this* key.
# The key's fingerprint, from the keystore (the password through the environment, never
# on the command line), as lower-case hex without colons, which is how apksigner prints it.
fingerprint() { tr -d ':' | tr 'A-F' 'a-f'; }
KEY_SHA1="$("$JAVA_HOME/bin/keytool" -list -v -keystore "$TUNG_KEYSTORE" -alias "$TUNG_KEY_ALIAS" \
  -storepass:env TUNG_KEYSTORE_PASSWORD 2>/dev/null | sed -n 's/^[[:space:]]*SHA1: //p' | head -n1 | fingerprint)"
[ -n "$KEY_SHA1" ] || fail "could not read the certificate of alias $TUNG_KEY_ALIAS from $TUNG_KEYSTORE"
echo "release key SHA-1 $KEY_SHA1"

BT="$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -n1)"
for module in app wear; do
  APK="$REPO/$module/build/outputs/apk/release/$module-release.apk"
  AAB="$REPO/$module/build/outputs/bundle/release/$module-release.aab"
  [ -f "$APK" ] || fail "Gradle finished but there is no APK at $APK"
  [ -f "$AAB" ] || fail "Gradle finished but there is no bundle at $AAB"
  echo
  echo "APK: $APK"
  "$BT/aapt2" dump badging "$APK" | sed -n 1p   # sed reads it all; head would give SIGPIPE
  apk_sha1="$("$BT/apksigner" verify --print-certs "$APK" | sed -n 's/^Signer #1 certificate SHA-1 digest: //p')"
  [ "$apk_sha1" = "$KEY_SHA1" ] \
    || fail "the APK is signed by $apk_sha1, not the release key $KEY_SHA1"
  echo "The APK is signed by the release key."
  echo "AAB: $AAB"
  # A bundle is signed like a jar, so apksigner does not apply; keytool prints the
  # signer's certificate (nothing when the file is unsigned), so the fingerprint decides.
  aab_sha1="$("$JAVA_HOME/bin/keytool" -printcert -jarfile "$AAB" 2>/dev/null \
    | sed -n 's/^[[:space:]]*SHA1: //p' | head -n1 | fingerprint)"
  [ "$aab_sha1" = "$KEY_SHA1" ] \
    || fail "the bundle is signed by '${aab_sha1:-nobody}', not the release key $KEY_SHA1"
  echo "The bundle is signed by the release key."
done
