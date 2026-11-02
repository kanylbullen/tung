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

`Tung.xcodeproj` and `Tung/Info.plist` are generated from `project.yml` and
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
(database, settings, Keychain) come next. Running on a phone needs signing
with an Apple ID.
