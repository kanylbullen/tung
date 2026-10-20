# Tung — agent manual

Strength training log for Android. A local app: no backend, no login, no
network. Everything lives in Room on the phone.

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
Gradle 8.11.1 (wrapper). compileSdk/targetSdk 35, minSdk 26.

**No Hilt/Koin.** `AppContainer` in `TungApp.kt` holds the database and the
repositories lazily. ViewModels are created with `viewModel(factory = factoryOf { ... })`
(`ui/common/Containers.kt`) and get repositories (for reads), `container.commands`
(for every change, see "Commands") and nav arguments in the constructor;
the active workout's factory also passes `createSavedStateHandle()`, for the rest timer.

## Layers

Two Gradle modules: `core/` (plain Kotlin on the JVM) and `app/` (Android),
with `app` depending on `project(":core")`.

### `core/` — domain logic without Android

`core/src/main/kotlin/io/github/kanylbullen/tung/core/`, package
`io.github.kanylbullen.tung.core`. A plain Kotlin JVM module
(`org.jetbrains.kotlin.jvm`, bytecode 17 like the app), so that a future Ktor
server and a Wear OS app use the same code as the phone. It is **not** Kotlin
Multiplatform; that is a later step if a non-JVM client ever needs it.

What belongs there: pure functions and plain data shapes that are calculated
the same on every client — volume, best set, records, pre-fill (`LoggedSet`,
`isLogged`), routine targets and start sets (`Routines.kt`), the program day suggestion (`Programs.kt`), weight/reps parsing and formatting, diacritic-insensitive search,
`MuscleGroup`/`Equipment`, the id helpers (`Ids.kt`), the rest timer and the
current set (`RestTimer.kt`, `LogSet.kt`), and the catalogue models
with their JSON parsing (`CoreCatalog`/`CoreExercise` for `core.json`,
`ExtendedCatalog`/`ExtendedExercise` for `extended.json`, including the
extended-catalogue search), and the command surface (`core/command/`, see
"Commands"). Core gets the catalogue as a `String` or an
`InputStream`; where the bytes come from (APK assets, a file) is the caller's
business. Room entities stay in the app and map to core's shapes
(`SetEntry.toLogged()`); core never depends on Room.

**The rule: no `android.*` or `androidx.*` imports in `core/`.** Anything that
needs a `Context`, resources, Room or Compose stays in the app; if a function
is mixed, split it and move the pure part. `NoAndroidImportsTest` (in
`core/src/test`) scans `core/src/main` and fails on such an import. Core's
only dependencies are `kotlinx-serialization-json` and
`kotlinx-coroutines-core`.

### `app/` — the Android app

| Directory | Contents |
|---|---|
| `data/` | Entities (`Exercise`, `Workout`, `WorkoutExercise`, `SetEntry`, `Routine`, `RoutineExercise`, `Program`, `ProgramDay`), `ExerciseDao`, `WorkoutDao`, `RoutineDao`, `ProgramDao`, `TungDatabase` (seeds the core catalogue's 70 exercises in `onCreate`), `ExerciseRepository`, `WorkoutRepository`, `RoutineRepository`, `ProgramRepository`, `ExtendedCatalogAssets.kt` (`ExtendedCatalog.fromAssets(context)`), `AppCommandHandler` and `AppExerciseResolver` (see "Commands"). `SeedExercises.kt` is generated. |
| `timer/` | The rest timer's Android side: `RestTimerController` (DataStore + alarm), `RestAlarm`, `RestTimerReceiver`, `RestAlerts` (vibration + notification). |
| `ui/<screen>/` | `Screen` + `ViewModel` + `UiState` per screen: `home`, `workout` (active workout, the input sheet, the rest sheet), `exercises` (tab and picker in one), `history` (list + detail), `summary`, `routines` (list + one routine, the row and the name dialog that Home, summary and detail share; programs list + one program). |
| `ui/common/` | Formatting, shared components, `WorkoutReport`, `ExerciseNames` (displayed exercise names). |
| `ui/theme/` | Colour tokens, typography, theme. |
| `../catalog/` | The exercise catalogue as open data (CC0): `core.json`, `extended.json`, generator and import scripts. Shipped as an asset directory in the APK. See `catalog/README.md`. |

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
also sets `lastUsedAt`, which orders Home's Routines section. There is at most
one workout in progress, so `start` returns that one instead of creating a
second, and the Start buttons are disabled while it exists.
`Workout.routineId` is indexed but deliberately **not** a foreign key:
deleting a routine (which cascades to its `RoutineExercise` rows) never
touches history.

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
the same transaction.

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
`LocalDate.now(AppContainer.clock)`: the system clock in the app, a settable
one (`FlowTestApp.today`) in the flow tests. Programs are reached from a row
at the top of the Routines screen (`ui/routines/Program*`).

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
started (`TungApp.isInForeground`). With the workout on screen the countdown
reaching zero calls the same function, so the alert is on time even with an
inexact alarm; it alerts once per `endsAt`. Tapping the notification opens
`MainActivity` with `EXTRA_OPEN_WORKOUT`, which `TungAppRoot` navigates to. No
foreground service. `POST_NOTIFICATIONS` is asked for once, the first time a
timer starts on Android 13+ (flag in DataStore); denied, the countdown and the
vibration still work.

The DataStore is built in `AppContainer` with `PreferenceDataStoreFactory`,
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
every insert and update; screens and ViewModels never touch it. Why:
preparation for sync with a server, and export/import between devices without
id collisions. No query may sort on id — order comes from `position` or
timestamps.

### Exercise names are resources

Catalogue exercises have a stable key in `Exercise.nameKey` (`bench_press`,
`back_squat` …) and are shown via the string resource `ex_<nameKey>`, so the
name follows the phone's language. `name` is stored anyway, as the English
fallback. Custom exercises have `nameKey = null` and show what the user typed.
Everything that shows an exercise name goes through
`exerciseDisplayName(nameKey, name)` or `Exercise.displayName(context)` in
`ui/common/ExerciseNames.kt`, which looks up each key with `getIdentifier` once
and caches the id. Search in the picker matches the **displayed** name
(`core.filterByDisplayName`), never `name` in SQL.

**The source of truth is `catalog/core.json`**, not Kotlin or the strings.
`data/SeedExercises.kt` and the `ex_*` lines between `<!-- catalog:begin -->`
and `<!-- catalog:end -->` in both `strings.xml` are generated by
`catalog/tools/gen_android.py`; never edit them by hand. Adding an exercise =
add the entry to `core.json` (sorted by `key`, `en` and `sv` required) and run
`python3 -I catalog/tools/gen_android.py`; three files are updated
(`SeedExercises.kt`, `values/strings.xml`, `values-sv/strings.xml`) and all
four are committed. `--check` writes nothing and exits with 1 if anything is
out of date. `CatalogTest` (in `core`) checks `core.json` and `extended.json`
as data, and `SeedExercisesTest` (in `app`) compares `core.json` with the seed list.
English names are gym English (Lat pulldown, Skull crusher), not literal
translations. A `key` never changes once it exists: it carries the exercise's id.

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
- **One handler.** `data/AppCommandHandler.kt` maps each command onto the
  existing repositories, which keep their transactions and rules. It runs
  `validate(command)` first (pure, in core: weight 0–999 kg, reps 0–999,
  planned sets 1–50, a routine's target sets 1–20, rest 0–3600 s, weekday
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
- **`CommandNames` are stable.** Each command has a snake_case wire name
  (`log_set`, `apply_plan`, …): the future REST route and MCP tool name.
  Clients and saved prompts outside the repository will call them, so a name
  never changes once it exists, like a catalogue key; rename the Kotlin class
  freely, but add a new command rather than rename a wire name. The
  `CommandIds` keys are stable for the same reason. `CommandNamesTest` fails
  when a command has no name (the commands are nested in `Command` so it can
  list them with plain Java reflection).

## Building

```sh
cd /home/xup/tung
export JAVA_HOME=/home/xup/android/jdk ANDROID_HOME=/home/xup/android/sdk ANDROID_SDK_ROOT=/home/xup/android/sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug test --no-daemon
```

`test` runs `:core:test` and the app's unit tests (AGP runs them for both the
debug and the release variant). Only the core tests, fast and without AGP:
`./gradlew :core:test --no-daemon`. Results land in
`core/build/test-results/test/` and `app/build/test-results/testDebugUnitTest/`.

APK: `app/build/outputs/apk/debug/app-debug.apk`. The first build downloads
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
  overrides `TungApp.createContainer()` to hand `AppContainer` an in-memory
  `TungDatabase.buildInMemory`, same schema and seed, and a settable
  `Clock` (`FlowTestApp.today`) for Home's "Today". Those two
  `AppContainer` parameters are the only test seams in production code.
- Assertions are on semantics, with text from the English string resources
  (`str(R.string.…)`), never on pixels. Room works on its own threads, so wait
  (`waitForText`, `assertShown`) rather than assert right after a tap.
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
- `exportSchema = false` and no migration: if the schema changes, `version`
  must be raised and a `Migration` written before the app is used for real.
  Until the first release the schema changes at `version = 1` (last:
  `Program`, `ProgramDay` and `Workout.programDayId`), so a dev install must
  be wiped (uninstall or clear data) after pulling such a change, or Room
  throws on open.
