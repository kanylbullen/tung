#!/usr/bin/env bash
# Puts Robolectric's android-all jar in ~/.m2 before the tests run, with
# retries and a checksum. Robolectric downloads it itself on first use
# (MavenArtifactFetcher) with no retry, and one Maven Central hiccup then fails
# every flow test (CI, 2026-11-18, PR #253). A jar already in ~/.m2 is used as it is.
#
# CI restores ~/.m2/repository/org/robolectric from a cache first, so this step
# normally finds the file and downloads nothing.
#
# The artifact follows the Robolectric version and the SDK in
# robolectric.properties (4.14.1 and sdk=34): change it with them.
set -euo pipefail

VERSION="${ROBOLECTRIC_ANDROID_ALL:-14-robolectric-10818077-i7}"
ART="android-all-instrumented"
BASE="https://repo1.maven.org/maven2/org/robolectric/$ART/$VERSION"
DIR="$HOME/.m2/repository/org/robolectric/$ART/$VERSION"
mkdir -p "$DIR"

fetch() { # file
    curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors --max-time 300 -o "$DIR/$1.part" "$BASE/$1"
    mv "$DIR/$1.part" "$DIR/$1"
}
valid() { [[ -s "$DIR/$ART-$VERSION.jar" && -s "$DIR/$ART-$VERSION.jar.sha512" ]] &&
    [[ "$(sha512sum "$DIR/$ART-$VERSION.jar" | cut -d' ' -f1)" == "$(cut -d' ' -f1 "$DIR/$ART-$VERSION.jar.sha512")" ]]; }

if valid; then echo "Robolectric $VERSION already in ~/.m2"; exit 0; fi
for f in "$ART-$VERSION.pom" "$ART-$VERSION.pom.sha512" "$ART-$VERSION.jar.sha512" "$ART-$VERSION.jar"; do fetch "$f"; done
valid || { echo "Checksum mismatch for $ART-$VERSION.jar" >&2; exit 1; }
echo "Fetched Robolectric $VERSION"
