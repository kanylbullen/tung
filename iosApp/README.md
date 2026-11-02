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

## Not yet

The app shows the wordmark only: the navigation root and the iOS container
(database, settings, Keychain) come next.

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
