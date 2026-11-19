#!/usr/bin/env bash
# Installs an APK on the connected device or emulator, starts it and checks that
# it stays alive: "the build ships but the app is dead". Flow tests run
# unminified under Robolectric and see none of R8's or the manifest's problems.
#
#   scripts/smoke-launch.sh app/build/outputs/apk/dogfood/app-dogfood.apk
#   scripts/smoke-launch.sh APK [PACKAGE] [ACTIVITY]
#
# Needs adb. CI runs it on an emulator; on a phone it replaces the installed
# build (`adb install -r`), so use it on a test device.
set -euo pipefail

APK="${1:?usage: smoke-launch.sh APK [PACKAGE] [ACTIVITY]}"
PKG="${2:-io.github.kanylbullen.tung}"
ACT="${3:-.MainActivity}"

adb install -r -d "$APK"
adb logcat -c
adb shell am force-stop "$PKG"
adb shell am start -W -n "$PKG/$ACT"
sleep 12

fail() { echo "FAIL: $1"; adb logcat -d -t 400 | grep -E "AndroidRuntime|FATAL|$PKG" | tail -80; exit 1; }

adb shell pidof "$PKG" >/dev/null || fail "the app is not running 12 s after launch"
adb logcat -d | grep -E "FATAL EXCEPTION|ANR in $PKG" >/dev/null && fail "a crash or ANR is in the log"
adb shell dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity" | grep -q "$PKG" \
    || fail "the app's activity is not in front"
echo "ok: $PKG started and stayed up"
