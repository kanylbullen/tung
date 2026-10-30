# Tung for Wear OS

The watch app logs a workout from the wrist: log a set, change weight or reps
with the bezel or crown, skip or extend the rest, finish, or start a workout
or a routine. It works in one of two modes, chosen automatically:

- **Standalone**, once the watch is linked to a tung-server. The watch keeps
  its own log in its own database, runs every command itself through the same
  command handler as the phone (so routine targets, one workout in progress and
  finishing behave exactly as on the phone), runs the rest timer itself, and
  syncs with the server over its own Wi-Fi (or whatever network it has): every
  15 minutes, after a finished workout and when the app is opened. "Last time"
  comes from the watch's database, which after a sync holds the phone's
  history too. No phone needed after the one-time setup.
- **Companion**, when the watch is not linked. It mirrors the workout in
  progress on the paired phone over the Wearable Data Layer and sends your
  taps to the phone, as before: the workout lives in the phone's database.

If the watch is linked **and** a phone is paired, standalone wins and the
phone's Data Layer state is ignored: the watch and the phone meet through the
server, so log a workout on one of them at a time and let sync carry it.

Built for Wear OS 3 and later (API 30+, Galaxy Watch 4 and newer), round
screens.

## Linking the watch to a server

Typing a password on a watch is no good, so the watch signs in with a code:

1. On the phone, sign in to your server (Settings, Sync), then tap **Link a
   watch**. The phone shows a six-digit code, valid for ten minutes.
2. On the watch, open Tung, scroll to **Settings** and tap **Link to**
   followed by your server's name. The watch knows the server because the phone publishes
   it over the Data Layer; that is the normal way.
3. Type the code on the number pad. The sixth digit links the watch; it gets
   its own token (named `watch` under the account's tokens) and switches to
   standalone.

Without a paired phone to learn the server from (a Galaxy Watch set up once
on a stock phone, then used on its own), **Choose server** lists the servers
the watch used before and has a field to type one; a bare host name means
`https://`. The code still comes from any signed-in phone: the phone does not
have to be the watch's paired one.

Settings on a linked watch shows the account, **Sync now** with the last sync,
and **Sign out**, which forgets the token and keeps the watch's data.

## Pairing (companion mode)

The Wearable Data Layer only connects apps that have the **same application
id and the same signing key**. Both `app/` and `wear/` use
`applicationId = "io.github.kanylbullen.tung"` and, for debug builds, the
default debug keystore (`~/.android/debug.keystore`); release builds of both
are signed with the one release key (`deploy/play.md`). So install a phone APK
and a watch APK built on the same machine (or taken from the same CI run), and
of the same kind: two debug builds from different machines, or a debug phone
app and a release watch app, will not see each other.

The phone app is optional (`com.google.android.wearable.standalone = true`):
a linked watch needs no phone. For companion mode the watch must be paired
with the phone in the usual way (Galaxy Wearable or the Wear OS app).

## Installing

Build both:

```sh
./gradlew :app:assembleDebug :wear:assembleDebug --no-daemon
```

Install the phone app on the phone (USB or wireless adb):

```sh
adb -s <phone> install -r app/build/outputs/apk/debug/app-debug.apk
```

Then the watch. First turn on Developer options on the watch (Settings →
About watch → Software → tap the software version seven times) and ADB
debugging.

**Over Wi-Fi** (works on every Wear OS 3+ watch, including Galaxy Watch 4):
watch and computer on the same network, Settings → Developer options →
Wireless debugging → Pair new device, then

```sh
adb pair <watch-ip>:<pairing-port> <code>
adb connect <watch-ip>:<port>
adb -s <watch-ip>:<port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

**Over Bluetooth through the phone** (where the companion app offers
"Debugging over Bluetooth"; Galaxy Wearable does not on every model): turn on
Debug over Bluetooth on the watch and in the companion app, connect the phone
over USB, then

```sh
adb forward tcp:4444 localabstract:/adb-hub
adb connect 127.0.0.1:4444
adb -s 127.0.0.1:4444 install -r wear/build/outputs/apk/debug/wear-debug.apk
```

Open Tung on the phone once after installing, so it publishes its state (and
its server, for linking).

## How it is built

`wear/` depends on `data/`, the multiplatform data layer it shares with the phone:
`TungWearApp` builds a `TungContainer` (its own Room database, repositories,
command handler, sync client and worker). `WatchViewModel` picks a backend by
mode: `LocalWatchBackend` (the watch's database through `WatchStateSource`,
taps through `LocalWatchCommands` → `Command` → the command handler) or
`PhoneLink` (the Data Layer). The screens get the same `WatchWorkoutState`
and send the same `WatchCommand`s either way.

## Protocol (companion mode)

Shared classes in `core/src/commonMain/kotlin/io/github/kanylbullen/tung/core/wear/WatchProtocol.kt`,
JSON with kotlinx-serialization (`type` tags the command; unknown fields are ignored).

| Direction | Transport | Path | Payload |
|---|---|---|---|
| Phone → watch | DataItem (synced and kept by the Data Layer) | `/tung/active_workout`, key `json` | `WatchWorkoutState`: `workoutId` (null = no workout), `startedAt`, `exercises` (`workoutExerciseId`, localised `name`, `sets` with `setId`, `position`, `weightKg`, `reps`, `completed`, and `lastTime`), `currentSetId`, `rest` (`endsAtMillis`, `durationMillis`) or null, `routines` (`id`, `name`, `exerciseCount`), `serverUrl` (the server the phone is signed in to, or null; never a token), `updatedAt`, `version` |
| Watch → phone | Message (not queued) | `/tung/cmd` | One `WatchCommand`: `log_set(setId)`, `set_weight(setId, kg)`, `set_reps(setId, reps)`, `select_set(setId)`, `start_workout`, `start_routine(routineId)`, `finish_workout`, `skip_rest`, `add_rest_30`; each with `commandId` (UUID) and `sentAt` |

The phone applies a command through its repositories (`WearSyncService` →
`WearCommandHandler`) and publishes the new state (`WearStatePublisher`). The
watch shows its change at once and replaces that guess with the phone's state
when it arrives. A message that cannot be delivered shows "Phone not
reachable"; the local change stays until the phone's next state.

## Ongoing Activity

While a workout is in progress the watch shows an **Ongoing Activity**: the
chip on the watch face and in the recents list, with "Bench press · set 3 of
5" (the current set against the routine's target sets, or "4 sets logged"
outside a routine). Tapping it opens the workout screen. It follows whatever
the watch shows, in both modes: the watch's own log when standalone, the
phone's mirrored state in companion mode (while the watch app's process
runs). It has no Log set or Finish buttons; those are on the workout screen.

Underneath it is the phone's "Workout in progress" notification from
`data/` (`OngoingWorkoutNotifier`) with an androidx.wear `OngoingActivity`
attached (`WatchOngoing.kt`, `wear-ongoing` 1.0.0). On Wear OS 4 and later it
needs the notification permission, which the watch asks for once, the first
time a workout is on screen; denied, the workout and the buzz still work, but
there is no chip. Whether a paired phone's ongoing notification also shows
up on the watch is up to Wear OS's bridging, which as far as we know leaves
ongoing notifications on the phone; not yet checked on a device.

## Not yet

- Adding exercises from the watch: "Start workout" starts an empty workout;
  add exercises on the phone (in standalone mode they arrive with the next
  sync), or start a routine instead.
- Merging a companion watch's log through the phone: a phone that cannot
  reach a server still cannot carry a standalone watch's sets.
- A tile, heart rate.
- Push: neither FCM nor UnifiedPush reaches a standalone watch reliably, so
  it keeps the periodic sync and the sync when the app opens.
