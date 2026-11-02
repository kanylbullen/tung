#!/usr/bin/env bash
# Archives the iPhone app and uploads it to App Store Connect for TestFlight.
# Run on the Mac from the repository root:
#
#   ASC_KEY_ID=… ASC_ISSUER_ID=… ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_….p8 \
#     iosApp/scripts/testflight.sh
#
# The archive is built unsigned and the export signs it: with automatic
# signing and an App Store Connect API key, xcodebuild uses a cloud-managed
# distribution certificate and makes the App Store profile, then uploads, with
# no Apple ID, keychain or registered device, so it works over ssh. Signing
# the archive itself would want a development certificate in the keychain
# and a registered device. An unsigned app carries no entitlements, and the
# export keeps only those the app has, so the archived app is signed ad hoc
# with Tung.entitlements first (Sign in with Apple would be lost otherwise).
# Cloud signing needs a key with the Admin role (App Manager gets "Cloud
# signing permission error"). The key file stays on
# the Mac and is never committed or printed.
#
# The build number is the number of commits on HEAD, as the Play build's is
# (scripts/release.sh), so every upload from a newer main is higher; set
# BUILD_NUMBER to override it.
set -euo pipefail

: "${ASC_KEY_ID:?the App Store Connect API key id}"
: "${ASC_ISSUER_ID:?the issuer id of the API key}"
: "${ASC_KEY_PATH:?the path to the AuthKey_<id>.p8 file}"
[ -f "$ASC_KEY_PATH" ] || { echo "No key file at $ASC_KEY_PATH" >&2; exit 1; }

root="$(cd "$(dirname "$0")/../.." && pwd)"
build_number="${BUILD_NUMBER:-$(git -C "$root" rev-list --count HEAD)}"
out="$root/iosApp/build/testflight"
rm -rf "$out"
mkdir -p "$out"

auth=(
  -allowProvisioningUpdates
  -authenticationKeyPath "$ASC_KEY_PATH"
  -authenticationKeyID "$ASC_KEY_ID"
  -authenticationKeyIssuerID "$ASC_ISSUER_ID"
)

cd "$root/iosApp"
xcodegen --quiet

echo "Archiving build $build_number"
xcodebuild archive \
  -project Tung.xcodeproj -scheme Tung -configuration Release \
  -destination 'generic/platform=iOS' \
  -archivePath "$out/Tung.xcarchive" \
  CODE_SIGNING_ALLOWED=NO \
  CURRENT_PROJECT_VERSION="$build_number"

app="$out/Tung.xcarchive/Products/Applications/Tung.app"
codesign --force --sign - --timestamp=none --generate-entitlement-der \
  --entitlements Tung/Tung.entitlements "$app"

cat > "$out/ExportOptions.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>app-store-connect</string>
  <key>destination</key><string>upload</string>
  <key>signingStyle</key><string>automatic</string>
  <key>teamID</key><string>55BB3CFVQ3</string>
  <key>manageAppVersionAndBuildNumber</key><false/>
</dict>
</plist>
PLIST

echo "Uploading build $build_number to App Store Connect"
xcodebuild -exportArchive \
  -archivePath "$out/Tung.xcarchive" \
  -exportOptionsPlist "$out/ExportOptions.plist" \
  -exportPath "$out/export" \
  "${auth[@]}"

echo "Uploaded build $build_number. It shows in TestFlight once App Store Connect has processed it."
