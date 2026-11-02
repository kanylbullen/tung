# Tung for iPhone

The Xcode app around the shared Compose screens in `ui/`. It is a thin SwiftUI
shell: everything drawn comes from the `TungUI` framework, which Gradle
builds from the `ui` module.

## Build

On a Mac with Xcode and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`), and a JDK 17 or newer for Gradle:

```sh
cd iosApp
xcodegen
open Tung.xcodeproj
```

`Tung.xcodeproj`, `Tung/Info.plist` and `Tung/Tung.entitlements` are generated from `project.yml` and
are not committed; edit `project.yml` and run `xcodegen` again. The target's
pre-build script runs `./gradlew :ui:embedAndSignAppleFrameworkForXcode`,
which builds the framework for the configuration and SDK Xcode is building.

From the command line, for the simulator and without signing (what CI's
`ios` job does):

```sh
./gradlew :ui:linkDebugFrameworkIosSimulatorArm64 --no-daemon
cd iosApp && xcodegen
xcodebuild -project Tung.xcodeproj -scheme Tung -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

## What runs

The whole app: `ui`'s `TungNavRoot` over `IosContainer` (the database and the
settings in Application Support, the session token and the assistant's key in
the Keychain, the rest timer over local notifications, sync while the app is
in front, the catalogue from the app's resources) and `IosPlatform`.

Not yet on the iPhone: speech (the mic says it is unavailable), Google sign-in,
feedback screenshots, push (APNs), background sync, HealthKit.

The app needs iOS 17.2 or newer (Compose Multiplatform's ICU data is built for
it) and carries the Sign in with Apple entitlement.

## On a phone

Signing is automatic with the team in `project.yml` (`55BB3CFVQ3`). Once, on
the Mac: add the team's Apple ID under Xcode → Settings → Accounts, and turn
on Developer Mode on the iPhone (Settings → Privacy & Security). Then pick the
phone in Xcode and run, or from the command line:

```sh
xcodebuild -project Tung.xcodeproj -scheme Tung -sdk iphoneos \
  -destination 'generic/platform=iOS' -allowProvisioningUpdates build
```

`-allowProvisioningUpdates` lets xcodebuild register the device and make the
profile, as Xcode does when you run from it.

## TestFlight

`scripts/testflight.sh` archives a Release build and uploads it to App Store
Connect, where it shows up in TestFlight after processing. It signs in with an
App Store Connect API key, not an Apple ID: with automatic signing the key
lets xcodebuild make the distribution certificate and profile in the cloud, so
it runs over ssh with no keychain prompt and no phone attached. The archive
is built unsigned; the export signs it. That is also
the way onto a managed (MDM) iPhone that refuses to pair with a Mac.

Once: the app record for `io.github.kanylbullen.tung` in App Store Connect,
and a Team key with the Admin role (cloud signing refuses App Manager) under Users and Access → Integrations → App
Store Connect API. The `.p8` downloads only once; keep it on the Mac in
`~/.appstoreconnect/private_keys/`, never in the repository. Then, from the
repository root on the Mac:

```sh
ASC_KEY_ID=<key id> ASC_ISSUER_ID=<issuer id> \
  ASC_KEY_PATH=~/.appstoreconnect/private_keys/AuthKey_<key id>.p8 \
  iosApp/scripts/testflight.sh
```

The build number is the commit count of HEAD (the Play build's rule), so an
upload from a newer `main` is always higher; `BUILD_NUMBER` overrides it. The
version is `MARKETING_VERSION` in `project.yml`. `ITSAppUsesNonExemptEncryption`
is false in the Info.plist (the app uses only the system's HTTPS), so no build
waits on the export compliance question.
