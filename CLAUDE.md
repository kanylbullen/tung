# Tung — agent manual

Strength training log for Android. Local first: everything lives in Room on
the phone and the app works fully without a network or an account. Sync with
an optional tung-server is opt-in, from Settings (section "Sync").

## Language

English is the repository language: code, comments, documentation, commit
messages and pull requests. The app's default locale is English
(`res/values/`), and Swedish (`res/values-sv/`) is the first translation.

Adding an app language:

1. Copy `res/values/strings.xml` to `res/values-<lang>/strings.xml` and
   translate it. Keep exactly the same string names.
2. Add `<locale android:name="<lang>" />` to `res/xml/locales_config.xml` and
   the tag to `resourceConfigurations` in `app/build.gradle.kts`.
3. Only if the catalogue gets that language: add `name.<lang>` to the entries
   in `catalog/core.json`.
4. Run `python3 -I catalog/tools/gen_android.py`. It manages the `ex_*` block
   in every `values-<lang>/strings.xml` that has the catalog markers; without
   `name.<lang>` the block stays empty and the exercise names fall back to
   English.

## Stack (decided)

Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01), Material 3, Room 2.6.1 via
KSP, Navigation Compose 2.8.5, ViewModel + StateFlow, coroutines, Preferences
DataStore 1.1.1 (small state outside Room). AGP 8.7.3,
Gradle 8.11.1 (wrapper). compileSdk/targetSdk 35, minSdk 26. For sync only:
Ktor client 3.0.3 (OkHttp engine, kotlinx JSON), WorkManager 2.10.0,
androidx.security-crypto 1.0.0 (the stable release) and lifecycle-process;
for Sign in with Google, Credential Manager (`androidx.credentials` 1.3.0
with its Play services provider) and `googleid` 1.1.1, in `app/` only.

**No Hilt/Koin.** `TungContainer` (in `data/`, built by `TungApp` and by the
watch's `TungWearApp`) holds the database and the repositories lazily. ViewModels are created with `viewModel(factory = factoryOf { ... })`
(`ui/common/Containers.kt`) and get repositories (for reads), `container.commands`
(for every change, see "Commands") and nav arguments in the constructor;
the active workout's factory also passes `createSavedStateHandle()`, for the rest timer.

## Layers

Five Gradle modules:

| Module | Kind | Depends on | Holds |
|---|---|---|---|
| `core/` | plain Kotlin, JVM | — | Domain rules, commands, sync and watch protocols |
| `data/` | Android library | `core` | Room, repositories, the CommandHandler, sync client, rest timer, `TungContainer` |
| `app/` | Android app (phone) | `core`, `data` | The phone's screens, the phone side of the Data Layer |
| `wear/` | Android app (Wear OS) | `core`, `data` | The watch's screens, standalone and companion modes |
| `server/` | Ktor server | `core` | tung-server |

Sections "Wear" and "Server" cover the last two.

### `core/` — domain logic without Android

`core/src/main/kotlin/io/github/kanylbullen/tung/core/`, package
`io.github.kanylbullen.tung.core`. A plain Kotlin JVM module
(`org.jetbrains.kotlin.jvm`, bytecode 17 like the app), so that a future Ktor
server and a Wear OS app use the same code as the phone. It is **not** Kotlin
Multiplatform; that is a later step if a non-JVM client ever needs it.

What belongs there: pure functions and plain data shapes that are calculated
the same on every client — volume, best set, records, pre-fill (`LoggedSet`,
`isLogged`), routine targets and start sets (`Routines.kt`), the program day suggestion (`Programs.kt`), weight/reps parsing and formatting, diacritic-insensitive search,
`MuscleGroup`/`Equipment`, the finer `Muscle` list and `MuscleIndex` (`Muscles.kt`), the id helpers (`Ids.kt`), the rest timer and the
current set (`RestTimer.kt`, `LogSet.kt`), and the catalogue models
with their JSON parsing (`CoreCatalog`/`CoreExercise` for `core.json`,
`ExtendedCatalog`/`ExtendedExercise` for `extended.json`, including the
extended-catalogue search), the command surface (`core/command/`, see
"Commands"), the picker's suggestions (`core/picker/`, see "Smart picker"), and the sync protocol with its pure rules (`core/sync/`, see
"Sync"). Core gets the catalogue as a `String` or an
`InputStream`; where the bytes come from (APK assets, a file) is the caller's
business. Room entities live in `data/` and map to core's shapes
(`SetEntry.toLogged()`); core never depends on Room.

**The rule: no `android.*` or `androidx.*` imports in `core/`.** Anything that
needs a `Context`, resources or Room goes in `data/`, Compose in the apps; if
a function is mixed, split it and move the pure part. `NoAndroidImportsTest` (in
`core/src/test`) scans `core/src/main` and fails on such an import. Core's
only dependencies are `kotlinx-serialization-json` and
`kotlinx-coroutines-core`.

### `data/` — the shared Android data layer

An Android library (`com.android.library`, namespace
`io.github.kanylbullen.tung.data`, minSdk 26) with everything below the
screens, so the phone and the watch run the same code on their own
databases. The packages are the ones the files had in `app/`:

| Package (`data/src/main/kotlin/io/github/kanylbullen/tung/…`) | Contents |
|---|---|
| `data/` | Entities (`Exercise`, `Workout`, `WorkoutExercise`, `SetEntry`, `Routine`, `RoutineExercise`, `Program`, `ProgramDay`), `ExerciseDao`, `WorkoutDao`, `RoutineDao`, `ProgramDao`, `TungDatabase` (seeds the core catalogue's 70 exercises in `onCreate`), `ExerciseRepository`, `WorkoutRepository`, `RoutineRepository`, `ProgramRepository`, `ExtendedCatalogAssets.kt` (`ExtendedCatalog.fromAssets(context)`), `CatalogueMuscles` (core's `MuscleIndex` over both catalogue assets, `container.catalogueMuscles`), `AppCommandHandler` and `AppExerciseResolver` (see "Commands"), `TungContainer` and `TungHost`. `SeedExercises.kt` is generated. |
| `sync/` | Sync with tung-server: `SyncClient` (Ktor; also link codes, `/auth/methods`, Google login, `/me`), `SyncStore` (Room side), `SyncAccount` (sign in/out, link codes, triggers), `SyncWorker` + `WorkManagerSyncScheduler`, `SyncSettings` (DataStore), `TokenStore`/`EncryptedTokenStore`, `SyncMapping.kt`. See "Sync". |
| `timer/` | The rest timer's Android side: `RestTimerController` (DataStore + alarm), `RestAlarm`, `RestTimerReceiver` (declared in data's manifest), `RestAlerts` (vibration + notification). |
| `wear/` | `WatchStateSource`: the `WatchWorkoutState` of this device's database, with `WearSelection`/`WearSelectionHolder`. The phone publishes it to a companion watch; a standalone watch shows its own. |
| `ongoing/` | The ongoing notification of a workout in progress: `OngoingWorkout` (+ `ongoingWorkouts`, its Flow over the repositories), `OngoingWorkoutNotifier`, `OngoingWorkoutReceiver` (its Log set). See "Ongoing workout and push". |
| `push/` | Push: `PushRegistrar`, `PushEnvironment`/`AndroidPushEnvironment`, `TungFirebaseMessagingService`, `TungUnifiedPushReceiver`. See "Ongoing workout and push". |
| `ui/common/` | `ExerciseNames` and `Exercise.displayName(context)`; the composable `exerciseDisplayName` stays in `app/`. |

`../catalog` (`core.json`, `extended.json`) is an asset directory of the
library, so both APKs get it from there. The library's own strings are only
what library code shows (the "Rest over" and "Workout in progress" notifications), in
`data/src/main/res/values{,-sv}/`; screens' strings stay in each app.

- **`app` and `wear` never touch Room.** No DAO, no `TungDatabase`, no
  `withTransaction` outside `data/`: the apps read the repositories' Flows
  and change data through `container.commands` (see "Commands").
- **`TungContainer(context, clock, createDatabase, createTokenStore, startedBy, createPushEnvironment)`** is the
  one container. The application implements `TungHost` (`container`,
  `isInForeground`, the notification icon, the intents "Rest over" and the
  ongoing notification open), which
  is how `SyncWorker` finds the container and `RestAlerts` knows what to show,
  without the library knowing either app.
- **Tests:** `DirtyTrackingTest`, `SeedExercisesTest`,
  `RoutineEditingCommandsTest` (`create_routine` and the routine-editing
  commands through `AppCommandHandler`), `SyncClientTest` and `LinkClientTest` in `data/src/test` (Robolectric with the default
  application). `FakeSyncServer` and `MemoryTokenStore` are Kotlin test
  fixtures (`data/src/testFixtures`, `testImplementation(testFixtures(project(":data")))`
  in the apps), which needs `android.experimental.enableTestFixturesKotlinSupport`
  in `gradle.properties`.

### `app/` — the phone app

| Directory | Contents |
|---|---|
| `ui/<screen>/` | `Screen` + `ViewModel` + `UiState` per screen: `home`, `workout` (active workout, the input sheet, the rest sheet), `exercises` (tab and picker in one), `history` (list + detail), `summary`, `routines` (the Routines tab, one routine, the row and the name dialog that Home, summary, the tab and detail share; programs list + one program), `settings` (server and account, from Home's gear, and "Link a watch"). |
| `ui/ai/` | The Assistant screen (in-app AI with the user's own key), reached from the last row on Home; its settings are `ui/settings/AiSection.kt`. See "In-app AI (BYOK)". |
| `auth/` | `GoogleSignIn` and `AndroidGoogleSignIn` (Credential Manager), made by `TungApp.createGoogleSignIn()`. See "Sync", "Account". |
| `voice/` | `SpeechInput` and `AndroidSpeechInput` (the phone's `SpeechRecognizer`), made by `TungApp.createSpeechInput()`; the workout's mic, hands-free mode and the mapping of intents are in `ui/workout/Voice*.kt`. See "Voice". |
| `ui/common/` | Formatting, shared components, `WorkoutReport`, `exerciseDisplayName` (displayed exercise names, over data's `ExerciseNames`), `Containers.kt`. |
| `ui/theme/` | Colour tokens, typography, theme. |
| `wear/` | The phone's side of the Data Layer for a companion watch (section "Wear"). |
| `../catalog/` | The exercise catalogue as open data (CC0): `core.json`, `extended.json`, generator and import scripts. Shipped as an asset directory of `data/`. See `catalog/README.md`. |

The app's most important query is `WorkoutDao.previousSessionSets` — the sets
for an exercise from the latest finished workout that contains it. It drives
both "Last: 80 × 8" and the pre-fill of new sets.

### Routines

A `Routine` is a saved workout template: `RoutineExercise` rows in `position`
order, each with `targetSets` and a nullable `targetReps`. "Save as routine"
(summary, and the workout detail's menu) takes the logged sets per exercise:
`targetSets` is how many there were, `targetReps` the most common rep count
(`core.routineTarget`). Saving the same workout twice makes two routines.
**Starting** a routine (`RoutineRepository.start`) creates a workout with
`routineId` set, the routine's exercises in order and `targetSets` unticked
sets each, filled by `core.routineStartSets`: the existing pre-fill rule at
each set's own position, so set 1 gets last session's set 1, set 2 last
session's set 2; past the end of last session a set repeats the one before
it, and with no history at all the sets start at 0 kg × `targetReps`. Start
also sets `lastUsedAt`, which orders Home's Routines section and the Routines
tab. There is at most one workout in progress, so `start` returns that one
instead of creating a second, and the Start buttons are disabled while it
exists; `RoutineRow`'s Start is also disabled for a routine with no exercises.
`Workout.routineId` is indexed but deliberately **not** a foreign key:
deleting a routine (which cascades to its `RoutineExercise` rows) never
touches history.

**The Routines tab** (`Routes.ROUTINES`, `ui/routines/RoutinesScreen.kt`) is
the second of the bottom bar's tabs, Home · Routines · Exercises · History,
switched with `navigateToTab` (`saveState`/`restoreState`, like the others):
the Programs row on top, then every routine by last use, each with Start and
its detail behind a tap; routine and program details are pushed on top of the
tab, so back returns to it. Home keeps its Routines section and "Today"; its
"All" switches to the tab, and so does the Assistant's "Save program".
**"+ New routine"** in the tab's top bar opens `RoutineNameDialog` and runs
`CreateRoutine(name)` (`RoutineRepository.create`: an empty routine, stamped
and dirty), then opens the routine's detail, which says "Add exercises with
+ Exercise" until it has one.

**Editing a saved routine** (routine detail, `ui/routines/RoutineDetail*`),
each a command:

- **"+ Exercise"** opens the workout's picker with a routine target
  (`PickerTarget.Routine`, route `routine/{id}/add`); a tap runs
  `AddRoutineExercise`, appended last (`maxPosition + 1`) with 3 target sets
  and no target reps unless the command says otherwise. That picker has no
  "Suggested" section: its reasons are about today's workout.
- **Target sets** −/+ in the row (`AdjustTargetSets`, 1–20). **Target reps**:
  tapping the row's "8 reps per set" / "No target reps" opens the reps sheet
  (`repsSpec`, minimum 1) for `SetTargetReps`; "Clear target reps" in the
  row's menu sets null.
- **Remove** in the row's menu (`RemoveRoutineExercise`): tombstone, delete,
  and the positions after it close up, in one transaction.
- Move up/down, rename and delete as before. An edit never touches a workout
  already started from the routine, except that Log set there reads the
  target sets live (`WorkoutDao.routineTargetSets`).

### Programs

A `Program` is an ordered list of routines with a `mode`: `"fixed"` (each day
on chosen weekdays) or `"rotating"` (day 1, 2, 3 in order, whatever the date);
`core.ProgramMode` reads the key. Its days are `ProgramDay` rows: a
`routineId`, a `position`, and `weekdays`, a bitmask of ISO weekdays with
Monday in bit 0 (`core.weekdaysToMask`/`maskToWeekdays`), so one day can fall
on several weekdays; it is kept but unused in rotating mode. Both foreign keys
of `ProgramDay` cascade: deleting a program deletes its days, and deleting a
routine removes the days that used it, because a day without a routine has
nothing to start. `ProgramRepository` closes the position gap when a day is
removed, so rotating days keep counting 1, 2, 3. At most one program is
`isActive`: `ProgramRepository.activate` deactivates every other program in
the same transaction. The mode is chosen at creation and switched on the
program's screen ("Mode", the same two radio rows as the new-program
dialog, `ProgramModeChoice`) with `SetProgramMode`; days, their order and
their weekdays are kept, so switching back restores the schedule.

Home's "Today" shows only while a program is active and has days.
`core.suggestedDay` picks the day: fixed mode, the first day by position whose
weekdays include today, else null and Home says "Rest day" with no Start;
rotating mode, the day after the last completed one by position, wrapping to
the first, and the first when nothing is completed or the completed day is
gone. "Last completed" is the latest *finished* workout whose
`Workout.programDayId` is a day of this program
(`ProgramDao.observeLastCompletedDayId`). Starting from Today, or from the
"Choose another" sheet that overrides the suggestion, calls
`RoutineRepository.start(routineId, programDayId)`, so the workout records
both ids. `programDayId` is indexed and, like `routineId`, **not** a foreign
key: removing a day or deleting a program never touches history. "Today" is
`LocalDate.now(container.clock)`: the system clock in the app, a settable
one (`FlowTestApp.today`) in the flow tests. Programs are reached from a row
at the top of the Routines tab (`ui/routines/Program*`).

### Log set and rest timer

The active workout has a sticky **Log set · Bench press · 80 × 8** button
above the system navigation (always under the thumb, wherever the list is
scrolled). It acts on the **current set** (`core.currentSet`), marked by a 3dp
accent bar at the left edge of its row (`Tung.colors.accent`; "Current set"
as its number's state description). The rule, in order: an explicit selection
while that set still exists and is un-ticked; else the first un-ticked set of
the exercise the user last touched (ticked, edited, added a set to, or just
added); else the first un-ticked set of the first exercise that has one; no
current set, no button. The selection is made by hand: a tap on an exercise's
**header** selects its first un-ticked set ("Log sets of Bench press next"),
a tap on the **number** of any other un-ticked set selects that set ("Make set
2 the current set"); that is how the user switches exercises and runs
supersets. Edits elsewhere (weight, reps, + Set) only move the touched
exercise, so they never override a manual selection. A **tick or Log set**
moves the current set by `core.nextCurrentAfterLogging` and clears the
selection: the first un-ticked set of the same exercise while it has one, else
of the next exercise with one in list order, wrapping. Adding an exercise
makes it current, over a manual selection. The selected set id and the
touched exercise live in the ViewModel's `SavedStateHandle`, so both survive
rotation and process death. **Auto-scroll:** the ViewModel puts a one-shot
`ScrollRequest` (a set or an exercise header, mode `IntoView` or `ToTop`) in
the UI state and the screen carries it out with the `LazyListState`, finding
the index among the list's keys (`listKeys`), then calls `onScrollHandled`.
`IntoView` scrolls only when the item is not wholly between the top bar and
the button: just enough when it is partly visible, else to it with the row
above in view. Requests: the new set after + Set, the new current set after
Log set (the set it added, or the next exercise's), the new current set when a
tick moves it to another exercise, and the new exercise's header to the top
after Add exercise.
Tapping the button executes `Command.LogSet(workoutId, setId)` with the set
the screen shows as current, which calls `WorkoutRepository.logSet`, one transaction:
tick the set and, **only if it was the last set of its exercise**, add the next
set pre-filled by the usual rule (a copy of the set just logged). A workout
started from a routine is not padded past the routine's `targetSets` for that
exercise (`core.addsNextSet`, `WorkoutDao.routineTargetSets` reads the
routine live), so its third Log set of three adds nothing and the current set
moves on. With reps 0 it opens the reps sheet instead, like the tick. The tick
on the row is the secondary way: it ticks only, and also starts the timer.

**The timer state** is `core.RestTimer` (`endsAtMillis`, `durationMillis`,
wall clock); what is left is always `remainingAt(now)`, never a counter. The
active workout's ViewModel holds it and writes it to its `SavedStateHandle`
and, through `RestTimerController`, to DataStore (`settings` file, also the
global default `rest_default_seconds`, 90 s, with no screen yet) and to an
alarm. A new ViewModel restores it from saved state, else from DataStore,
and drops one that already ended. The rest per exercise is
`Exercise.restSeconds` (null = default, 0 = off), set from the exercise's menu
("Rest timer…", 0–300 s in 30 s steps) as an ordinary update with
`updatedAt`. The countdown sits next to the elapsed time; tapping it shows
Skip and +30 s.

**When it ends:** `RestAlarm` sets a one-shot `RTC_WAKEUP` alarm, exact
(`setExactAndAllowWhileIdle`) below Android 12 or when "Alarms & reminders"
(`SCHEDULE_EXACT_ALARM`) is granted, else `setAndAllowWhileIdle`, which may be
late in the background. `USE_EXACT_ALARM` is not used: Play reserves it for
alarm-clock, timer and calendar apps. `RestTimerReceiver` gets the broadcast
and calls `RestAlerts.restOver`: vibrate (`VibratorManager` on 31+, `Vibrator`
below), and post "Rest over" on channel `rest_timer` only when no activity is
started (`TungHost.isInForeground`). With the workout on screen the countdown
reaching zero calls the same function, so the alert is on time even with an
inexact alarm; it alerts once per `endsAt`. Tapping the notification opens
`TungHost.openWorkoutIntent`: on the phone `MainActivity` with `EXTRA_OPEN_WORKOUT`, which `TungAppRoot` navigates to. No
foreground service. `POST_NOTIFICATIONS` is asked for once, the first time a
workout is on screen on Android 13+ (flag in DataStore,
`RestTimerController.firstTimeAskingForNotifications`, shared with the ongoing
notification); denied, the countdown and the vibration still work.

The DataStore is built in `TungContainer` with `PreferenceDataStoreFactory`,
not the `preferencesDataStore` delegate: the delegate is a process-wide
singleton, and Robolectric makes a new application for every flow test.

### Ids and `updatedAt`

All eight entities have `id: String`, a UUID generated by the client
(`Ids.kt` in `core`): `newId()` for everything the user creates (workouts, workout
exercises, sets, custom exercises, routines, routine exercises, programs, program days), and `catalogExerciseId(nameKey)` for
catalogue exercises — a name-based UUID from `"tung:exercise:<nameKey>"`, so the
same catalogue exercise has the same id on every device. The seeding in
`TungDatabase` uses it, with `updatedAt = 0` for an untouched catalogue row.
Every entity has `updatedAt` (epoch millis), and it is set by the repository on
every insert and update, together with `dirty` (section "Sync"); screens and
ViewModels never touch either. Why:
preparation for sync with a server, and export/import between devices without
id collisions. No query may sort on id — order comes from `position` or
timestamps.

### Exercise names are resources

Catalogue exercises have a stable key in `Exercise.nameKey` (`bench_press`,
`back_squat` …) and are shown via the string resource `ex_<nameKey>`, so the
name follows the phone's language. `name` is stored anyway, as the English
fallback. Custom exercises have `nameKey = null` and show what the user typed.
Everything that shows an exercise name goes through
`exerciseDisplayName(nameKey, name)` (app) or `Exercise.displayName(context)`
(`data/.../ui/common/ExerciseNames.kt`), which looks up each key with
`getIdentifier` in the running app's package once and caches the id. The
`ex_*` strings are generated into both apps, so the watch names exercises in
its own language too. Search in the picker matches the **displayed** name
(`core.filterByDisplayName`), never `name` in SQL.

**The source of truth is `catalog/core.json`**, not Kotlin or the strings.
`data/src/main/kotlin/.../data/SeedExercises.kt` and the `ex_*` lines between
`<!-- catalog:begin -->` and `<!-- catalog:end -->` in the `strings.xml` of
`app/` and `wear/` (English and Swedish each) are generated by
`catalog/tools/gen_android.py`; never edit them by hand. Adding an exercise =
add the entry to `core.json` (sorted by `key`, `en` and `sv` required) and run
`python3 -I catalog/tools/gen_android.py`; five files are updated
(`SeedExercises.kt` and four `strings.xml`) and all six are committed. `--check` writes nothing and exits with 1 if anything is
out of date. `CatalogTest` (in `core`) checks `core.json` and `extended.json`
as data, and `SeedExercisesTest` (in `data`) compares `core.json` with the seed list.
English names are gym English (Lat pulldown, Skull crusher), not literal
translations. A `key` never changes once it exists: it carries the exercise's id.

### Muscles

Each catalogue entry, core and extended, carries `primaryMuscles` (1–3) and
`secondaryMuscles` (0–4) from a fixed list of 21 (`core.Muscle`: chest,
lats, upper_back, lower_back, traps, front_delts, side_delts, rear_delts,
biceps, triceps, forearms, quads, hamstrings, glutes, calves, adductors,
abductors, abs, obliques, hip_flexors, neck), each in one `MuscleGroup`.
**`muscleGroup` stays the display level and follows the first primary
muscle**: `muscleGroup == MuscleGroup.of(primaryMuscles[0])`, except
`fullbody`, which has no muscle of its own and which whole-body lifts keep
(`muscleGroupMatches`; `CatalogTest` checks both files). The muscles live
only in the catalogue files: no Room column, nothing synced. `MuscleIndex`
(core, built from both files) answers `musclesOf(id, key or extended id)`;
the app gets it from `container.catalogueMuscles.index()`, the server would
build it from its classpath copies. free-exercise-db has one `shoulders`, so
the import maps every one to `side_delts` (`catalog/README.md`); front and
rear delts come only from `core.json`. Table and rules: `catalog/README.md`,
"Muscles".

### The extended catalogue

`catalog/extended.json` (~600 exercises, English only, from free-exercise-db
under the Unlicense, built by `catalog/tools/import_free_exercise_db.py`) is
**not** in Room. `ExtendedCatalog` (in `core`, fed from assets by
`ExtendedCatalog.fromAssets`) reads it once, lazily on IO,
and only the exercise picker searches it: from two characters the matches are
shown under the heading "More exercises", with the same `filterByDisplayName`.
The Exercises tab never shows it. When such an exercise is picked,
`ExerciseRepository.getOrCreateExtended` does a get-or-create (insert with
`OnConflictStrategy.IGNORE`) with the deterministic id
`extendedExerciseId(fedId)` = `catalogExerciseId("ext:" + fedId)`,
`nameKey = null`, the English name in `name` and `isCustom = false`. The same
exercise thus gets the same row on every device, and after that it is an
ordinary exercise (it shows in the tab and among the core matches, and
disappears from "More exercises").

A set counts (`core.isLogged`) when it is ticked **and** has reps > 0.
"Finish workout" deletes everything else, then exercises without sets, and a
workout without a single counted set is deleted entirely instead of being
saved empty.

### Smart picker

Opened from a workout that already has exercises, with the search empty, the
picker shows **Suggested** first: up to six rows, each with a muted reason
("Often after Barbell curl", "Pairs with biceps", "Not trained yet today",
"Often together"), then the usual grouped list. Typing hides the section;
a tap is the same `AddExercise` as a list row. The ranking is pure and lives
in core, `core/picker/Suggestions.kt` (`suggest`), so a server or the watch
can rank the same way; the app only feeds it (`ExercisesViewModel`). Two
layers, with A the last exercise in the workout:

- **Learned**, from the latest 60 finished workouts
  (`WorkoutRepository.recentHistory`, `WorkoutDao.recentExerciseOrder`):
  B right after A weighs 3, B in a workout with any exercise of this one
  weighs 1, each times a recency factor from 1.0 (newest) down to 0.5 (60th).
- **Heuristic**, always added at half weight, from the catalogue's muscles
  (`container.catalogueMuscles`): +2 when B's first primary muscle
  complements A's (biceps–triceps, chest–lats/upper back, quads–hamstrings,
  front–rear delts, abs–lower back), +1 for a group not in the workout yet,
  −2 for A's own main muscle, −1 for abs and obliques unless nothing else is
  left. Exercises already in the workout are never suggested.

**Hand-over:** once A has three observations (recent workouts with A and
another exercise) the learned score is tripled, so the user's habits win;
before that the heuristic separates what history does not. Each row's reason
is the largest contribution; order is score, then name. **The learning is
derived from history every time the picker opens and is never stored or
synced**: no table, no column, nothing new in Private mode or on the server.
The server has no `suggest_exercises` MCP tool yet.

## Commands

Everything that changes training data is a **command** (`core/command/`,
package `io.github.kanylbullen.tung.core.command`): `Command` is a sealed
interface of plain data classes (`StartWorkout`, `AddExercise`, `SetWeight`,
`LogSet`, `FinishWorkout`, `ApplyPlan`, `SaveRoutine`, `CreateProgram`, …),
each carrying only what its caller knows. The app's buttons call them today;
the REST API, the MCP tools, voice and camera will call the same ones, so a
button and an MCP call cannot drift apart. A new input is a translator from
its signal to commands, never a new path into the database
(`docs/architecture.md`, "One command surface").

- **The rule: a ViewModel never calls a repository for a mutation.** It calls
  `container.commands.execute(command)` (a `CommandHandler`). Reads stay on
  the repositories' Flows. Where a ViewModel needs an id back (the new set to
  scroll to, the started workout to navigate to) it takes it from
  `CommandResult.Done.ids`, under the keys in `CommandIds` (`id(key)` reads
  one). View state stays in the ViewModel: the rest timer, the selection and
  the touched exercise, scroll requests. That is why `LogSet` takes the set
  the screen shows as current, and why the ViewModel selects a set itself
  (`SelectCurrent` exists for other callers and only resolves).
- **One handler.** `data/AppCommandHandler.kt` (module `data/`, so the phone
  and a standalone watch run the same one) maps each command onto the
  existing repositories, which keep their transactions and rules. It runs
  `validate(command)` first (pure, in core: weight 0–999 kg, reps 0–999,
  planned sets 1–50, a routine's target sets 1–20 and target reps 1–999
  (or none), rest 0–3600 s, weekday
  1–7, no blank names or ids, no empty plans) and returns `Rejected(reasons)`
  without touching anything when it fails. A new mutation = a new `Command`
  data class, a name in `CommandNames`, a branch in `validate` and in the
  handler (both `when`s are exhaustive, so the compiler points at them).
- **Exercises by reference.** A command names an exercise with an
  `ExerciseRef`: `ById` (the picker), `ByKey` (`bench_press`), `Extended`
  (an extended-catalogue id; the handler creates the row the first time) or
  `ByName` (words from a voice or a model). Resolution is core's
  (`resolveRef`, `resolvePlan`, `matchExerciseName`) over an
  `ExerciseResolver`, an interface core defines and the app implements in
  `data/AppExerciseResolver.kt` over Room, `core.json`'s names in every
  language (so "Bänkpress" resolves on an English phone) and the extended
  catalogue. **Only an exact name resolves** (after
  `normalizeExerciseName`: the picker's normalisation plus punctuation as
  spaces), and only when one exercise has it; a row beats an extended entry
  of the same name. Anything else is `NeedsAnswers` with up to five
  suggestions, never a guess.
- **`ApplyPlan(WorkoutPlan)`** applies a whole dictated or recognised workout:
  every exercise is resolved first, and if any is unknown nothing is written.
  Then one Room transaction (`WorkoutRepository.applyPlan`): the workout in
  progress or a new one, the exercises appended in order, the planned sets,
  nothing ticked. A number the plan leaves out comes from the pre-fill rule
  (`plannedSetValues`). `SaveRoutinePlan(RoutinePlan)` does the same for a
  routine.
- **`CreateRoutine(name)`** makes an empty routine (only a non-blank name;
  returns `routineId`), to be filled with `AddRoutineExercise`;
  `SaveRoutinePlan` refuses an empty plan, so this is how a routine starts
  from scratch. Wire name `create_routine`.
- **Routine and program editing**: `RenameRoutine`, `DeleteRoutine`,
  `MoveRoutineExercise`, `AdjustTargetSets`, `AddRoutineExercise` (resolved
  like `AddExercise`: an unknown name is `NeedsAnswers`; returns
  `routineExerciseId` and `exerciseId`), `RemoveRoutineExercise`,
  `SetTargetReps` (null clears it; on the wire `target_reps` is required, so
  clearing is explicit), and for programs `SetProgramMode` next to the day
  commands. Wire names: `add_routine_exercise`, `remove_routine_exercise`,
  `set_target_reps`, `set_program_mode`.
- **`CommandNames` are stable.** Each command has a snake_case wire name
  (`log_set`, `apply_plan`, …): the future REST route and MCP tool name.
  Clients and saved prompts outside the repository will call them, so a name
  never changes once it exists, like a catalogue key; rename the Kotlin class
  freely, but add a new command rather than rename a wire name. The
  `CommandIds` keys are stable for the same reason. `CommandNamesTest` fails
  when a command has no name (the commands are nested in `Command` so it can
  list them with plain Java reflection).

## Sync

The client half of issue #25, in `data/src/main/kotlin/io/github/kanylbullen/tung/sync/`
and shared by the phone and a standalone watch: the device and a tung-server
exchange changed rows over `POST /api/v1/sync`. The protocol is `core/.../core/sync/SyncProtocol.kt`
(shared with the server, snake_case on the wire, one list per table, a
tombstone is a row with `deleted_at`); the pure rules are next to it:
`SyncPlanner` (what to push, in chunks of at most `SYNC_MAX_ROWS`, parents
first), `SyncApplier` (how a response merges), `checkServerUrl` (https, or
http only to localhost and private IP literals), `SyncState`, `SyncFailure`.

- **Dirty.** All eight entities have `dirty` (default `true`). The one place
  per repository that stamps `updatedAt` stamps `dirty` with it: inserts get
  the entity default, and every DAO `UPDATE … SET … updatedAt = :updatedAt`
  also says `dirty = 1` (`DirtyTrackingTest` scans the DAO sources for it).
  The seeded catalogue rows are inserted with `dirty = 0` and never pushed;
  setting a catalogue exercise's rest time makes it dirty, and it goes up as
  the user's copy. Only sync clears the flag (`SyncDao`).
- **Tombstones.** `tombstones (tableName, id, deletedAt, dirty)`, `tableName`
  being the wire key (`SyncTable.key`). Every delete path in the repositories
  writes one in the same transaction as the delete, for the row and for every
  row its foreign keys cascade to (`TungDatabase.recordDeletes`): sets,
  workout exercises, workouts (including the discard in `finishWorkout`),
  routines (their exercises and the program days that use them), programs and
  program days. Existing queries never read the table. There is no delete path
  for exercises.
- **One sync** (`SyncClient.syncOnce`, always on `Dispatchers.IO`, one at a
  time): read the dirty rows and tombstones in one transaction, plan, POST each
  chunk with the latest cursor (5xx and I/O errors are retried twice, a 4xx
  never), and apply each response in one Room transaction (`SyncStore.apply`):
  strictly newer wins, equal or older is ignored (a newer local edit stays
  dirty and goes next time), a newer tombstone deletes, a newer row brings back
  a locally deleted one. Rows are upserted parents first (`@Upsert`, never
  REPLACE, which would cascade) and deleted children first; a row whose parent
  is missing, or that lacks a required field with no local row to take it
  from, is skipped; an exercise still in use is not deleted. Then the dirty
  flags are cleared **only for the rows that request carried and only if they
  still have the pushed `updatedAt`**, so an edit made during the request goes
  with the next sync. The cursor is stored after each chunk; while a response
  comes back with `SYNC_MAX_ROWS` rows it pulls again. A 401 clears the token
  and shows "signed out".
- **Triggers** (`SyncAccount`, `SyncWorker`): unique periodic work every 15
  min with a network constraint while signed in (scheduled at sign-in and
  re-asserted at app start), and `enqueueNow` (unique, `APPEND_OR_REPLACE`)
  after `FinishWorkout` from `AppCommandHandler`'s result path
  (`onWorkoutFinished`) and when the app comes to the foreground
  (`ProcessLifecycleOwner`). Exponential backoff from 30 s; network and 5xx
  retry, 401 and other 4xx do not. Sign-out cancels both. "Sync now" in
  Settings calls `syncOnce` directly, so the screen sees the result.
  WorkManager is initialised on demand (`TungApp` and `TungWearApp` are
  `Configuration.Provider`s; each manifest removes its startup initializer).
- **Account.** Settings (gear on Home) takes the server URL, email and
  password and calls `/api/v1/auth/login` or `/register`. **Sign in with
  Google** (issue #51): whenever the form shows a valid server address the
  ViewModel asks `GET /api/v1/auth/methods` (`SyncAccount.authMethods`;
  600 ms after typing stops, on `Dispatchers.Default` so the pause is real
  time under Robolectric; a 404 from an older server means password only),
  and shows "Sign in with Google" only when it names a client id.
  `GoogleSignIn` (`app/.../auth/`, made by `TungApp.createGoogleSignIn()`,
  which `FlowTestApp` overrides with `FakeGoogleSignIn`, like
  `createSpeechInput`) gets an ID token for that id from Credential Manager
  (`AndroidGoogleSignIn`: `GetGoogleIdOption`, all accounts, no
  auto-select, a nonce); `SyncAccount.signInWithGoogle` trades it at
  `/api/v1/auth/google` and finishes like `signIn`. "Google linked" under the
  account comes from `SyncSettings.googleLinked`, set at sign-in and
  refreshed from `/me` when Settings opens. The watch keeps the link code. The session token is
  in `EncryptedSharedPreferences` (`sync_token`, excluded from backups because
  its Keystore key cannot be restored), never in DataStore or a log; the URL,
  email, cursor and last result are in the `settings` DataStore
  (`SyncSettings`). Signing in resets the cursor to 0 and marks every row
  dirty, so a new account receives everything; signing out cancels the work,
  forgets the token, calls `/logout` and keeps all local data.
- **Link codes** (issue #30): a signed-in device asks for a six-digit code
  (`SyncAccount.startLink` → `POST /api/v1/link/start`, the phone's "Link a
  watch") and a device that cannot type a password trades it for its own
  token (`SyncAccount.signInWithCode` → `POST /api/v1/link/claim`, the
  watch). After the claim it is the same as a password sign-in: token stored,
  cursor 0, everything dirty, work scheduled. `SyncSettings.recentServers`
  keeps the last five servers for the watch's server list.
- **Network.** `INTERNET` is each app's only network permission and the
  server URL its only destination. The network security config permits
  cleartext because it cannot express "private IPs only"; `checkServerUrl` is
  that gate.
- **Schema.** Installed phones are upgraded in place and never wiped; a PR
  that needs a wipe is a bug. **Every schema change** (a column, a table, an
  index) raises `TungDatabase`'s `version` by one, commits the new
  `data/schemas/io.github.kanylbullen.tung.data.TungDatabase/<n>.json` (KSP
  writes it on build; `exportSchema = true`, `room.schemaLocation` in
  `data/build.gradle.kts`), adds a `Migration` to `ALL_MIGRATIONS`
  (`data/.../data/Migrations.kt`, registered in both `TungDatabase.build` and
  `buildInMemory`) and a case in `MigrationTest`, which creates the old
  version from its committed JSON with `MigrationTestHelper` under
  Robolectric (the schemas are a test asset directory), runs the migration,
  validates against the new schema and reads the old rows back.
  `everyVersionHasASchemaAndAMigration` fails when a version has no JSON or no
  migration. Version 1 is the schema as it was on 2026-10-20; version 2
  (issue #45) added the Health Connect columns on `workout`.

## Wear

`wear/` is the Wear OS app (Compose for Wear OS 1.4, minSdk 30, Galaxy Watch 4
and later). Same `applicationId` as the phone (`io.github.kanylbullen.tung`;
the Data Layer only pairs apps with the same id and signing key), its own
namespace `io.github.kanylbullen.tung.wear`, `standalone = true`. It depends
on `data/` and builds its own `TungContainer` (`TungWearApp`, a `TungHost`
and WorkManager `Configuration.Provider`). Install and pairing: `wear/README.md`.

**Two modes, chosen automatically** (`WatchMode.kt`, from the token store):

- **Standalone** when the watch has a server token. Its own Room database is
  the log; `WatchViewModel` uses `LocalWatchBackend`: the state is
  `WatchStateSource` over the watch's database (so "Last time" is the
  watch's own history, which after a sync is the phone's too), and every tap
  goes through `LocalWatchCommands`, which turns it into a core `Command` for
  the shared `CommandHandler`. Routine target sets, one workout in progress
  and finish semantics are therefore the phone's. View state is kept as the
  phone's workout screen keeps it: the current-set selection in a
  `WearSelectionHolder`, the rest timer in the watch's `RestTimerController`
  (DataStore + alarm, `RestAlerts` buzzes once per rest). Sync is the
  phone's: `SyncWorker` periodic while signed in, after `FinishWorkout`, and
  when the app comes to the foreground, over whatever network the watch has.
- **Companion** when it has no token: v0, unchanged. The watch mirrors the
  phone over the Data Layer (`PhoneLink`) and sends it commands. Without a
  paired phone it shows the hint to open the phone or link the watch.
- **Both available:** standalone wins and the Data Layer state is ignored;
  the watch and the phone then meet through the server.

**Linking** (no password on the wrist): Settings, from the start screen
(`ui/SettingsScreen.kt`, `AccountViewModel`), shows the account, "Sync now"
with the last sync and "Sign out", or the way in. The server is normally the
one the phone publishes in its Data Layer state (`WatchWorkoutState.serverUrl`,
filled by the phone's publisher), offered as "Link to <host>"; otherwise a
list of recent servers (`SyncSettings.recentServers`) and a text field, where
a bare host means https (`checkWatchServerInput`). Then a six-digit number pad
(`ui/LinkCodeScreen.kt`, `LinkCodeInput`) whose sixth digit claims the code
the phone's Settings shows ("Link a watch"), through
`SyncAccount.signInWithCode`.

**Protocol** (`core/.../core/wear/WatchProtocol.kt`, JSON, shared by both
sides) for companion mode: the phone writes a `WatchWorkoutState` as the
DataItem `/tung/active_workout`; the watch sends a `WatchCommand` as a message
to `/tung/cmd`. State is a **DataItem** because the Data Layer syncs and keeps
it: a watch that was out of range, or just started, reads the latest state
with no request. Commands are **messages** because they are actions, not
state: a DataItem would replay or coalesce them. Messages are not queued, so a
failed send shows "Phone not reachable" and the watch keeps its optimistic
change (`wear/.../Optimistic.kt`) until the phone's next state replaces it.
Standalone mode applies no optimistic change: the command runs locally and
the next state shows what it did.

**Phone side** (`app/.../wear/`): `WearSyncService` (a `WearableListenerService`,
started by the system on a message) hands the JSON to `WearCommandHandler`,
which applies it through `WorkoutRepository`/`RoutineRepository` and
`RestTimerController`, checks every set id against the workout in progress and
drops repeated `commandId`s, then publishes once. `WearStatePublisher` (on
`TungApp`, started from `TungApp.onCreate` when Play services is present)
writes `WatchStateSource`'s state (data's, the same builder a standalone
watch uses) as the DataItem on change, debounced 100 ms and skipped when
nothing changed, with the phone's server URL in it.

**Limitations.** The publisher only runs while the phone app's process does;
the DataItem keeps the last state when Android kills it, and the service
restarts the process for the next command. The phone's workout screen does
not see a companion watch's selection or rest. The phone's
`WearCommandHandler` still writes through the repositories rather than the
`CommandHandler` (its rules match; moving it is a follow-up). A standalone
watch cannot add exercises: start a routine, or add them on the phone and
sync. The watch asks for the notification permission once, the first time a
workout is on screen (Wear OS 4+), for its Ongoing Activity and "Rest over".

**File boundaries.** Watch code only in `wear/`; the protocol only in
`core/.../core/wear/`; what both devices share in `data/`; the phone's
companion code only in `app/.../wear/` plus the service in the manifest and
the publisher on `TungApp`. The watch never reaches Room directly; everything
goes through a command.

## Ongoing workout and push

Issue #34; the design is `docs/architecture.md`, "The ongoing workout and push".

- **The ongoing notification** (`data/.../ongoing/`). `OngoingWorkoutNotifier`
  follows a `Flow<OngoingWorkout?>` and posts one ongoing notification
  (`setOngoing`, `CATEGORY_WORKOUT`, channel `workout_in_progress`, low
  importance, silent, id 2; "Rest over" is 1) while it has a workout, and
  cancels it on null (finished or discarded). No foreground service. Title
  "Workout in progress"; body from core's `ongoingStatus`: "Bench press · set
  3 of 5" for the current set by the default rule of `core.currentSet`
  (no selection: the notification cannot see the screen's), counted against
  the routine's target sets (or the sets there are, if more), else "4 sets
  logged"; elapsed time by `setUsesChronometer` + `setWhen(startedAt)`. The
  phone's source is `ongoingWorkouts(...)` over the repositories, built as
  `container.ongoingWorkout` and started in `TungApp.onCreate`, so it shows
  whoever started the workout: this phone, a companion watch, or a sync that
  brought one from the server. Nothing is posted without `POST_NOTIFICATIONS`.
  **Android 16 Live Update is not done:** `setRequestPromotedOngoing` is API 36
  and compileSdk stays 35.
- **Actions.** Tap opens the workout (`TungHost.openWorkoutIntent`). **Log
  set** is a broadcast to `OngoingWorkoutReceiver` (in data's manifest) with
  the set the notification showed; it runs `Command.LogSet` through
  `container.commands`, no UI, and does nothing if that set is no longer
  open, so a stale notification cannot tick the wrong set. A current set with
  0 reps makes Log set open the workout instead (like the tick opening the
  reps sheet); it starts no rest timer. **Finish** opens
  `TungHost.finishWorkoutIntent`: on the phone `MainActivity` with
  `EXTRA_FINISH_WORKOUT`, and `TungAppRoot` hands the workout screen a
  one-shot request that opens its finish dialog; it never finishes silently.
- **`Workout.startedBy`** (`core.StartedBy`: `phone`, `watch`, `server`,
  `agent`). Each command path stamps its own when it creates a workout:
  `AppCommandHandler` gets it from `TungContainer(startedBy = …)` (`phone`,
  `watch` on the watch), the phone's `WearCommandHandler` stamps `watch`, the
  server's command handler `agent` (REST commands and MCP tools); `server`
  is reserved. Synced as the additive, nullable `started_by`; a null from a
  peer never erases a stored value (app `SyncMapping`, server `COALESCE`). The
  notification's sub-text says "Started by your assistant" for `agent`.
- **The watch** (`wear/.../WatchOngoing.kt`): `WatchOngoingActivity` runs the
  same notifier over the state the watch shows (`watchOngoing(state)`, pure:
  the watch's own database standalone through the shared `LocalWatchBackend`,
  the phone's DataItem in companion mode while the process lives) and
  attaches an androidx.wear `OngoingActivity` in `decorate`. No actions there.
  `WatchExercise.targetSets` and `WatchWorkoutState.startedBy` carry what it needs.
- **Push transports** (`data/.../push/`). The server only ever sends an empty
  "sync now"; receiving one on either transport calls
  `SyncAccount.requestSync()`. `core.selectPushTransport` (pure) picks: FCM
  when Play services are available and the build has a Firebase app
  (`FirebaseApp.getApps`), else the chosen or first UnifiedPush distributor,
  else none (periodic sync). `PushRegistrar` (started by `TungApp`, never by
  the watch) refreshes on app start and every sign-in, registers the FCM
  token or UnifiedPush endpoint with `POST /api/v1/push/endpoints` under the
  device id, follows `onNewToken`/`onNewEndpoint`, and deletes the endpoint
  in `SyncAccount.signOut` before the token is forgotten (`beforeSignOut`).
  Settings shows the transport and offers "Choose UnifiedPush distributor"
  when several are installed. `TungFirebaseMessagingService` and
  `TungUnifiedPushReceiver` (connector 2.5.0) are in data's manifest.
- **Firebase is optional at build time.** `firebase-messaging` comes through
  the Firebase BoM in `data/`; the `com.google.gms.google-services` plugin is
  declared `apply false` at the root and applied by `app/` and `wear/` **only
  when their `google-services.json` exists**. Without it Firebase has no app
  and FCM is off; the open-source build compiles and runs. Never commit a
  `google-services.json` (gitignored); how to add one: `server/README.md`,
  "Push".
- **`X-Tung-Device`** (`core.sync.DEVICE_HEADER`): every sync request and
  push registration carries the device's stable random id
  (`SyncSettings.deviceId()`, DataStore, kept across sign-outs), so the server
  never wakes the device that made a change.
- **Server trigger** (`server/.../push/`): after a successful write by a
  client (a sync that carried rows, a REST command or MCP tool with a `Done`
  result except `select_current`, the REST writes) `ChangeNotifier.changed(user,
  device)`; `PushService` debounces 2 s per user, sends to every other active
  endpoint (one in flight per endpoint), revokes on 404/410 and logs and
  drops other failures. A request without the header counts as a client of
  its own. `UnifiedPushSender` POSTs `{"type":"sync"}`, behind
  `EndpointGuard` at registration and at every send (https only, and no
  loopback, link-local, private, CGNAT or multicast address, literal or
  resolved, unless `TUNG_PUSH_ALLOW_PRIVATE=true`; `ServerConfig.pushResolver`
  is the DNS seam tests replace), no redirects, at most 1 KiB of the answer
  read, 5 s in all; `FcmSender` uses the
  HTTP v1 API with a hand-rolled service-account JWT; FCM is off without
  `TUNG_FCM_SERVICE_ACCOUNT_JSON`.

## Health Connect

Issue #45; the design is `docs/architecture.md`, "Health Connect". The phone
only: the watch's `TungContainer` gets no gateway, and the server never talks
to Health Connect, it stores what the phone syncs.

- **Where it lives.** `data/.../health/`: `HealthConnectGateway` (interface:
  `availability`, `permissions(access)`, `hasPermissions(access)`, the
  permission sheet's `ActivityResultContract`, `writeSession`, `readVitals`),
  `AndroidHealthConnectGateway` (the real one, over
  `androidx.health.connect:connect-client:1.1.0-beta01`, the newest that builds
  with compileSdk 35 and AGP 8.7; 1.1.0 needs compileSdk 36), and
  `HealthConnectRecorder`, which does the work. `FakeHealthConnect` is in
  data's test fixtures. `TungContainer(createHealthConnect = …)` is the seam:
  `TungApp` passes `::AndroidHealthConnectGateway`, the watch nothing (null),
  `FlowTestApp` the fake. The pure parts are in core, `core/health/`:
  `HEALTH_CONNECT_SEGMENTS` (catalogue key → `HealthSegmentType`, named after
  `ExerciseSegment.EXERCISE_SEGMENT_TYPE_*`; data maps each entry to the
  library's constant in an exhaustive `when`, and
  `HealthConnectSegmentTypesTest` builds a strength-training record with every
  type, because Health Connect throws on a segment the session type does not
  allow), `planHealthSession` and `aggregateVitals`.
- **Two opt-ins**, `health_connect_write` and `health_connect_read` in the
  `settings` DataStore (`HealthConnectSettings`), both off by default and not
  synced: Settings' last section (`ui/settings/HealthConnectSection.kt`, one
  call in `SettingsScreen`) has a switch for each, which turns on only after
  Health Connect's permission sheet granted its permissions
  (`WRITE_EXERCISE`; `READ_HEART_RATE` + `READ_ACTIVE_CALORIES_BURNED`, all
  declared in the phone's manifest) and shows off when a permission was
  revoked. Unavailable or outdated Health Connect: no switches, a hint.
  `HealthPermissionsActivity` is the privacy text Health Connect opens
  (`ACTION_SHOW_PERMISSIONS_RATIONALE`, and the `VIEW_PERMISSION_USAGE` alias
  on Android 14+); the manifest also has the `<queries>` entry for
  `com.google.android.apps.healthdata`.
- **When.** `AppCommandHandler`'s `onWorkoutFinished(workoutId)` (null when the
  workout was discarded) → `TungContainer.workoutFinished`: the recorder runs
  first, then the sync request, so what it stored goes up in the same sync.
  Only `FinishWorkout` through the phone's `CommandHandler`; a companion
  watch's finish (`WearCommandHandler`) and a workout finished elsewhere and
  synced here are not written or read.
- **Write.** One `ExerciseSessionRecord`, `EXERCISE_TYPE_STRENGTH_TRAINING`,
  titled with the routine's name or "Workout" (data's
  `health_session_title`). Tung keeps no time per set, so the interval is split
  evenly into one slot per logged set in workout order; a set whose exercise
  has a type becomes a segment over its slot with its reps, **a set without
  one is left unsegmented** (no `OTHER_WORKOUT` filler).
- **Written once.** The record id goes into `Workout.healthConnectId` through
  `WorkoutRepository.setHealthConnectId` (`WHERE healthConnectId IS NULL`,
  stamps `updatedAt` and `dirty`), and nothing is written for a workout that
  has one, from this phone or synced from another. The recorder is behind a
  mutex, and the record's `clientRecordId` is `tung:workout:<id>`, so even a
  crash between the insert and the update cannot make a second session.
- **Read.** Heart-rate samples and active-calories records over
  `[startedAt, endedAt]` (paged); core's `aggregateVitals` takes the rounded
  mean and the max of samples inside the interval in 20–300 bpm, and calories
  prorated by overlap, summed per source app with the largest source winning
  (two apps often write the same burn). Stored with
  `WorkoutRepository.setVitals` (a null keeps a column). Read once, right
  after finishing: heart rate a watch syncs into Health Connect later is not
  picked up.
- **Columns.** `Workout.avgHeartRate`, `maxHeartRate`, `activeCalories` (kcal),
  `healthConnectId`, all nullable (Room version 2, `MIGRATION_1_2`). Synced as
  `avg_heart_rate`, `max_heart_rate`, `active_calories`, `health_connect_id`,
  additive and nullable like `started_by`, and like it **a null from a peer
  never erases a stored value** (app `SyncMapping`, server `keepWhenNull`, i.e.
  `COALESCE`). Server: `V9__health.sql`, validated in sync (20–300, 0–20000,
  an id of at most 200 characters), shown by `WorkoutDto` (REST, export) and
  MCP's `history`; `PUT /workouts/{id}` does not take them and leaves them.
  The workout detail shows "Heart rate 128 avg · 171 max · 342 kcal" as one
  muted line when there is anything.
- **Tests.** Core: `HealthConnectSegmentsTest` (every mapped key is a
  catalogue key, the slots), `HealthVitalsTest`. Data: `MigrationTest`,
  `HealthConnectRecorderTest` (write once, the routine title, vitals, no
  opt-in, a revoked permission, unavailable), `SyncMappingHealthTest`,
  `HealthConnectSegmentTypesTest`. Server: `HealthColumnsTest`, and MCP
  history in `McpTest`. Flows: `HealthConnectFlowTest`.

## In-app AI (BYOK)

Issue #46, step 12 of `docs/architecture.md` ("AI features"), BYOK mode
only: the three MCP prompts ("Suggest a program", "Next workout", "Where have
I got stuck") as buttons on the phone, answered by the user's own provider
with the user's own key. **There is no server-side key, no operator key and no
free budget**: the call goes from the phone straight to the provider, and
tung-server never sees the key or the request.

- **core, `core/.../core/ai/`** (pure, tested): `AiRequest` (`Program`,
  `NextWorkout`, `Plateaus`); `AiContext`, built by `buildAiContext` from plain
  shapes: finished workouts of the last 90 days (at most 30, logged sets only,
  exercise key and name), routines, programs, today's date and weekday, the
  core catalogue's keys, unit kg. The shape has no field for an email, a name,
  a device id, a server URL or a note, so none can be sent. `buildAiPrompt`
  (English, framed like the server's MCP prompts, the answer's JSON shape in
  the system message, the app's language for the text fields).
  `parseAiAnswer` + `resolveAiAnswer`: a program is routines in the shape
  `SaveRoutinePlan` takes (`ExerciseRef.ByKey` when the key is in the
  catalogue, else `ByName`), a next workout one `WorkoutPlan`, plateaus
  `{exercise, since, observation, suggestion}`; numbers outside `validate`'s
  bounds and exercises that resolve to nothing are **dropped** (counted, not
  shown), and a plan left empty is rejected. `AiWire.kt`: the OpenAI-compatible
  (`POST {base}/chat/completions`, `response_format` json when enabled) and
  Anthropic (`POST {base}/v1/messages`, `x-api-key`, `anthropic-version`)
  formats as pure request builders and response readers, and
  `checkAiBaseUrl` (the sync rule: https, or http only to localhost and
  private IP literals, for Ollama on the home network).
- **data, `data/.../ai/`**: `AiSettings` (provider, base URL, model, JSON
  mode, "first request shown" and "always show" flags in the `settings`
  DataStore; the key in EncryptedSharedPreferences `ai_key` through
  `TokenStore`, excluded from backups like `sync_token`); `AiClient` (its own
  Ktor client: 60 s, no retries, no redirects, statuses mapped to
  `AiFailure`); `AiService` (`prepare` builds the context and the request
  without the key, `send` sends exactly that body with the key added to the
  header, `confirm` runs the commands, `test` sends a one-token request with
  no training data). `TungContainer(createAiKeyStore = …)` is the seam;
  `FlowTestApp.aiKeys` keeps the key in memory.
- **app**: the Assistant screen (`ui/ai/`), reached from the **last row on
  Home**; Settings' "Assistant" section is `ui/settings/AiSection.kt`, one
  call in `SettingsScreen`. A program proposal is confirmed with "Save
  program": `SaveRoutinePlan` per routine, then `CreateProgram` (rotating)
  and `AddProgramDay` in order; the program is not activated. A next workout
  is `ApplyPlan`. Plateaus are only shown.

**Non-negotiable rules.** A call happens only on a tap, never in the
background (no WorkManager, no retry). The first request ever is shown in
full ("What is sent", the pretty-printed body and the URL; the key is in a
header and never shown) before it is sent, and the menu's "Show what is sent"
reviews every request while it is on. What is sent is training data and
settings only. A suggested exercise that is not in the catalogue is dropped,
not shown. Nothing is written until the user confirms, and then only through
`container.commands`. The key is never in DataStore, a log or a sync.

## Voice

Issue #48, the first half of step 13 of `docs/architecture.md` ("Voice"):
one set at a time, spoken in the active workout, with no model and no
network. The grammar is tried first and never guesses; the assistant is the
fallback, and only on a tap.

- **Grammar in core, `core/.../core/voice/`** (pure, tested):
  `parseUtterance(text, VoiceLanguage)` → `VoiceIntent`: `SetWeightAndReps`,
  `AdjustWeight(deltaKg)`, `AdjustReps(delta)`, `SameAgain`, `Next`, `AddSet`,
  `Done`, `Finish`, `Rest`, `Unknown(text)`. `VoiceLanguage.of(tag)` from the
  app's locale; each language has its own words (`VoiceGrammar.kt`), and the
  numbers (`SpokenNumbers.kt`) are digits, "82,5", or words up to 999
  ("sjuttiofem", "hundratjugo", "seventy-five", "two and a half", "åttio komma
  fem"). Normalised like `normalizeExerciseName` but with the diacritics kept.
  One number needs a unit ("80 kilo", "8 reps") or a half ("82,5" is a
  weight); a bare "80" is `Unknown`. Two numbers are weight then reps ("80 8",
  "80 kg 8", "80 x 8"), or reps then weight when both carry their unit.
  "plus/minus/upp/ner/up/down/add" + a number is kilos unless it says reps.
  **Never guess:** number words written apart are tried both joined and not
  ("åttio åtta", "one hundred twenty-five"), and an utterance with more than
  one reading is `Unknown` ("one hundred twenty-five reps" is also 100 × 25).
  Weights 0–999 in steps of 0.5, reps 0–999, else `Unknown`.
  `VoiceGrammarTest` is the table (over 100 utterances) and checks every
  spoken number 0–200 in both languages; a new phrase goes in the table.
- **Speech, `app/.../voice/`:** `SpeechInput.listenOnce(locale)` is a Flow of
  `Listening`, `Partial`, then one `Result` or `Error(kind)`.
  `AndroidSpeechInput` wraps `SpeechRecognizer` (`ACTION_RECOGNIZE_SPEECH`,
  `EXTRA_LANGUAGE` from the app locale, `EXTRA_PREFER_OFFLINE`,
  `EXTRA_PARTIAL_RESULTS`) and answers `UNAVAILABLE` when
  `isRecognitionAvailable` is false, which on Android 11+ needs the
  `<queries>` entry for `android.speech.RecognitionService` in the manifest.
  The factory is `TungApp.createSpeechInput()`, an open function the flow
  tests' `FlowTestApp` overrides with `FakeSpeechInput` (`say`, `fail`); it is
  deliberately not a `TungContainer` seam, since nothing in `data/` hears.
  `RECORD_AUDIO` is asked for the first time the mic is used.
- **The mic, `ui/workout/`:** a round button left of Log set, as tall as it,
  accent outline, filled while listening (`VoiceControls.kt`). `voiceAction`
  (`VoiceActions.kt`, pure, `VoiceActionsTest` without Robolectric) decides
  what an intent does on the current set from the UI state; the screen runs it
  through the ViewModel's own functions, so every write is the usual command:
  values are `SetWeight`/`SetReps` (an adjustment added and clamped to
  0–999), `Done` is exactly the Log set button (`LogSet`, rest timer, or the
  reps sheet when the set has none), `SameAgain` copies the set before it in
  the same exercise and logs (with none before it, nothing: last session is
  not guessed), `Next` selects the set a tick would move to without logging,
  `AddSet` is `AddSet`, `Rest` starts the rest timer, `Finish` opens the finish
  dialog. `applyVoice` runs the writes and the log in one coroutine on
  `viewModelScope`. The echo line above the buttons shows what was understood
  ("80 × 8 logged") or not ("Didn't catch that: '…'") for 3 s, the partial
  text while listening.
- **Hands-free** (long press on the mic): `VoiceController` listens again 1 s
  after each result and shows the "Listening" chip; a `CONFIRM` haptic
  (`CLOCK_TICK` below Android 11) per intent that changed something. It stops
  on a tap on the chip or the mic, on leaving the screen (it runs on the
  screen's composition scope) or the app going to the background (`ON_STOP`),
  on "finish workout", on a fatal error (no recogniser, no permission) and
  after three other errors in a row. Silence is not an error: between sets the
  phone mostly hears nothing. No foreground service; the screen is already
  kept on.
- **The assistant link:** on `Unknown` with the assistant set up
  (`AiConfig.configured`), the echo offers "Ask the assistant" for 8 s. It
  opens the Assistant screen with the transcript (`assistant?ask=…`), which
  asks `AiRequest.FreeText(text)` once: the next-workout schema and parser,
  the transcript as a JSON string in the question. The rules of "In-app AI
  (BYOK)" hold unchanged: the request goes on that tap, the first one is shown
  in full first, and "Start this workout" applies the plan with `ApplyPlan`.

## Server

`server/` is the Gradle module for tung-server, the optional backend
(`docs/architecture.md`, steps 5 and 6). Package
`io.github.kanylbullen.tung.server`, run with `./gradlew :server:run` (needs
`DATABASE_URL`; see `server/README.md` for the environment), shipped as
`server/Dockerfile` with `deploy/docker-compose.yml`.

- **Stack:** Ktor 3.0.3 on Netty (content negotiation with kotlinx JSON,
  bearer auth, call logging, status pages, CORS, rate limit, Swagger UI),
  Exposed 0.56 DSL (not DAO) over HikariCP and Postgres 17, Flyway 10
  migrations in `src/main/resources/db/migration/` run on start, Argon2id from
  Bouncy Castle (pure Java, no native library in the image), logback.
  Bytecode 17, run on JRE 21.
- **Depends on `:core` and never on `:app`.** Domain rules come from core:
  the catalogue (`CoreCatalog.parse` of `catalog/core.json`, copied into the
  classpath as `/catalog/core.json` by `processResources`, so there is one
  file), ids (`catalogExerciseId`), `isLogged` for finish, and
  `core.command.validate` for weight, reps, rest and names, so the API accepts
  exactly what a command does. Anything the server needs from the app moves
  into core first.
- **Data is keyed by `(user_id, id)`**, never `id` alone, with the client's
  ids: one user can never write into another's rows, and another user's id is
  a 404. Catalogue exercises have `user_id NULL`; a user's row with a
  catalogue id is their copy (made when they set its rest time) and shadows
  the catalogue for them. Every user table has `updated_at` and `deleted_at`.
  Routines and programs (V2) mirror the app's entities; sync, the command
  handler and their REST routes write them, the export, the REST routes and
  the MCP read tools read them.
- **The row rule:** a write replaces a stored row only when its `updated_at`
  is strictly newer; equal or older is ignored per row (`upsert … where
  updated_at < incoming`). `PUT /api/v1/workouts/{id}` applies it to the
  workout, each exercise and each set separately, and tombstones stored
  children missing from the body only when they are older than the body's
  workout `updated_at`. Deletes are tombstones, never `DELETE`. Server-made
  changes (finish, delete, PATCH) stamp `max(now, stored + 1)`. Sync uses
  exactly this rule; do not add a write path that bypasses it.
- **Routines and programs over REST** (`routes/RoutineRoutes.kt`,
  `routes/ProgramRoutes.kt`, writes in `RoutineStore.kt`, issue #42):
  `GET/POST /api/v1/routines`, `GET/PUT/DELETE /api/v1/routines/{id}` and the
  same for `/programs`, nested like the workouts API (exercises, days).
  `POST` and `PUT` are the row rule exactly as `PUT /workouts/{id}`: per
  routine and per routine exercise (program and day), missing children
  tombstoned only when older than the body's parent `updated_at`; `POST`
  takes the body's id or makes one and is 409 `already_exists` for an id the
  user has or had; a missing `last_used_at` keeps the stored one; exercise
  ids must be visible (`unknown_exercise`), day routines live
  (`unknown_routine`). `DELETE` is core's `delete_routine`/`delete_program`
  through the command handler, so the cascades (routine exercises, the
  program days that use a routine) are the app's and there is no third
  write path. Writes call `ChangeNotifier.changed` (the handler does it for
  deletes).
- **At most one active program, across devices.** Two devices can each
  activate a program offline and push `is_active = true`.
  `resolveActivePrograms` (`RoutineStore.kt`) runs inside the sync
  transaction after a push that carried programs, and after `POST`/`PUT
  /programs`: of the user's live active programs the newest `updated_at`
  stays active (a tie goes to a row just written, then the lowest id) and the
  others get `is_active = false` with `GREATEST(now, updated_at + 1)`, so the
  trigger gives them a new `seq` and the losing device gets the correction
  on its next pull (or in the same answer when its own push lost).
- **Sync** (`POST /api/v1/sync`, `SyncStore.kt`, validation in
  `SyncValidation.kt`, protocol types in `core/.../sync/SyncProtocol.kt`,
  shared with the app): one transaction per request. Every pushed row goes
  through the row rule (newer `updated_at` wins, equal or older is ignored
  and the stored row is sent back); a tombstone (`deleted_at` set) sets
  `deleted_at` and `updated_at` on an older stored row and keeps it, and is
  dropped when the server never had the row. The answer is every row of the
  user's with `seq > cursor` plus the stored version of every pushed row, and
  `cursor` = the highest `seq` in it. Plain JDBC batches, not Exposed, for
  speed (10 000 sets in about 3 s). An exercise row with a catalogue id is
  the user's copy and takes only `rest_seconds`.
- **`seq`:** one sequence, `sync_seq`, stamped on every insert and update of
  the eight synced tables by a trigger (`bump_sync_seq`, V2), so every REST
  write is synced without code of its own; index `(user_id, seq)` on each.
  **Every transaction that writes training data starts with
  `lockUser(userId)`** (a transaction-scoped advisory lock), and so does a
  sync: sequence values are taken in call order, not commit order, and
  without the lock a client could pull seq 11 before seq 10 commits and never
  see 10. A new write path must take the lock too.
- **Foreign keys between training-data tables are `DEFERRABLE INITIALLY
  DEFERRED`** (V2), so the order of rows in a sync request does not matter.
  `SyncStore` checks parents (and exercise ids, which are not foreign keys,
  because a catalogue row has `user_id NULL`) before commit and answers 409
  `missing_parent` naming the rows; the commit-time check is the backstop.
- **Every route is in `src/main/resources/openapi/openapi.yaml`** (OpenAPI
  3.1, hand-written, served at `/openapi.yaml` and as Swagger UI at `/docs`).
  `OpenApiCoverageTest` walks the routing tree and fails on a route missing
  from the spec or an operation the server lacks. Change the DTOs
  (`Dto.kt`, snake_case on the wire through `ApiJson`) and the spec together.
- **Errors** are thrown as `ApiException` (`badRequest`, `notFound`, …), never
  responded by hand, so they come out as `{"error": {"code", "message"}}` and
  StatusPages' handlers for Ktor's own 401/404/405/429 do not replace them.
- **Tokens:** `tung_` + 32 random bytes, SHA-256 in `api_tokens`; sessions
  are tokens named `session`. A `read` token gets 403 on anything but GET.
  `api_tokens.expires_at` (V5) is null except on OAuth tokens; `authenticate`
  refuses expired ones.
- **Sign in with Google** (issue #51; `auth/GoogleIdTokens.kt`,
  `Accounts.googleUser`, V10): only with `TUNG_GOOGLE_CLIENT_ID` (comma-separated
  ids; the first is what `GET /api/v1/auth/methods` hands clients; without it
  `/auth/google` is 404 `not_configured` and nothing Google is shown). An ID
  token is verified by hand with `java.security`: RS256, Google's issuer,
  audience in the ids, `exp` with 60 s leeway, `iat`, the signature against
  the JWK of its `kid` from `ServerConfig.googleJwksUrl` (cached per
  `max-age`, else 1 h; an unknown kid refetches, at most once a minute);
  failures are 401 `invalid_google_token`, an unreachable key set 503.
  **Linking rule:** by `users.google_sub`; else, only when `email_verified`,
  the user with that email gets the `google_sub` (and keeps the password);
  else a new user with `password_hash NULL`. An unverified email linked to
  nothing is 401 `email_not_verified`; never link on it. A null
  `password_hash` refuses password login after the same Argon2 work as a
  wrong password (`verifyCredentials`). `POST /api/v1/auth/google` answers
  what `/login` answers (`Accounts.session`); `/me` has `has_password` and
  `google_linked`. **The consent page** renders the GIS button (its CSP and
  referrer policy widen only then); GIS posts to `POST /oauth/google`, which
  checks `g_csrf_token` (cookie = field) and the button's `state` (the
  authorization request, HMAC-signed by `GoogleConsent` with a per-process
  key, because our SameSite=Strict CSRF cookie is not sent on Google's
  cross-site POST), resolves the user the same way and renders the consent
  step with a `google_ticket` (HMAC, ten minutes, bound to the CSRF token
  and client) that `POST /oauth/authorize` takes instead of the password.
  Tests: `GoogleSignInTest` (a test RSA key and a JWK set on a JDK
  `HttpServer`; `ServerConfig.clock` exists for expiry).
- **Link codes** (`routes/LinkRoutes.kt`, `link_codes` in V4): `POST
  /api/v1/link/start` (write token) gives a six-digit code valid ten minutes,
  unique among unexpired codes; `POST /api/v1/link/claim {code}` (no auth)
  claims it once and answers a new `write` token named `watch` and the user,
  else 404 `invalid_code`. 5 codes a minute per user, 10 claims a minute per
  address, counted in that file rather than by the RateLimit plugin.
- **The server-side command handler** (`command/ServerCommandHandler.kt`,
  built per user by `CommandService`) is the server's twin of the app's
  `AppCommandHandler`: core's `validate`, then **one transaction** starting
  with `lockUser`, names resolved by core over `ServerExerciseResolver`
  (catalogue rows shadowed by the user's own, `core.json`'s names in every
  language, the extended catalogue from `/catalog/extended.json`), and the
  app's rules (one workout in progress, log set adds a set only after the
  last and not past a routine's target, finish/delete via `WorkoutStore`'s
  `finishIn`/`deleteIn`, pre-fill from `lastSessionIn`, i.e. `/last`'s query).
  Its own changes stamp `GREATEST(now, updated_at + 1)`; deletes are
  tombstones. Unlike the app it answers `Rejected` for ids that do not exist,
  and refuses `log_set` on a set with 0 reps (the app opens the reps sheet).
  **Every REST command (`POST /api/v1/commands/{name}`, `routes/CommandRoutes.kt`)
  and every MCP tool that changes data goes through it**; do not write
  training data from a route or a tool any other way. The JSON of each
  command, its JSON Schema and the result's JSON are core's `CommandWire`
  (`core/command/CommandWire.kt`), one table for all three.
- **OAuth 2.1** (`oauth/`, `routes/OAuthRoutes.kt`, `V5__oauth.sql`): the
  server is its own authorization server for MCP clients. Tables
  `oauth_clients` (dynamic registration, redirect URIs as a JSON array,
  matched exactly), `oauth_codes` (SHA-256 of the code, bound to client,
  redirect URI, user, scope and the S256 challenge; five minutes, single use;
  a reused code revokes the token it gave) and `oauth_refresh_tokens`
  (SHA-256, rotating, `replaced_by`; a rotated one presented again revokes
  the grant). **An access token is an `api_tokens` row** named
  `mcp:<client name>`, scope `write` for `read write` and `read` for `read`,
  `expires_at` 30 days ahead; a refresh gives the same row a new hash and 30
  more days. Revoking that row ends the grant (refresh checks it). The issuer
  is `TUNG_PUBLIC_URL`, else the request's own host (`publicBaseUrl`). OAuth
  errors are `OAuthException` (RFC 6749 shape), handled in `Errors.kt`.
  **Unused clients expire** (V8, issue #42): `oauth_clients.last_used_at` is
  stamped (`OAuthStore.markUsed`) on every `/oauth/authorize` call naming the
  client and every `/oauth/token` call it authenticates; a coroutine on the
  application scope (`Application.module`, cancelled at `ApplicationStopping`)
  runs `sweepUnusedClients` at start and every 24 h. It deletes clients whose
  `COALESCE(last_used_at, created_at)` is over 90 days old and that have no
  live grant: no unrevoked `api_tokens` row that is unexpired or has a
  working refresh token, found through `oauth_refresh_tokens` (which names
  both the client and the token row; `mcp:<client name>` is not unique).
  Codes and refresh tokens cascade; the expired API tokens stay in the
  user's list. `sweepUnusedClients(now)` takes the time, so a test ages a
  client by passing a later `now` (`OAuthClientExpiryTest`).
- **MCP** (`mcp/`, `routes/McpRoutes.kt`): the official Kotlin SDK 0.5.0 for
  the protocol types and `Server` (0.6+ needs Ktor 3.2 and Kotlin 2.2; its
  Ktor engines are excluded), with streamable HTTP implemented here: one SDK
  `Server` and one `ExchangeTransport` for all requests, because the SDK
  handles a message inline, so `McpExchange` in the coroutine context carries
  the request's principal in and the responses out. Sessions
  (`Mcp-Session-Id`) are in memory with a 24 h idle TTL; GET is 405. The
  initialize handler is ours, to negotiate the protocol version. Tools: one
  per `CommandNames` entry (descriptions in `McpDescriptions`, which a test
  keeps complete; the commands table and the name enum of
  `/api/v1/commands/{name}` in `openapi.yaml` list the same names) plus the
  read tools in `McpReads`; a read token gets a tool
  error from a command tool.
- **Tests** (`./gradlew :server:test --no-daemon`) use a real Postgres from
  Testcontainers, so they need Docker; `./gradlew test` includes them.
  `server/build.gradle.kts` sets `api.version=1.44` for docker-java, because
  Docker Engine 29 rejects the older API version Testcontainers 1.20 asks for.
  Each test registers its own users instead of cleaning the database.

## Building

```sh
cd /home/xup/tung
export JAVA_HOME=/home/xup/android/jdk ANDROID_HOME=/home/xup/android/sdk ANDROID_SDK_ROOT=/home/xup/android/sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug test --no-daemon
```

`test` runs `:core:test`, the unit tests of `data`, `app` and `wear` (AGP
runs them for both the debug and the release variant) and `:server:test`.
Only the core tests, fast and without AGP: `./gradlew :core:test --no-daemon`.
Results land in `core/build/test-results/test/` and
`<module>/build/test-results/testDebugUnitTest/`.

APKs: `app/build/outputs/apk/debug/app-debug.apk` and
`wear/build/outputs/apk/debug/wear-debug.apk` (`:wear:assembleDebug`). The first build downloads
dependencies and takes a few minutes.

## Flow tests

`app/src/test/kotlin/io/github/kanylbullen/tung/flows/` drives the real
screens: `MainActivity`, the nav graph, the ViewModels and a real Room
database, held in memory. They run on the JVM under Robolectric (SDK 34,
native graphics) with Compose UI testing — no emulator, no device — and are
part of `./gradlew test`. Only them:

```sh
./gradlew :app:testDebugUnitTest --tests 'io.github.kanylbullen.tung.flows.*' --no-daemon
```

- `FlowTestApp` (named in `src/test/resources/robolectric.properties`)
  overrides `TungApp.createContainer()` to hand `TungContainer` an in-memory
  `TungDatabase.buildInMemory`, same schema and seed, and a settable
  `Clock` (`FlowTestApp.today`) for Home's "Today", and a `TokenStore` in
  memory (`FlowTestApp.tokens`), because Robolectric has no Android Keystore,
  and the push transports (`FlowTestApp.push`, a `FakePushEnvironment` from
  data's test fixtures, none by default), and Health Connect
  (`FlowTestApp.healthConnect`, a `FakeHealthConnect` from data's test
  fixtures: available, nothing granted, nothing to read by default), and the
  assistant's API key in memory (`FlowTestApp.aiKeys`). Those six
  `TungContainer` parameters are the only test seams in production code. Sync tests talk to `FakeSyncServer` (data's test fixtures,
  `data/src/testFixtures/.../sync/`, the protocol and the link routes on a
  MockWebServer), and `SyncFlowTest` installs WorkManager's test
  instance (`WorkManagerTestInitHelper`) before it signs in.
- Assertions are on semantics, with text from the English string resources
  (`str(R.string.…)`), never on pixels. Room works on its own threads, so wait
  (`waitForText`, `assertShown`) rather than assert right after a tap.
- **Wait with `FlowTest.waitUntil`, never `compose.waitUntil`** (issue #28).
  Under Robolectric the test thread is the main thread and the main looper
  runs only when the test idles it; `compose.waitUntil` just advances
  Compose's clock and sleeps. Work that hops to a background thread and back
  to Main (a DataStore `edit` runs its transform on the caller's dispatcher,
  Room hands the transaction thread back to the caller) and returns after
  the last click or `onAllNodes` stays queued for the whole wait when the
  condition reads only the database or an alarm, however long the timeout.
  `waitUntil` idles the looper before every check, and on timeout prints
  every thread's stack and whether the looper had work left.
- Each test also saves PNGs of the screens it passes through to
  `app/build/reports/flow-screenshots/<test>-<step>.png`; CI uploads them as
  the `flow-screenshots` artifact. Only `testDebugUnitTest` writes them (the
  release variant runs the same flows), and the directory is a declared task
  output, so a run restored from the build cache restores them too. They are
  drawn with `View.draw`, because `captureToImage()` times out under
  Robolectric on API 34.
- **A dialog with a text field needs `DialogProperties(usePlatformDefaultWidth = false)`**
  (plus `Modifier.padding(horizontal = 24.dp)`). With the platform width,
  Compose never goes idle under Robolectric at the flow tests' `w411dp-h891dp`,
  so every interaction times out after 60 s with `AppNotIdleException`.
  `RoutineNameDialog` does this; the create-exercise dialog does not yet, and
  no flow test opens it.
- **A UI change that breaks a flow test is a UI bug until proven otherwise.**
  Change the test only when the behaviour change is intended, and say so in
  the commit.

## Design: the numbers are the content

The app is used with one hand, between sets, in poor light, with tired eyes.
Weight and reps are the only things that matter; everything else is scenery.

- Set rows: numbers ~28sp, bold, tabular (`tnum`). Input sheet: 96sp.
- One bold move: the input sheet (huge number, thumb-sized −/+, "Done").
  The rest is calm — no cards in cards, no icons that do nothing, no
  dividers where space is enough, no all-caps labels, no illustrations.
- One corner radius: 12dp (`TungCorner`) on buttons, sheets and dialogs. 0 on list rows.
- Motion only in response to an action (the check fills, the sheet slides up).
- The screen is kept on during the active workout (`view.keepScreenOn`).
- Dark/light follows the system. Dynamic colours are **off**.
- Typeface: Archivo (variable, OFL) — one family.

The ruler under the big number (`RulerPicker`, scale in `core/Ruler.kt`) is
the same gesture as a slider with usable precision. Its scale is piecewise
(`RulerScale` = a list of `RulerSegment(from, step, labelEvery)`): weight has
a tick every 1 kg from 0 to 30 kg (labelled every 5), then every 2.5 kg up to
500 kg (labelled every 10); reps a tick every rep from 0 to 100 (labelled
every 5). Every tick has the same width on screen, so the ruler's position is
in ticks and maps linearly to the value within a segment. A fixed accent line
sits in the centre; horizontal drag only, so vertical swipes still reach the
sheet. Dragging moves the big number live, one `CLOCK_TICK` haptic per step;
release snaps to the nearest tick and a fling decelerates onto one (the decay
runs in tick space). A **tap** (press and lift within the touch slop) jumps to
`RulerScale.jumpTarget` for the value under the finger: the nearest 10 kg, or
the nearest 5 reps, and exactly between two, the one nearer the current value.
**−/+ follow the local step** (`stepUp`/`stepDown`, via `core.stepWeight`):
1 kg below 30, 2.5 kg from 30 up, so 29 → 30 → 32.5 and back; an off-grid
value goes to the next point on the grid, and past 500 the 2.5 kg step carries
on to 999. Their screen-reader descriptions name the step at the current
value. **Typing wins:** typing or −/+ moves the ruler to the
value (between ticks if off the grid, pinned at the end if past it), and the
ruler never writes back what was typed; only a drag or a tap writes, always on
the grid. "Apply to all sets" is a switch in the sheet, off every time it opens,
shown only when another un-ticked set of the exercise exists. With it on,
Done writes the value to the edited set and to every un-ticked set of that
exercise (`WorkoutRepository.update…ForOpenSets`); ticked sets stay as
logged. Weight and reps are applied independently.

| Token | Dark | Light | Used for |
|---|---|---|---|
| background | `#1A1D21` | `#F6F6F4` | Background, list rows |
| raised | `#23272D` | `#FFFFFF` | Sheets, dialogs, bottom bar, secondary button |
| border | `#2F343B` | `#E2E1DC` | Borders, swipe background |
| text | `#ECE7DD` (chalk) | `#1A1D21` | Primary text |
| muted | `#9AA0A6` | `#5F6368` | Muted text, "Last:" |
| accent | `#F2C230` | `#B98900` | Fill: primary button, ticked set |
| accentText | `#F2C230` | `#8A6500` | Accent as text: "New record", "Finish workout" |

Contrast (WCAG): chalk/graphite 13.8:1, yellow/graphite 10.1:1, muted/graphite
6.4:1, muted/raised 5.7:1. In light mode `#B98900` only reaches 3.2:1 against
white, so it is used only as a fill (dark text on it: 5.4:1); as text,
`#8A6500` is used (4.9:1 against `#F6F6F4`).

Copy (English, and the same in each translation): active voice, only the first
letter capitalised. Buttons say what happens ("Start workout", "Add exercise",
"Save"). Errors say what happened and what to do, without "oops".

## Traps

- **IPv6/Maven:** Gradle cannot reach Maven over IPv6 here.
  `gradle.properties` has `-Djava.net.preferIPv4Stack=true` both in
  `org.gradle.jvmargs` and as a `systemProp`. Do not remove them.
- **`local.properties`** with `sdk.dir=/home/xup/android/sdk` must exist and
  be gitignored. Without it AGP finds no SDK.
- **One gradle process at a time.** The machine is shared; never run parallel
  builds, and use `--no-daemon` so that no daemon is left behind. `/tmp` is
  tmpfs — put nothing large there.
- **The wrapper is 8.11.1** and already downloaded. Do not run `gradle wrapper`
  with another version.
- **Strings only in resources.** No hard-coded strings in composables;
  everything goes in `res/values/strings.xml` (English, the default) and
  `res/values-sv/strings.xml` (Swedish), with the same names in both. The
  exercise names too — see "Exercise names are resources".
- **`res/font/` takes only font files.** That is why the OFL licence is in
  `assets/licenses/`, not next to the `.ttf` file.
- No `!!`, no `GlobalScope`, no blocking DB calls on main. The DAOs are
  `suspend` or `Flow`.
- **A schema change without a migration crashes every installed phone** on
  open. Version + 1, the committed schema JSON, a `Migration` and a
  `MigrationTest` case, always; see "Sync", "Schema".
