# Tung for Wear OS (v0)

The watch app mirrors the workout in progress on the phone and sends what you
do on the wrist back to it: log a set, change weight or reps with the bezel or
crown, skip or extend the rest, finish, or start a workout or a routine. The
workout lives in the phone's database; the watch keeps no log of its own yet.

Built for Wear OS 3 and later (API 30+, Galaxy Watch 4 and newer), round
screens.

## Pairing

The Wearable Data Layer only connects apps that have the **same application
id and the same signing key**. Both `app/` and `wear/` use
`applicationId = "io.github.kanylbullen.tung"` and, for debug builds, the
default debug keystore (`~/.android/debug.keystore`). So install a phone APK
and a watch APK built on the same machine (or taken from the same CI run);
two debug builds from different machines will not see each other.

The phone app is required (`com.google.android.wearable.standalone = false`).
The watch must be paired with the phone in the usual way (Galaxy Wearable or
the Wear OS app).

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

Open Tung on the phone once after installing, so it publishes its state.

## Protocol

Shared classes in `core/src/main/kotlin/io/github/kanylbullen/tung/core/wear/WatchProtocol.kt`,
JSON with kotlinx-serialization (`type` tags the command; unknown fields are ignored).

| Direction | Transport | Path | Payload |
|---|---|---|---|
| Phone → watch | DataItem (synced and kept by the Data Layer) | `/tung/active_workout`, key `json` | `WatchWorkoutState`: `workoutId` (null = no workout), `startedAt`, `exercises` (`workoutExerciseId`, localised `name`, `sets` with `setId`, `position`, `weightKg`, `reps`, `completed`, and `lastTime`), `currentSetId`, `rest` (`endsAtMillis`, `durationMillis`) or null, `routines` (`id`, `name`, `exerciseCount`), `updatedAt`, `version` |
| Watch → phone | Message (not queued) | `/tung/cmd` | One `WatchCommand`: `log_set(setId)`, `set_weight(setId, kg)`, `set_reps(setId, reps)`, `select_set(setId)`, `start_workout`, `start_routine(routineId)`, `finish_workout`, `skip_rest`, `add_rest_30`; each with `commandId` (UUID) and `sentAt` |

The phone applies a command through its repositories (`WearSyncService` →
`WearCommandHandler`) and publishes the new state (`WearStatePublisher`). The
watch shows its change at once and replaces that guess with the phone's state
when it arrives. A message that cannot be delivered shows "Phone not
reachable"; the local change stays until the phone's next state.

## Not in v0

- **Logging without the phone.** The watch cannot log while the phone is out
  of reach. The next step is a small log on the watch with its own UUIDs and
  `updatedAt`, merged by the phone with the same rules as server sync (see
  `docs/architecture.md`).
- Adding exercises from the watch: "Start workout" starts an empty workout;
  exercises are added on the phone, or start a routine instead.
- An ongoing activity, a tile, heart rate.
- The rest buzzes on the watch only while the watch app is on screen; the
  phone buzzes as well.
