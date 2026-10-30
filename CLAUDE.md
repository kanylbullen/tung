# Tung — agent manual

Strength training log for Android. Local first: everything lives in Room on
the phone and the app works fully without a network or an account. Sync with
an optional tung-server is opt-in, from Settings → Account. A web app
(`web/`, see "Web") runs the same `core` in the browser as a PWA.

## Language

English is the repository language: code, comments, documentation, commit
messages and pull requests. The app's default locale is English
(`res/values/`), and Swedish (`res/values-sv/`) is the first translation.

Adding an app language:

1. Copy `res/values/strings.xml` to `res/values-<lang>/strings.xml` and
   translate it. Keep exactly the same string names. `ai_language_tag` is
   the new language's tag, and `aiLanguageName` in
   `core/.../core/ai/AiLanguage.kt` gets its English name, or the assistant
   keeps writing English (see "In-app AI (BYOK)").
2. Add `<locale android:name="<lang>" />` to `res/xml/locales_config.xml` and
   the tag to `resourceConfigurations` in `app/build.gradle.kts`.
3. Only if the catalogue gets that language: add `name.<lang>` to the entries
   in `catalog/core.json`.
4. Run `python3 -I catalog/tools/gen_android.py`. It manages the `ex_*` block
   in every `values-<lang>/strings.xml` that has the catalog markers; without
   `name.<lang>` the block stays empty and the exercise names fall back to
   English.

## Stack (decided)

Kotlin 2.2.21 (KSP 2.2.21-2.0.5, KSP2), Jetpack Compose (BOM 2026.05.01: Compose 1.11, the line Compose Multiplatform 1.11 runs on Android; lifecycle 2.9.2),
Material 3, Room 2.8.5 via KSP, Navigation Compose 2.8.5, ViewModel + StateFlow, coroutines, Preferences
DataStore 1.1.7 (small state outside Room). AGP 8.9.1,
Gradle 8.11.1 (wrapper), build-tools 36.0.0 (pinned in the four Android
modules: AGP's default is not compileSdk's). compileSdk 36; targetSdk 36 on
the phone (Play's requirement since 2026-08-31) and 35 on the watch (Play's
Wear OS bar; Android 16's behaviour changes are not wanted there yet);
minSdk 26. For sync only:
Ktor client 3.0.3 (OkHttp engine, kotlinx JSON), WorkManager 2.10.0,
androidx.security-crypto 1.0.0 (the stable release) and lifecycle-process;
for Sign in with Google, Credential Manager (`androidx.credentials` 1.3.0
with its Play services provider) and `googleid` 1.1.1, in `app/` only.

**No Hilt/Koin.** `TungContainer` (in `data/`, built by `TungApp` and by the
watch's `TungWearApp`) holds the database and the repositories lazily. ViewModels are created with `viewModel(factory = factoryOf { ... })`
(`ui/common/Containers.kt`) and get repositories (for reads), `container.commands`
(for every change, see "Commands") and nav arguments in the constructor;
the active workout's factory also passes `createSavedStateHandle()`, for the rest timer, and so does the Routines tab's, for its selection.

## Layers

Seven Gradle modules:

| Module | Kind | Depends on | Holds |
|---|---|---|---|
| `core/` | Kotlin Multiplatform (JVM, JS, iOS klibs) | — | Domain rules, commands, sync and watch protocols |
| `data/` | Kotlin Multiplatform (Android, iOS klibs) | `core` | Room, repositories, the CommandHandler, sync client, settings, rest timer, `TungContainer` |
| `data-fixtures/` | Android library (tests only) | `core`, `data` | The test doubles of data's seams: `FakeSyncServer`, `MemoryTokenStore`, `FakePushEnvironment`, `FakeHealthConnect` |
| `app/` | Android app (phone) | `core`, `data` | The phone's screens, the phone side of the Data Layer |
| `wear/` | Android app (Wear OS) | `core`, `data` | The watch's screens, standalone and companion modes |
| `server/` | Ktor server | `core` | tung-server |
| `web/` | Kotlin/JS app (browser) | `core` | The web app: the log in the browser, its sync, the PWA shell |

Sections "Wear", "Server" and "Web" cover the last three.

### `core/` — domain logic without Android or the JVM

`core/src/commonMain/kotlin/io/github/kanylbullen/tung/core/`, package
`io.github.kanylbullen.tung.core`. A Kotlin Multiplatform module
(`org.jetbrains.kotlin.multiplatform`), so that the phone, the watch, the
server, the web app and, later, an iOS app use the same code. Targets:
`jvm()` (bytecode 17 like the app), `js()` (the browser, for `web/`) and
the iOS app's `iosArm64()` and `iosSimulatorArm64()`. The iOS klibs compile on Linux too
(`kotlin.native.enableKlibsCrossCompilation` in `gradle.properties`; the
Kotlin/Native compiler lands in `~/.konan`), so
`./gradlew :core:compileKotlinIosArm64` here, and CI, prove that
`commonMain` is iOS-clean before there is a Mac; linking a binary or running
a test on iOS needs one. `src/jvmMain` holds the one thing that is
JVM-specific (`browseNameOrder`, the locale's `Collator`), `src/jsMain` the
browser's version of it (`Intl.Collator`, `BrowseJs.kt`), `src/jvmTest`
the tests (JUnit 4, run by `:core:jvmTest`; `:core:test` is an alias so
`./gradlew test` still covers them).

What belongs there: pure functions and plain data shapes that are calculated
the same on every client — volume, best set, records, pre-fill (`LoggedSet`,
`isLogged`), routine targets and start sets (`Routines.kt`), the program day suggestion (`Programs.kt`), weight/reps parsing and formatting, diacritic-insensitive search,
`MuscleGroup`/`Equipment`, the finer `Muscle` list and `MuscleIndex` (`Muscles.kt`), the id helpers (`Ids.kt`), the rest timer and the
current set (`RestTimer.kt`, `LogSet.kt`), and the catalogue models
with their JSON parsing (`CoreCatalog`/`CoreExercise` for `core.json`,
`ExtendedCatalog`/`ExtendedExercise` for `extended.json`, including the
extended-catalogue search), the command surface (`core/command/`, see
"Commands"), the picker's suggestions (`core/picker/`, see "Smart picker"),
the Exercises tab's browse list and the catalogue by id (`core/catalog/`),
the muscle map's shapes (`core/muscles/BodyMap.kt`) and a workout's sets per
muscle (`core/muscles/MuscleWork.kt`, see "Workout summary"), an exercise's history
(`ExerciseHistory.kt`; see "Exercises tab and exercise page"), progress
(`core/progress/`: estimated 1RM, sessions, weeks, streak, records; see
"Progress"), the sync protocol with its pure rules (`core/sync/`, see
"Sync"), and the import adapters (`core/importing/`, see "Import"). Core gets the catalogue as a `String`;
where the text comes from (APK assets, a file, the classpath) is the caller's
business. Room entities live in `data/` and map to core's shapes
(`SetEntry.toLogged()`); core never depends on Room.

**The rule: no `android.*`, `androidx.*`, `java.*` or `javax.*` imports in
`core/src/commonMain`, and no `Dispatchers.IO` (a caller passes its
dispatcher in).** Anything that needs a `Context`, resources or Room goes in
`data/`, Compose in the apps, and what needs the JVM in `core/src/jvmMain`;
if a function is mixed, split it and move the pure part. `NoJvmImportsTest`
(in `core/src/jvmTest`) scans `commonMain` and fails on such an import, and
the iOS compile catches what an import scan cannot: a JVM member reached
without one, such as `java.util.Map.merge` on a `MutableMap` (what
`Suggestions.kt` used until the targets came). Core's only
dependencies are `kotlinx-serialization-json`, `kotlinx-coroutines-core` and
`kotlinx-datetime`, all `api`. Dates and zones in core's API are
kotlinx-datetime's `LocalDate` and `TimeZone` (instants travel as epoch
millis), and the apps convert at the edge (`toKotlinLocalDate()`,
`toKotlinTimeZone()`, `toJavaLocalDate()`). What the JVM used to supply is
pure Kotlin: the name-based ids (`nameBasedUuid` over `Md5.kt`, byte for byte
what `UUID.nameUUIDFromBytes` made, which `Md5Test` checks against the JDK),
diacritic folding and composition (`Diacritics.kt` over
`DiacriticTables.kt`, generated by `java core/tools/GenDiacritics.java` from
the JDK's Unicode tables, plus the Hangul syllables by their formula, so the
whole BMP folds as NFD minus the marks did and "벤치프레스" still matches
"베"; `DiacriticsTest` compares with `java.text.Normalizer` over the whole
plane. Outside the BMP nothing folds: a surrogate pair is kept as typed), and
the server URL check (`checkServerUrl`, a small parser of
`scheme://host[:port][/path]`: a bracketed host must be a well-formed IPv6
literal, and a dotted name with a leading-zero octet, `010.0.0.5`, is a host
name that needs https, not a private address).

### `data/` — the shared data layer

A Kotlin Multiplatform module (`com.android.library` + `kotlin.multiplatform`:
`androidTarget()`, namespace `io.github.kanylbullen.tung.data`, minSdk 26,
and the iOS app's `iosArm64()`/`iosSimulatorArm64()`) with everything below
the screens, so the phone, the watch and later the iPhone run the same code
on their own databases. **`src/commonMain`** holds what every client runs the
same way: the Room entities, DAOs and `TungDatabase` (Room 2.8's
multiplatform API), the repositories, `RoomCommandTx`, the command handler and
resolver, the catalogue indexes, sync (`SyncClient`, `SyncStore`,
`SyncAccount`, `SyncSettings`), the assistant's client and service, the import
and the settings in DataStore (`datastore-preferences-core`). **`src/androidMain`**
holds what needs Android: `TungContainer`, the database builders,
WorkManager's `SyncWorker`, `EncryptedTokenStore`, the rest timer's alarm and
alerts, the ongoing notification, push, Health Connect, the Wear state and
`ExerciseNames`. **`src/iosMain`** holds the iOS actuals (below). The
platform seams, each one file per platform: `TungDatabase.build`, an
extension on the companion per platform (Room's Android open helper on the
phone and the watch; `BundledSQLiteDriver` over a file path on iOS), and the
`expect`/`actual` declarations `TungDatabaseConstructor` (Room generates the
actuals), `ALL_MIGRATIONS` and the seed callback (both running the SQL of
common `DatabaseSchema`), `RoomDatabase.transaction` (Room's `withTransaction`
on Android; on iOS a writer connection's `immediateTransaction`, which checks
the caller's `Job` before it commits, so a cancelled caller rolls back there
too: every rule that writes more than one row runs in one), `ioDispatcher`
(the platform's `Dispatchers.IO`, which common code cannot name), and
`syncHttpClient` and `aiHttpClient` (OkHttp on Android; Darwin on iOS, whose
engine has no connect timeout, only the socket one). The iOS klibs compile on
Linux and in CI (`:data:compileKotlinIosArm64`), so a JVM-only type in
`commonMain` fails the build before there is a Mac. The packages are the ones
the files had in `app/`:

| Package (`data/src/commonMain/kotlin/io/github/kanylbullen/tung/…`, Android-only parts in `androidMain`) | Contents |
|---|---|
| `data/` | Entities (`Exercise`, `Workout`, `WorkoutExercise`, `SetEntry`, `Routine`, `RoutineExercise`, `Program`, `ProgramDay`), `ExerciseDao`, `WorkoutDao`, `RoutineDao`, `ProgramDao`, `TungDatabase` (seeds the core catalogue's 70 exercises in `onCreate`), `ExerciseRepository`, `WorkoutRepository`, `RoutineRepository`, `ProgramRepository`, `ExtendedCatalogAssets.kt` (`ExtendedCatalog.fromAssets(context)`), `CatalogueMuscles` (core's `MuscleIndex` over both catalogue assets, `container.catalogueMuscles`), `CatalogueDetails` (core's `CatalogueIndex` the same way, `container.catalogueDetails`, for the exercise page), `EquipmentSettings` ("My equipment" in DataStore), `ProgressSettings` (the compact trends' metric and span), `AppCommandHandler` and `AppExerciseResolver` (see "Commands"), `TungContainer` and `TungHost`. `SeedExercises.kt` is generated. |
| `sync/` | Sync with tung-server: `SyncClient` (Ktor; also link codes, `/auth/methods`, Google login, `/me`), `SyncStore` (Room side), `SyncAccount` (sign in/out, link codes, triggers), `SyncWorker` + `WorkManagerSyncScheduler`, `SyncSettings` (DataStore), `TokenStore`/`EncryptedTokenStore`, `SyncMapping.kt`. See "Sync". |
| `timer/` | The rest timer's Android side: `RestTimerController` (DataStore + alarm), `RestAlarm`, `RestTimerReceiver` (declared in data's manifest), `RestAlerts` (vibration + notification). |
| `wear/` | `WatchStateSource`: the `WatchWorkoutState` of this device's database, with `WearSelection`/`WearSelectionHolder`. The phone publishes it to a companion watch; a standalone watch shows its own. |
| `ongoing/` | The ongoing notification of a workout in progress: `OngoingWorkout` (+ `ongoingWorkouts`, its Flow over the repositories, and `chipText`, the Live Update chip's text), `OngoingWorkoutNotifier`, `OngoingWorkoutReceiver` (its Log set and Add set). See "Ongoing workout and push". |
| `push/` | Push: `PushRegistrar`, `PushEnvironment`/`AndroidPushEnvironment`, `TungFirebaseMessagingService`, `TungUnifiedPushReceiver`. See "Ongoing workout and push". |
| `importing/` | Import from other apps: `Importer` (the one bulk write), `ImportService` (`container.imports`), `ImportHistory`. See "Import". |
| `ui/common/` | `ExerciseNames` and `Exercise.displayName(context)`; the composable `exerciseDisplayName` stays in `app/`. |

`../catalog` (`core.json`, `extended.json`) is an asset directory of the
library, so both APKs get it from there. The library's own strings are only
what library code shows (the "Rest over" and "Workout in progress" notifications), in
`data/src/androidMain/res/values{,-sv}/`; screens' strings stay in each app.

- **`app` and `wear` never touch Room.** No DAO, no `TungDatabase`, no
  `transaction` outside `data/`: the apps read the repositories' Flows
  and change data through `container.commands` (see "Commands").
- **`TungContainer(context, clock, createDatabase, createTokenStore, startedBy, createPushEnvironment, createHealthConnect, createAiKeyStore)`** is the
  one container. The application implements `TungHost` (`container`,
  `isInForeground`, the notification icon, the intents "Rest over" and the
  ongoing notification open), which
  is how `SyncWorker` finds the container and `RestAlerts` knows what to show,
  without the library knowing either app.
- **Tests:** `DirtyTrackingTest`, `SeedExercisesTest`,
  `RoutineEditingCommandsTest` (`create_routine` and the routine-editing
  commands through `AppCommandHandler`), `DeleteRoutinesCommandTest`, `AddExerciseCommandsTest`
  (`add_exercise` set counts and pre-fill, `add_set` with a count), `LogSetCommandsTest`
  (`log_set` only ticks; `add_next` and routine targets), `ProgressSetsTest` (the progress reads), `ImporterTest` and `SyncMappingImportTest` (see "Import"), `SyncClientTest` and `LinkClientTest` in `data/src/androidUnitTest` (Robolectric with the default
  application). `FakeSyncServer`, `MemoryTokenStore`, `FakePushEnvironment`
  and `FakeHealthConnect` are the module **`data-fixtures/`** (a plain Android
  library, `testImplementation(project(":data-fixtures"))` in data's own
  tests and in both apps): a multiplatform Android target has no test
  fixtures of its own.

### `app/` — the phone app

| Directory | Contents |
|---|---|
| `ui/<screen>/` | `Screen` + `ViewModel` + `UiState` per screen: `home`, `workout` (active workout, the input sheet, the rest sheet), `exercises` (tab and picker in one, the exercise page `ExerciseDetail*` with its routine sheet, and `MuscleMap` with `MuscleHeatMap`), `history` (list + detail), `summary` (see "Workout summary"), `routines` (the Routines tab, one routine, the row and the name dialog that Home, summary, the tab and detail share; programs list + one program), `progress` (the Progress tab, an exercise's progress, the sheet inside a workout; see "Progress"), `settings` (from Home's gear: the list of groups and one screen per group, see "Settings"), `importing` (the import's preview, review and summary). |
| `ui/ai/` | The Assistant screen (in-app AI with the user's own key), reached from the last row on Home; its settings are `ui/settings/AiSection.kt`. See "In-app AI (BYOK)". |
| `auth/` | `GoogleSignIn` and `AndroidGoogleSignIn` (Credential Manager), made by `TungApp.createGoogleSignIn()`. See "Sync", "Account". |
| `voice/` | `SpeechInput` and `AndroidSpeechInput` (the phone's `SpeechRecognizer`), made by `TungApp.createSpeechInput()`; the workout's mic, hands-free mode and the mapping of intents are in `ui/workout/Voice*.kt`. See "Voice". |
| `ui/common/` | Formatting, shared components, `WorkoutReport`, `exerciseDisplayName` (displayed exercise names, over data's `ExerciseNames`), `Containers.kt`; `charts/` holds the Canvas charts (`LineChart`, `BarChart`, `Sparkline`, `niceTicks`; see "Progress"). |
| `ui/theme/` | Colour tokens, typography, theme. |
| `wear/` | The phone's side of the Data Layer for a companion watch (section "Wear"). |
| `../catalog/` | The exercise catalogue as open data (CC0): `core.json`, `extended.json`, generator and import scripts. Shipped as an asset directory of `data/`. See `catalog/README.md`. |

The app's most important query is `WorkoutDao.previousSessionSets` — the sets
for an exercise from the latest finished workout that contains it. It drives
both "Last: 80 × 8" and the pre-fill of new sets.

### Home

`ui/home/`: the wordmark (`Tung.numbers.wordmark`: Archivo at its widest
and heaviest, `ArchivoWide` in `ui/theme/Type.kt`, which only the wordmark
and the hero number use), then, once there is a finished workout, **"This
week"** (`ThisWeekSection`): the week's volume as the hero number
(`Tung.numbers.hero`, 64 sp, smaller for a longer number), "3 workouts ·
41 sets" (or "No workout yet this week · Last week 2 430 kg", from
`ThisWeek.lastWeek`), "Best of the last 12 weeks" in the accent when it is, the
muscles the week's sets worked as the small body (`MuscleHeatMap` at 112 dp
over core's `muscleBreakdown`, like the summary's), and the seven days as
bars (`WeekStrip`: Monday first, a trained day in the accent scaled to the
week's biggest day, an empty day a short mark, today outlined; one
semantics node naming the trained days). The numbers are core's
`thisWeek(sets, today, zone)` (`core/progress/ThisWeek.kt`, `ThisWeekTest`:
every day present, workouts counted once per day, logged sets only, the
date in the zone, and `bestOfRecentWeeks` = strictly more volume than each
of the eleven weeks before, at least one of which had a workout), computed
in `HomeViewModel` on
`Dispatchers.Default` from `WorkoutRepository.observeProgressSets()`. Then
Start workout (or Resume with its menu), "Today" when a program is active,
the three routines as one raised group (`RoutineRow(compact = true)`: a
56 dp row with an outlined 40 dp Start), Recent workouts, and the
Assistant's row. **A routine's name on a row is `RoutineName`**
(`ui/common/RoutineName.kt`, the routine rows and the workout rows):
everything up to the last `core.ROUTINE_NAME_SEPARATOR` (" · ", the one
constant the import builds names with) is drawn muted, so an imported
"Skokloster Gym · JT2 dag 1" reads as its day, and when the line is too
narrow it is the muted group that is cut, never the day ("Your Adap… ·
Full Body Workout"; with no room for three ems of the group, the day
alone). A `Layout` of three texts measures each part's intrinsic width,
the pure `routineNameWidths` (`RoutineNameWidthsTest`) shares the line,
the parts sit on one baseline, and the whole name is one semantics node
(`clearAndSetSemantics`), so a screen reader hears it entire and a test
finds it as one string (`RoutineNameFlowTest`, which also renders the
day-alone case). A workout's row (`WorkoutRow`, shared with History) is
titled with the routine it was started from, else its first two exercises,
else "Workout", with the short date (`shortLocalDate`, "Thu 22 Oct"),
duration and exercise count muted under it and the volume on the right; `WorkoutSummaryRow` carries the routine's name
and the first two exercises' names and keys as SQL subqueries (a query
result, not an entity: no schema change). The date stays its own text node,
which is how the flow tests find a workout.

### Settings

Home's gear opens `settings`, a list of groups (issue #67): calm list rows,
each a title and a one-line live summary (`SettingsScreen` and its read-only
`SettingsViewModel`; Health Connect's line comes from
`HealthConnectViewModel`). A row opens `settings/<group>`
(`SettingsGroup`, `settingsGraph` in `SettingsNavigation.kt`, one call in
`TungAppRoot`); each group page is a `SettingsPage` (top bar, back, scrolling
column). All in `app/.../ui/settings/`:

| Group | Route | Summary | What is there |
|---|---|---|---|
| Account | `settings/account` | "Signed in as …" / "Not signed in" | `AccountScreen` + `AccountViewModel`: the server (default, see "Sync", "Account"), sign-in, sync status, Sync now, Sign out |
| Devices | `settings/devices` | the push transport, or "Link a watch once signed in" | `DevicesScreen` + `DevicesViewModel`: "Link a watch" and push (needs an account; signed out, a hint and "Go to Account") |
| Training | `settings/training` | "Default rest 1:30 · kg" | `TrainingScreen`: the default rest (−/+ 30 s, 0–300 s), the unit (kg), and "My equipment" (`EquipmentSection`, issue #65) |
| Assistant | `settings/assistant` | the model, or "Not set up" | `AiSection` |
| Health Connect | `settings/health` | "Writing workouts", "Off", … | `HealthConnectSection` |
| Data | `settings/data` | "Last import 21 Oct" | `ImportSection` (import's `onDone` pops back here): import, "Undo last import", "Delete routines from the last import (N)" |
| About | `settings/about` | "Version 0.1.0 · dogfood" | `AboutScreen` in `SettingsGroups.kt`: version and build type (`BuildConfig`), AGPL-3.0 with the source link, the catalogue's credit (core CC0, free-exercise-db Unlicense) |

A section's own composable keeps its logic and has no title of its own: the
page's top bar names it. Flow tests go in through `openSettingsGroup`,
`useServer` and `leaveSettings` (`FlowTest`).

### Routines

A `Routine` is a saved workout template: `RoutineExercise` rows in `position`
order, each with `targetSets` and a nullable `targetReps`. "Save as routine"
(summary, and the workout detail's menu) takes the logged sets per exercise:
`targetSets` is how many there were, `targetReps` the most common rep count
(`core.routineTarget`). Saving the same workout twice makes two routines.
**Starting** a routine (`StartRoutine`, core's `CommandRules.startRoutine`) creates a workout with
`routineId` set, the routine's exercises in order and `targetSets` unticked
sets each, filled by `core.routineStartSets`: the existing pre-fill rule at
each set's own position, so set 1 gets last session's set 1, set 2 last
session's set 2; past the end of last session a set repeats the one before
it, and with no history at all the sets start at 0 kg × `targetReps`
(`core.addedExerciseSets`, which `add_exercise` uses, is the same rule with
no target reps). Start
also sets `lastUsedAt`, which orders Home's Routines section and the Routines
tab. There is at most one workout in progress, so `start` returns that one
instead of creating a second, and the Start buttons are disabled while it
exists; `RoutineRow`'s Start is also disabled for a routine with no exercises.
`Workout.routineId` is indexed but deliberately **not** a foreign key:
deleting a routine (which cascades to its `RoutineExercise` rows) never
touches history.

**A routine's muscles.** Every routine row (Home's group, the Routines tab)
carries a small body (`MuscleHeatMap` at 44 dp, `RoutineRow(muscles = …)`)
and the routine's page the body at 140 dp with a line of its most worked
muscles: data's `routineMuscles(exercises, index)` (`data/RoutineMuscles.kt`)
is core's `muscleBreakdown` over the catalogue with each exercise's
`targetSets` as its sets, so a routine with 4 × squat and 3 × curl shows
quads over biceps; `RoutineDao.observeRoutineExerciseLinks` gives every
routine's exercises in one query for the lists, computed on
`Dispatchers.Default` in `RoutinesViewModel` and `HomeViewModel`. A screen
reader hears "Muscles: quads, glutes, chest" (the row's top three; the page
names them all with percentages). `RoutineMusclesFlowTest`.

**The Routines tab** (`Routes.ROUTINES`, `ui/routines/RoutinesScreen.kt`) is
the second of the bottom bar's tabs, Home · Routines · Exercises · History ·
Progress,
switched with `navigateToTab` (`saveState`/`restoreState`, like the others):
the Programs row on top, then every routine by last use, each with Start and
its detail behind a tap; routine and program details are pushed on top of the
tab, so back returns to it. Home keeps its Routines section, the three most recently used
(`HomeViewModel.HOME_ROUTINES`), and "Today"; its
"All" switches to the tab, and so does the Assistant's "Save program".
**"+ New routine"** in the tab's top bar opens `RoutineNameDialog` and runs
`CreateRoutine(name)` (`CommandRules.createRoutine`: an empty routine, stamped
and dirty), then opens the routine's detail, which says "Add exercises with
+ Exercise" until it has one.

**Selection mode** (issue #79, `RoutinesViewModel`): a long press on a row
starts it with that routine selected (the row's long-press action is labelled
"Select" for a screen reader); a tap then toggles a row, which is
`selectable` (announced as selected) with a checkbox where Start was: the
Start buttons are hidden while selecting. The top bar becomes ✕, "3
selected", "Select all" (until all are) and a delete icon; ✕ or back leaves.
The selection is in the ViewModel's `SavedStateHandle`, so it survives
rotation and process death, and a routine that disappears drops out of it.
Delete asks once (`DeleteRoutinesDialog`, shared with Settings → Data):
"Delete 12 routines?", "Your workout history is kept.", and, when any of
them is a program day, "3 program days that use them go too."
(`RoutineRepository.programDaysUsing`); then one `DeleteRoutines` with the
ids, one transaction and one sync.

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
- **Start** (issue #81): a primary Start button at the top of the page, the
  same `StartRoutine` as the row's Start on Home and the Routines tab, then
  the workout opens (`popUpTo(HOME)`, so back lands on Home). Disabled while
  a workout is in progress (`RoutineDetailUiState.canStart`, from
  `observeActiveWorkout`) or while the routine has no exercise.
  `RoutineStartFlowTest`.

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
`isActive`: `ActivateProgram` (`CommandRules.activateProgram`) deactivates every other program in
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
"Choose another" sheet that overrides the suggestion, runs
`StartRoutine(routineId, programDayId)`, so the workout records
both ids. `start_routine` refuses a day that is gone (removed on another
device, with the sync landing between the card's drawing and the tap):
nothing opens, and Home says so in a snackbar ("That day is no longer in the
program.", `HomeMessage.DAY_GONE` over `HomeViewModel.messages`); the card
follows Room and already shows the program as it is now. A rest day has no
Start and is unchanged. `programDayId` is indexed and, like `routineId`, **not** a foreign
key: removing a day or deleting a program never touches history. "Today" is
`LocalDate.now(container.clock)`: the system clock in the app, a settable
one (`FlowTestApp.today`) in the flow tests. Programs are reached from a row
at the top of the Routines tab (`ui/routines/Program*`).

### Log set and rest timer

The active workout has a sticky **Log set · Bench press · 80 × 8** button
above the system navigation (always under the thumb, wherever the list is
scrolled). It acts on the **current set** (`core.currentSet`), marked by the
`raised` background on its row, a 3dp accent bar at the row's left edge and
its number in `accentText` (`Tung.colors.accent`; "Current set" as its
number's state description). The rule, in order: an explicit selection
while that set still exists and is un-ticked; else the first un-ticked set of
the exercise the user last touched (ticked, edited, added a set to, or just
added); else the first un-ticked set of the first exercise that has one; no
current set, the button turns into **Add set · Bench press · 80 × 8** (below);
an empty workout has no button. The selection is made by hand: a tap on an exercise's
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
above in view. Requests: the new set after + Set or Add set, the new current set after
Log set, the new current set when a tick moves it to another exercise, and the
new exercise's header to the top after Add exercise.

**Adding an exercise** (issue #55) gives it as many sets as it had last
session, pre-filled position by position (`add_exercise` without `sets`, see
"Commands"), so with history nothing opens. **The first time** (no history:
one set of 0 × 0) the weight sheet opens at once for set 1: the ViewModel
sees the one new exercise in the Flow (`noteAddedExercise`; the picker in
`ui/exercises/` runs the command, the workout screen only reacts) and puts a
one-shot `FirstTimeRequest` in the UI state, which the screen turns into an
`EditTarget(firstTime = true)` once the set is on screen, then calls
`onFirstTimeHandled`. That weight sheet's Done writes the weight and opens
the reps sheet for the same set; in this flow only, the reps sheet has
**Sets: 3 · 4 · 5 · 6 · 8** above Done (`SetCountChips`, default 3 =
`DEFAULT_ROUTINE_TARGET_SETS`; radio buttons described "4 sets"), and Done
writes the reps, then one `AddSet(count = n − 1)` so every set is a copy of
set 1 (`finishFirstTime`, after the weight's write). Set 1 stays current.
Dismissing either sheet leaves the one set (with the weight, if the first
sheet was confirmed). The sheet is keyed on its `EditTarget`, so the second
sheet starts fresh. + Set is unchanged. A plan (`ApplyPlan`) that adds
several exercises at once never opens it.
Tapping the button executes `Command.LogSet(workoutId, setId)` with the set
the screen shows as current, which runs `CommandRules.logSet`: **it ticks
the set and nothing more** (issue #64; before, logging an exercise's last set
silently added another). With reps 0 it opens the reps sheet instead, like the
tick. The tick on the row is the secondary way: it ticks only, and also starts
the timer. **Add set:** when no un-ticked set is left in the workout, the
button is `core.stickyAction`'s `AddSet` and reads "Add set · Bench press ·
80 × 8" for the exercise last touched (after a Log set, the one just logged;
with nothing touched, the last exercise), showing its last set's numbers. A
tap runs `AddSet` (a copy of that set, as + Set does), clears the selection
and touches the exercise, so the new set is current and scrolled into view
and the next tap is Log set again (`ActiveWorkoutViewModel.addSetFromButton`;
the UI state's `sticky` is `StickyButton.LogSet` or `AddSet`). A routine
workout is the same: after its last target set the button offers Add set,
and Finish stays in the top bar. The old behaviour is still on the wire as
`log_set.add_next` (`core.addsNextSet`, never past a routine's `targetSets`,
`WorkoutDao.routineTargetSets` reads the routine live); no client in the
repository uses it.

**The timer state** is `core.RestTimer` (`endsAtMillis`, `durationMillis`,
wall clock); what is left is always `remainingAt(now)`, never a counter. The
active workout's ViewModel holds it and writes it to its `SavedStateHandle`
and, through `RestTimerController`, to DataStore (`settings` file, also the
global default `rest_default_seconds`, 90 s, set in Settings → Training through
`RestTimerController.setDefaultSeconds`) and to an
alarm. A new ViewModel restores it from saved state, else from DataStore,
and drops one that already ended. The rest per exercise is
`Exercise.restSeconds` (null = default, 0 = off), set from the exercise's menu
("Rest timer…", 0–300 s in 30 s steps) as an ordinary update with
`updatedAt`. The countdown sits next to the elapsed time; tapping it shows
Skip and +30 s. The menu's other item, "Remove exercise", asks first when
the exercise has a logged set ("Remove Bench press and its 3 logged sets?")
and removes at once when it has none.

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
It must be at least 1.1.3: in 1.1.1 a read that raced the file's very first
write (a fresh install, e.g. the first workout's `stored` read next to
`firstTimeAskingForNotifications`) threw `FileNotFoundException` when the
write's atomic move landed between the failed open and the `exists` check;
1.1.3 reads again.

**Deleting a set** (issue #81): a swipe to the left on a set row no longer
deletes it; the row settles open with a **Delete** button revealed behind it
(`SwipeableSetRow`, an `AnchoredDraggableState` with `Closed` at 0 and
`Open` at −96 dp, filled with `Tung.colors.danger`), and a tap on the button
deletes. A tap on the open row, a swipe back, a scroll, or a tap on anything
else in the list closes it, and only one row is open at a time
(`WorkoutList`'s `openSetId`; every row and header callback goes through
`closing`). The button is composed only while revealed, so a screen reader
never meets "Delete" on every set; its way to delete is still the custom
action "Delete set" on each part of the row. `SwipeDeleteFlowTest`,
`AccessibilityFlowTest`.

**Discard workout** (issue #81): the active workout's top bar has a menu after
Finish with "Discard workout…", and Home's Resume card the same in a small
menu, both opening `DiscardWorkoutDialog` ("Discard this workout? The sets
you logged are deleted."). Confirming runs `Command.DeleteWorkout` (the
ordinary delete, tombstones included), clears the rest timer and its alarm as
finish does (`ActiveWorkoutViewModel.discard`, `ActiveWorkoutEvent.Discarded`)
and returns to Home; the ongoing notification follows the database and goes.
It is the only way to abandon a workout: Finish keeps the ticked sets or, with
none, discards with the "not saved" toast. `DiscardWorkoutFlowTest`. The
finish dialog says what goes ("3 sets that are not ticked will be removed.",
"1 exercise without sets goes too.", or "Every set is logged.") and its
buttons are short, "Finish" and "Keep training", so they sit side by side.

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
`data/src/commonMain/kotlin/.../data/SeedExercises.kt` and the `ex_*` lines between
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
"Muscles". The exercise page draws them on a body (the muscle map, see
"Exercises tab and exercise page"), and the workout summary shades them by
how much a workout worked them (see "Workout summary"); every muscle has a
region there, so a new `Muscle` needs one in `core/muscles/BodyMap.kt` too
(`BodyMapTest`).

### The extended catalogue

`catalog/extended.json` (~600 exercises, English only, from free-exercise-db
under the Unlicense, built by `catalog/tools/import_free_exercise_db.py`) is
**not** in Room. `ExtendedCatalog` (in `core`, fed from assets by
`ExtendedCatalog.fromAssets`) reads it once, lazily on IO. The exercise
picker searches it from two characters and shows the matches under the
heading "More exercises", with the same `filterByDisplayName`; the Exercises
tab lists all of it among the user's exercises, by muscle group (see
"Exercises tab and exercise page"). When such an exercise is picked, the
command names it `ExerciseRef.Extended` and the executor makes its row
(`CommandTx.ensureExerciseRow`: insert with `OnConflictStrategy.IGNORE` on
the phone; the import's `ExerciseRepository.getOrCreateExtended` is the same
rule) with the deterministic id
`extendedExerciseId(fedId)` = `catalogExerciseId("ext:" + fedId)`,
`nameKey = null`, the English name in `name` and `isCustom = false`. The same
exercise thus gets the same row on every device, and after that it is an
ordinary exercise (it shows in the tab and among the core matches, and
disappears from "More exercises").

**Equipment values** (`core.Equipment`, issue #65): `barbell`, `ez_bar`,
`dumbbell`, `kettlebell`, `machine`, `cable`, `band`, `bodyweight`,
`medicine_ball`, `exercise_ball`, `other`, in the order the app lists them.
The import maps free-exercise-db's `kettlebells`, `bands`, `e-z curl bar`,
`medicine ball`, `exercise ball` and `body only` to their own value; `foam
roll`, `other` and null stay `other` (table in `catalog/README.md`).
`Exercise.equipment` in Room is the **key as a string**, so a new value needs
no schema change, and `Equipment.fromKey` reads an unknown key (a newer
catalogue or client) as `other`. Adding a value: the enum, `EQUIPMENT` in
both catalogue scripts, `equipmentLabel` and an `equipment_<key>` string in
both languages, and the `Equipment` enum in the server's `openapi.yaml`
(`OpenApiCoverageTest`). **The server validates synced rows against its own
enum** (`SyncValidation`): a phone with a new value pushing a picked
extended exercise that carries it gets a 4xx from an older server until that
server is redeployed (rows stay dirty, nothing is lost), so deploy the server
before or with the app.

**Instructions.** Extended entries carry free-exercise-db's `instructions`.
The core entries that have a twin there (64 of 70; the import drops the twin
from `extended.json`) get the twin's steps in `core.json` as
`"instructions": {"en": [...]}`, written by the same import script;
`CoreExercise.instructionsIn(language)` falls back to English, since Swedish
steps do not exist yet. Like the muscles they are catalogue data only: no
Room column, nothing synced, and `gen_android.py` ignores them.

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

The candidates are narrowed to "My equipment" first (see "Exercises tab and
exercise page"); the workout's own exercises stay in the list given to
`suggest`, which reads their groups and never proposes them.

**Hand-over:** once A has three observations (recent workouts with A and
another exercise) the learned score is tripled, so the user's habits win;
before that the heuristic separates what history does not. Each row's reason
is the largest contribution; order is score, then name. **The learning is
derived from history every time the picker opens and is never stored or
synced**: no table, no column, nothing new in Private mode or on the server.
The server has no `suggest_exercises` MCP tool yet.

### Exercises tab and exercise page

**The tab** (`ExercisesScreen` without a picker target) lists the whole
catalogue: the user's exercises from Room (the core seed, custom ones and
extended entries already picked) and every extended entry that has no row
yet, by its English name. A row of chips under the search filters by
`MuscleGroup` ("All", then each group that has exercises; tapping the chip
that is on goes back to "All"); the search filters both, on the displayed
name, as in the picker. The list is in sections by `MuscleGroup` order,
sorted by name with the locale's collator, then by ref, so equal names keep
their place. The merging, grouping, sorting and filtering are pure and live in
core (`core/catalog/Browse.kt`: `browseItems`, `browseGroups`, `browse`); the
ViewModel only feeds them, recomputing the rows when Room, the catalogue or
the language changes and the sections per keystroke. About 680 rows: always a
`LazyColumn` with stable keys (`b-<ref>`). A row opens the exercise page; the
picker keeps its behaviour (Suggested, "More exercises", add on tap).

**My equipment** (issue #65). Under the group chips (in the picker, under the
search) one chip, "Equipment: All" or "Equipment: 3", opens `EquipmentSheet`
(`ui/exercises/EquipmentSheet.kt`): "Everything" and "Bodyweight only", then a
checkbox per `Equipment`; every tap is saved at once and the last ticked box
cannot be unticked. Settings → Training has the same sheet behind its "My equipment"
row (`ui/settings/EquipmentSection.kt`). The set lives in the `settings`
DataStore (`data/EquipmentSettings`, key `my_equipment`, `container.equipmentSettings`),
**not synced**; unset (and choosing everything) means all, so a value added
later is included for a user who never narrowed it. The filter is pure in
core (`core/catalog/EquipmentFilter.kt`: `passes`, `filterByEquipment` with
an allow-list, and `browse`/`browseGroups` take `available`): the tab, the
picker's list, "More exercises" and Suggested apply it; bodyweight is a value
like the others, so unticking it hides bodyweight exercises. **An exercise
already in the workout or routine always shows** in the picker (the
ViewModel passes their ids as the allow-list; Suggested still never proposes
them). The import's chooser (`PickerTarget.Choose`) is not narrowed: it maps
history done with whatever there was. `EquipmentFlowTest` covers the tab, the
picker and Settings.

**The exercise page** (`ui/exercises/ExerciseDetail*`, route
`exercise/{ref}`). The ref is `core.catalog.ExercisePageRef`: a Room id, or
`ext:<free-exercise-db id>` for an extended entry with no row (URL-encoded in
the route). An extended entry picked since is found by its row
(`extendedExerciseId`), whichever ref opened it. The page shows the name,
equipment and group, the muscle map, two actions, the history when the
exercise has a Room row, and the instructions (numbered, muted). What the
catalogue says comes from core's `CatalogueIndex` (both files by exercise id)
through `container.catalogueDetails`; nothing of it is in Room.

- **Add to routine** opens a sheet of the user's routines, most recently used
  first (none: a hint to save a workout as a routine), and a tap runs
  `Command.AddRoutineExercise(routineId, ref)` with the defaults (3 sets, no
  target reps), then a snackbar "Added to Push day".
- **Add to workout** shows only while a workout is in progress and runs
  `Command.AddExercise(workoutId, ref)`, then opens the workout.
- An extended entry is named `ExerciseRef.Extended(id)` until it has a row,
  so the handler's get-or-create makes it; after that the page shows its
  (empty) history.
- **History**: `WorkoutDao.observeExerciseHistory` reads every counted set in
  finished workouts and core's `exerciseHistory` sums them: how many workouts,
  the best set (`bestSet`, weight × reps) and last session's sets (the first
  time the exercise appears in the latest workout, as `previousSessionSets`
  picks it). Under it, once there is history, **Progress** opens the
  exercise's progress (`progress/{exerciseId}`, see "Progress").

**The muscle map** (`MuscleMap(primary, secondary)` in `ui/exercises/`)
draws a front and a back body with Compose `Path`s: primary muscles in
`Tung.colors.accent`, secondary in the accent at 40 %, the rest a muted
surface tone, and one legend line "Primary / Secondary"; a screen reader gets
one node that names the muscles. The shapes are data in core
(`core/muscles/BodyMap.kt`): two outlines and, per `Muscle` and side,
normalised polygons hand-authored on a 100 × 200 grid (bilateral ones on the
left half and mirrored), simple and recognisable rather than anatomical. Some
muscles are on one side only (chest and abs on the front, glutes and lats on
the back). `BodyMapTest` checks that each of the 21 muscles has a region,
that every point is in 0..1 and inside its outline, and that the map is
symmetric. It is drawn at 160 dp high. No photos: free-exercise-db's images
have no documented origin.

### Progress

Issues #60 and #78. The last tab, after History, and two ways into one exercise's
progress. **All computation is pure in core**, `core/progress/` (package
`io.github.kanylbullen.tung.core.progress`, `ProgressTest`), so the tab, the
sheet and later the server and MCP compute the same:

- **`estimatedOneRepMax(weight, reps)`**: Epley, `w × (1 + reps / 30)`, only
  for 1–12 reps (`E1RM_MAX_REPS`); a single is the weight; null otherwise.
- **`ProgressSet`**: one set with its exercise, workout, workout exercise,
  the workout's start, positions and `completed`; **only logged sets count**
  (`isLogged`: ticked and reps > 0), and every function drops the rest
  whatever it is given.
- **`sessionPoints(sets, zone)`**: one `SessionPoint` per workout (an
  exercise added twice is one session), oldest first, with the date in the
  zone, the sets, the best estimate and its set, the top set (heaviest, ties
  to more reps) and the session volume; `value(ProgressMetric)` for `E1RM`,
  `TOP_SET`, `VOLUME` (null for `WEEKLY_VOLUME`, a week's number).
  `exerciseProgress(sets, zone)`: every exercise with a session, most
  recently trained first.
- **Ranges** (`ProgressRange`: `THREE_MONTHS`, `ONE_YEAR`, `ALL`) count back
  from today (`startOn`, inclusive; `within`).
- **Trends**: **`trendPoints(sessions, metric, range, today)`** is what every
  chart and sparkline plots, `TrendPoint(date, value)` oldest first: one per
  session in range that has a value, or for **`WEEKLY_VOLUME`** one per ISO
  week with a session, dated its Monday, the sum of its sessions' volume.
  Weeks without the exercise are no point (not zeros: x is the date, so a
  gap shows), and the range's first week counts whole, from the Monday of
  the week its first day falls in. **`change(points, metric)`** (and
  `List<TrendPoint>.change()`): first to last value, null with fewer than
  two. `TREND_RANGES` (3 and 12 months: `ONE_YEAR` is twelve months, called
  "1Y" on the exercise screen and "12M" in the compact trends),
  `TREND_METRICS` (`E1RM`, `VOLUME`, `WEEKLY_VOLUME`) and
  `ExerciseProgress.trend(metric, range, today)` (`RowTrend`: the latest
  value of all, the last session's or the last trained week's, then the
  values in range and their change) are what a tab row shows.
- **Weeks**: `weekStart` is the ISO week's Monday (1 January 2026 is in the
  week of 29 December 2025); `weeklyBuckets(sets, today, zone, weeks = 12)`
  has every week, empty ones as zeros, with the workouts that have a logged
  set in it and their volume. **`weeklyStreak`**: consecutive weeks with a
  workout back from this week; an empty *current* week does not break it
  (counting starts from last week), an empty earlier week does.
- **Records** (`exerciseRecords(sessions)`, `RecordKind` in rank order):
  heaviest weight, best estimated 1RM, most reps (of equal reps the heavier
  set), best session volume, each with its set, workout and date. A tie never
  takes a record: the first session to reach a value keeps it.
  `mostRepsAtOrAbove(weight, sets)`. **`beatsRecord(weight, reps, records)`**
  is the highest-ranked record a proposed set would beat: heavier than ever,
  else a better estimate, else more reps than ever at that weight or heavier;
  null with no reps, no history (nothing to beat, like `isNewRecord`) or when
  it only equals. Session volume is never answered for one set.
  `repsToBeatE1rm(weight, records)`: the fewest reps at that weight that beat
  the best estimate. `recentRecords` (`RECENT_RECORDS` = 5): standing records
  across exercises, newest first, leaving out an exercise's first session
  (it beat nothing) and keeping one per exercise and workout.
  `progressOverview` bundles weeks, streak, exercises and recent records.

**data**: `WorkoutDao.observeProgressSets()` (every exercise) and
`observeProgressSets(exerciseId)`: the counted sets of finished workouts with
exercise id and start, as `ProgressSetRow` (a query result in
`ProgressRows.kt`, not an entity: no schema change), one query per screen.
`WorkoutRepository.observeProgressSets` maps them to `ProgressSet`. Imported
workouts are ordinary finished workouts and count the same; the workout in
progress never counts.

**app, `ui/progress/`** (`ProgressViewModel` and `ExerciseProgressViewModel`
compute on `Dispatchers.Default`; today and the zone are `container.clock`'s):

- **The Progress tab** (`Routes.PROGRESS`, `ProgressScreen`, icon
  `ic_trending_up`): "Last 12 weeks" with workouts and weeks in a row, bars
  for workouts and volume per week; **Exercises**, most recently trained
  first, each row "Est. 1RM 112 kg · +14 kg in 3 months" (or "No change in
  3 months", or "1 session in 3 months" when the span holds fewer than two
  values), always with a sparkline of the span's values (one session is a
  dot, so the rows line up); **Recent records**. A row opens the
  exercise's progress. **The compact trends' choices** (issue #78), two
  rows of chips under the Exercises heading (`ChoiceChips`, the exercise
  screen's style; see below when they do not fit): the metric **Est. 1RM ·
  Volume · Weekly volume** (Volume is per session: "Session volume 480 kg ·
  +60 kg in 3 months"; "Weekly volume 930 kg · …" is the last trained
  week's) and the span **3M · 12M** ("−1 kg in 12 months"); default
  estimated 1RM over 3 months. Both are remembered in the `settings` DataStore
  (`data/ProgressSettings`, keys `progress_rows_metric`, `"session_volume"`
  or `"weekly_volume"`, and `progress_rows_range`, `"12m"`; the default is no
  key; `container.progressSettings`), **not synced**, and **shared with the
  sheet in a workout**: a choice in either is the other's. The weekly bars
  stay at 12 weeks whatever the choice.
- **Exercise progress** (`progress/{exerciseId}`, `ExerciseProgressScreen`):
  metric chips (Est. 1RM · Top set · Volume · Weekly volume, a menu on a
  phone since four do not fit) above a line chart, range chips (3M · 1Y ·
  All) below it, the records with dates, the last five sessions' sets. Reached from the tab and from the exercise
  page's **Progress**. Its choices start at estimated 1RM over 3 months each
  time and are not the compact trends'. Estimates and volumes are written in
  whole kilos, a top set as lifted.
- **The sheet in a workout** (`ProgressSheet`): each exercise header in the
  active workout has a trend button ("Progress for Bench press") next to its
  menu. The sheet shows the record line for the exercise's current set (the
  screen's current set if it is this exercise's, else its first open set):
  "96 × 7 would be a new best estimated 1RM", "… would be your heaviest
  weight", "… your most reps at 96 kg or more", else the reps at that weight
  that would beat the estimate, else "is no new record" (only a set that
  beats a record is in the accent; the rest is muted); then a compact
  chart between the tab's two chip rows (Est. 1RM · Volume · Weekly volume
  above, 3M · 12M below, the same remembered choice as the rows) and the
  last three sessions. It writes no training data; the open sheet is
  `rememberSaveable` state on the screen, and dismissing it leaves the list,
  its scroll and the selection as they were. Both screens use
  `ExerciseProgressViewModel`, keyed `progress-<exerciseId>` on the screen
  and `progress-sheet-<exerciseId>` in the sheet, which hands it
  `ProgressSettings` for its choices.
- **`ChoiceChips`** (`ProgressComponents.kt`) is always one line. It
  measures its chips (a `SubcomposeLayout` with a probe that is never
  placed and has no semantics) and, when they are wider than the line,
  shows instead one chip naming the choice with an arrow ("Show all
  choices") that opens a `DropdownMenu` of every option, the chosen one
  ticked. So it holds for any width, font scale and language; at the flow
  tests' 411 dp the exercise screen's metrics are the menu and every other
  choice is chips.

**Charts, `ui/common/charts/`**, drawn with Compose `Canvas`, no chart
library: `LineChart` (line plus a dot per point, the last one larger, x
proportional to the date), `BarChart` (a zero is a short baseline mark), and
`Sparkline` (no axes). One accent (`Tung.colors.accent`) for the data,
`border` for grid lines, labels muted, 12sp, tabular; at most four y labels
(`niceTicks`: steps of 1, 2, 2.5, 5 × 10ⁿ, `NiceTicksTest`), the first and
last date under the x axis as the locale writes a short month and day
(`shortDate`, `getBestDateTimePattern("MMMd")`). **Each chart is one semantics
node whose content description is its summary** ("Estimated 1RM, 3 months:
from 98 to 112 kg", "Weekly volume, 12 months: from 360 to 930 kg";
`chartSummary`, a row's sparkline too), which is what a screen reader says and what
`ProgressFlowTest` asserts; with fewer than two sessions in range the chart is
"Log this exercise in two workouts to see a chart" and the summary says so.

### Workout summary

The screen after Finish (`ui/summary/`, route `summary/{id}`,
`SummaryScreen` + `SummaryViewModel`), one `LazyColumn` (`SUMMARY_LIST_TAG`)
under the top bar, with **Save as routine** and **Done** side by side in a
bottom bar that stays put whatever is scrolled:

- **Stats**: duration, sets, volume, as before.
- **Exercises**: each row "2 sets · 960 kg · Est. 1RM 112 kg" (logged sets,
  `ExerciseReport.volumeKg`, `e1rmKg`: the best Epley estimate of its sets),
  "New record" when it is one, and the best set in large numbers.
- **Muscles worked**: the body (`MuscleHeatMap`, the exercise page's
  `BodyMap` with each muscle in the accent by how much it was worked, from
  30 % for the least to full for the most) and a row per muscle, most worked
  first: its name, its percentage and a bar against the most worked one.
  The numbers are core's **`muscleBreakdown`** (`core/muscles/MuscleWork.kt`,
  `MuscleWorkTest`): **sets per muscle**, so weight does not enter
  (bodyweight work counts, a heavy squat does not drown the curls). Each
  logged set credits each primary muscle of its exercise with one set and
  each secondary one with `SECONDARY_SET_CREDIT` = 0.5; a muscle in both is
  primary. Percentages are of all credit and rounded by largest remainder
  (`wholePercents`), so they add up to 100 (a sliver shows "<1%"). The
  muscles come from `container.catalogueMuscles`; a custom exercise has
  none, and its sets are a muted line ("2 sets of custom exercises are not
  counted…", `uncountedSets`). A screen reader hears the map as "Muscles
  worked: chest 50%, front delts 25%, triceps 25%".
- **Progress**: the compact trends' chips (Est. 1RM · Volume · Weekly
  volume, 3M · 12M; the same remembered choice as the Progress tab and the
  workout sheet, `ProgressSettings`) and, per exercise of the workout (once
  each, in workout order), its name, which opens its progress
  (`progress/{exerciseId}`), and a `ProgressChart` over every finished
  workout (`WorkoutRepository.observeProgressSets()`), this one the last
  point.

`SummaryFlowTest` (light and dark) covers the rows, the muscles and their
percentages, the charts and the shared choice.

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
- **One handler.** Core's `CommandExecutor` (`core/command/CommandExecutor.kt`)
  is the `CommandHandler` on every platform; `data/AppCommandHandler.kt`
  (module `data/`, so the phone and a standalone watch run the same one)
  and the server's `ServerCommandHandler` only wrap it with what is theirs
  (below). It runs `validate(command)` first (pure, in core: weight 0–999
  kg, reps 0–999, planned sets 1–50, a routine's target sets 1–20 and
  target reps 1–999 (or none), `add_exercise.sets` and `add_set.count`
  1–20, rest 0–3600 s, weekday 1–7, no blank names or ids, no empty plans)
  and returns `Rejected(reasons)` without touching anything when it fails.
  A new mutation = a new `Command` data class, a name in `CommandNames`, a
  branch in `validate`, a function in `CommandRules` and a branch in the
  executor's `perform` (the `when`s are exhaustive, so the compiler points
  at them).
- **One rule, in core (issue #87).** The rules live in
  `core/command/CommandRules.kt`, one function per command over a storage
  interface, `CommandTx` (`CommandStore.kt`: the live rows as
  `ExerciseRow`/`WorkoutRow`/`WorkoutExerciseRow`/`SetRow`/`RoutineRow`/
  `RoutineExerciseRow`/`ProgramRow`/`ProgramDayRow`, `previousSession`,
  `routineTargetSets`, `activePrograms`, `programDaysUsing`, inserts, the
  one-column updates and the deletes, one row each: a parent is deleted only
  after the rules deleted its children; `ensureExerciseRow` for an extended
  entry's row, `setRest` where a server makes the user's copy of a catalogue
  exercise), so that the phone, the server and later a web or iOS client
  cannot drift apart. `CommandExecutor(store, resolver, startedBy)` is the
  handler: `validate`, then one `CommandStore.transaction { }` in which it
  resolves names (`resolveRef`, `resolvePlan`, `resolveRoutinePlan`:
  anything unknown is `NeedsAnswers` and nothing is written), makes the row
  an extended-catalogue entry resolves to (after checking the workout or
  routine exists, so a refusal makes no row; `ExerciseCandidate` carries
  the muscle group and equipment for it) and runs the rule. The stores:
  `data/RoomCommandStore` (`db.transaction` over `RoomCommandTx`, the
  DAOs, stamping `updatedAt` and `dirty` as every write does, a tombstone
  per delete; `WorkoutRepository.commandStore()`) and the server's
  `command/ExposedCommandStore` (`lockUser` first, `ExposedCommandTx`
  stamping `GREATEST(now, updated_at + 1)`, deletes as tombstones, and a
  rollback of any result that is not `Done` as a guarantee). Ids come from
  the rules, time from the store, `startedBy` from the executor (`phone`,
  `watch`, `agent`). What the wrappers do: `AppCommandHandler` asks for the
  sync and Health Connect after `finish_workout` (`onWorkoutFinished`);
  `ServerCommandHandler` caps names for the REST API (`MAX_COMMAND_NAME`)
  before anything runs and wakes the user's other devices after a commit
  (`ChangeNotifier`). The rules are strict on every platform (an id that
  does not exist is `Rejected`, `log_set` refuses a set with 0 reps, which
  every client already guards: the app opens the reps sheet instead, and
  finishing a finished workout leaves it as it is) and write only what
  changes (the same target reps, mode, active state or rest again writes
  nothing). `CommandRulesTest` (core, over `MemoryCommandTx`) is where the
  rules are tested and `CommandExecutorTest` (over `MemoryCommandStore` and
  a `CandidateResolver`) what the executor adds; `LogSetCommandsTest`,
  `AddExerciseCommandsTest`, `RoutineEditingCommandsTest`,
  `DirtyTrackingTest` and the server's `CommandTest` guard the stores.
  `WorkoutRepository`'s write functions (`startOrResumeWorkout`,
  `addExercise`, `addSet`, `logSet`, `updateWeight`, `finishWorkout`, …),
  `RoutineRepository`'s (`start`, `saveFromWorkout`, `createFromPlan`,
  `create`, `rename`, `delete`), `ProgramRepository`'s (`create`,
  `activate`, `delete`, `addDay`, `removeDay`, `toggleWeekday`) and
  `ExerciseRepository`'s (`createExercise`, `setRestSeconds`) remain as the
  Room-side entry for callers that are not commands (the companion watch's
  `WearCommandHandler`, the import, tests) and run the same rules over
  `RoomCommandTx` in a transaction of their own; on the server
  `WorkoutStore.finish`/`delete` (the REST routes) do too, so
  `POST /workouts/{id}/finish` and the command are one rule.
  `ExerciseRepository.getOrCreateExtended` (the import's row for an
  extended entry) is the one exercise write outside them.
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
  Then one transaction (`CommandRules.applyPlan`): the workout in
  progress or a new one, the exercises appended in order, the planned sets,
  nothing ticked. A number the plan leaves out comes from the pre-fill rule
  (`plannedSetValues`). `SaveRoutinePlan(RoutinePlan)` does the same for a
  routine.
- **`AddExercise(workoutId, exercise, sets)`**, wire `add_exercise` with an
  optional `sets` (1–20): that many sets, else as many as the exercise had
  last session (`previousSessionSets` in the app, `lastSessionIn` on the
  server), at least one; each pre-filled at its own position by
  `core.addedExerciseSets` (the rule of `routineStartSets`: last session's set
  at that position, past its end the set before, 0 × 0 with no history).
  Nothing ticked. **`LogSet(workoutId, setId, addNext)`**, wire `log_set`
  with an optional `set_id` (default: the current set by the default rule)
  and an optional `add_next` (default false): it ticks the set and nothing
  more. With `add_next = true` it also adds the next set after the
  exercise's last (a copy of it, never past a routine's target sets,
  `core.addsNextSet`) and returns it as `addedSetId`: the behaviour before
  issue #64, kept for clients that want it. Every client in the repository
  (button, tick, notification, watch, voice) sends the default; the sticky
  button's Add set is a separate `AddSet`. **`AddSet(workoutExerciseId, count)`**, wire `add_set` with
  an optional `count` (1–20, default 1): that many sets, each a copy of the
  one before, in one transaction; `setId` is the first added set. Both are
  core's `CommandRules` (see "One rule, in core").
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
- **`DeleteRoutines(ids)`**, wire `delete_routines` with `routine_ids`
  (issue #79): 1–500 non-blank ids (`MAX_DELETE_ROUTINES`), a duplicate
  counts once. Each routine goes as `delete_routine` takes it (its exercises
  and the program days that use it, a tombstone for every row; workouts
  started from it stay), all in **one transaction**
  (`CommandRules.deleteRoutines`, so the phone and the server do the same).
  Returns `deletedCount` (a number as a string, like every id value). An id
  that is gone is skipped and not counted; a list in which none of the ids
  exists is `Rejected`, as `delete_routine` rejects a missing id. Callers:
  the Routines tab's selection mode and Settings → Data's "Delete routines
  from the last import" (both ask `RoutineRepository.programDaysUsing` how
  many program days go, before).
- **`CommandNames` are stable.** Each command has a snake_case wire name
  (`log_set`, `apply_plan`, …): the future REST route and MCP tool name.
  Clients and saved prompts outside the repository will call them, so a name
  never changes once it exists, like a catalogue key; rename the Kotlin class
  freely, but add a new command rather than rename a wire name. The
  `CommandIds` keys are stable for the same reason. `CommandNamesTest` fails
  when a command has no name (the commands are nested in `Command` so it can
  list them with plain Java reflection).

## Sync

The client half of issue #25, in `data/src/commonMain/kotlin/io/github/kanylbullen/tung/sync/`
and shared by the phone and a standalone watch: the device and a tung-server
exchange changed rows over `POST /api/v1/sync`. The protocol is `core/.../core/sync/SyncProtocol.kt`
(shared with the server, snake_case on the wire, one list per table, a
tombstone is a row with `deleted_at`); the pure rules are next to it:
`SyncPlanner` (what to push, in chunks of at most `SYNC_MAX_ROWS`, parents
first), `SyncApplier` (how a response merges), `checkServerUrl` (https, or
http only to localhost and private IP literals), `SyncState`, `SyncFailure`.

- **Dirty.** All eight entities have `dirty` (default `true`). The one place
  that stamps `updatedAt` (`RoomCommandTx`, which the repositories' wrappers
  go through too) stamps `dirty` with it: inserts get
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
- **One sync** (`SyncClient.syncOnce`, always on the platform's IO dispatcher (`ioDispatcher`), one at a
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
- **Account.** Settings → Account (`ui/settings/AccountScreen.kt`,
  `AccountViewModel`) takes email and password and calls
  `/api/v1/auth/login` or `/register`. **The server defaults to
  `https://tung.xuper.fun`** (issue #67): `BuildConfig.DEFAULT_SERVER_URL`
  in `app/build.gradle.kts`, one line for a self-hoster's fork, read through
  `TungApp.defaultServerUrl` (which `FlowTestApp` overrides with
  `defaultServer`, a closed localhost port, so no test ever reaches the real
  one). With no stored server it is shown read-only at the bottom of the form;
  "Use another server" turns it into the field, and a stored server other than
  the default shows as the field from the start. Signed out the order is
  "Sign in with Google" (when offered), "Or with email and password", the
  fields, Sign in, Create account. **Sign in with Google** (issue #51): the
  ViewModel asks `GET /api/v1/auth/methods` (`SyncAccount.authMethods`) **at
  once when the form shows** (the screen opens, or after signing out), and
  again 600 ms after typing in the server field stops (on
  `Dispatchers.Default` so the pause is real time under Robolectric; a 404
  from an older server means password only), and shows "Sign in with Google"
  only when it names a client id. The list of groups never asks a server.
  `GoogleSignIn` (`app/.../auth/`, made by `TungApp.createGoogleSignIn()`,
  which `FlowTestApp` overrides with `FakeGoogleSignIn`, like
  `createSpeechInput`) gets an ID token for that id from Credential Manager
  (`AndroidGoogleSignIn`: `GetGoogleIdOption`, all accounts, no
  auto-select, a nonce); `SyncAccount.signInWithGoogle` trades it at
  `/api/v1/auth/google` and finishes like `signIn`. "Google linked" under the
  account comes from `SyncSettings.googleLinked`, set at sign-in and
  refreshed from `/me` when Account opens. The watch keeps the link code. The session token is
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
  `data/schemas/io.github.kanylbullen.tung.data.TungDatabase/<n>.json` (the
  Room Gradle plugin writes it on build; `exportSchema = true`,
  `room { schemaDirectory }` in `data/build.gradle.kts`), adds a
  `MigrationStep` with its SQL to `DatabaseSchema.MIGRATIONS`
  (`data/src/commonMain/.../data/DatabaseSchema.kt`; the platforms'
  `ALL_MIGRATIONS` run it, and every builder registers them all; the test
  reaches a step through `MIGRATION_1_2`/`MIGRATION_2_3`, accessors over
  `ALL_MIGRATIONS` in `TungDatabase.android.kt`, so a new step gets one) and
  a case in `MigrationTest`, which creates the old
  version from its committed JSON with `MigrationTestHelper` under
  Robolectric (the schemas are a test asset directory), runs the migration,
  validates against the new schema and reads the old rows back.
  `everyVersionHasASchemaAndAMigration` fails when a version has no JSON or no
  migration. Version 1 is the schema as it was on 2026-10-20; version 2
  (issue #45) added the Health Connect columns on `workout`; version 3
  (issue #5) added `importSource` and `importBatchId` on `workout` (see
  "Import").

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
the phone's Settings → Devices shows ("Link a watch"), through
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

**Log set and Add set on the watch** (issue #64, both modes): Log set only
ticks (`log_set` without `add_next`). With every set ticked the state carries
`WatchWorkoutState.addSetWorkoutExerciseId` (`WatchStateSource`: the watch's
touched exercise, which after a Log set is the one logged, else the exercise
whose sets changed last, by `core.stickyAction`), and that exercise's page
shows "Add set" under "All sets logged" (`logButton`, `WatchLogButton`; the
pager turns to it). A tap sends `WatchCommand.AddSet(workoutExerciseId)`:
`AddSet` on the phone (`WearCommandHandler`) or the watch (`LocalWatchCommands`),
and the new set becomes the selection. Both are additive: an older watch
ignores the field, an older phone leaves it out (no button, as before) and
drops the unknown `add_set` message as one that does not parse. Optimistic:
logging the last open set offers Add set at once; Add set waits for the state.

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
`WearCommandHandler` still writes through the repositories' wrappers rather
than a `CommandExecutor` of its own (its rules are the same `CommandRules`;
an executor built with `StartedBy.WATCH` would replace it, a follow-up). A standalone
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
  the routine's target sets or, without a routine, the sets there are (the
  Live Update's chip shows that set's numbers, so the body names it too);
  only a workout with no set at all says "0 sets logged"; and with every set
  ticked "Bench press · all sets logged"
  (`OngoingStatus.AllLogged`, for the exercise Add set goes to: the one whose
  sets changed last, `lastTouchedByTime`, else the last); elapsed time by `setUsesChronometer` + `setWhen(startedAt)`. The
  phone's source is `ongoingWorkouts(...)` over the repositories, built as
  `container.ongoingWorkout` and started in `TungApp.onCreate`, so it shows
  whoever started the workout: this phone, a companion watch, or a sync that
  brought one from the server. Nothing is posted without `POST_NOTIFICATIONS`.
  **On Android 16 (API 36) the phone's notification is a Live Update.** The
  phone's `TungContainer.ongoingWorkout` builds the notifier with
  `liveUpdate = true`, and on 36 and above it calls
  `setRequestPromotedOngoing(true)` and `setShortCriticalText` (androidx.core
  1.17.0, which needs compileSdk 36). Nothing else changes: the standard
  template it already uses is one of the promotable styles (standard,
  BigText, Call, Progress, Metric), it is ongoing, has a title and a channel
  above `IMPORTANCE_MIN`, so title, body, chronometer and the three actions
  stay as they are, and the system shows it at the top of the shade and the
  lock screen and as a chip in the status bar. The chip's text is the current
  set's numbers, `OngoingWorkout.chipText` ("80×8": `ongoing_chip_set` over
  `logSetWeightKg` and `logSetReps`, the weight as `formatWeight` writes it,
  no spaces because the chip is 96 dp and shows a text whole only under seven
  characters: "100×10" fits, "22,5×12" is cut to what fits), and nothing when
  that set has no reps or every set is ticked (the chip then shows the icon).
  While it is on, the notifier debounces its source by `DEBOUNCE_MILLIS`
  (100 ms, like `WearStatePublisher`), since the chip follows every weight
  and reps edit of the current set and the system drops posts over its rate
  limit; below 36 and on the watch the source is collected as before. The phone's manifest
  declares `POST_PROMOTED_NOTIFICATIONS` (granted by default, revocable in
  Settings; revoked, the system ignores the request and the notification is
  the ordinary ongoing one). **Below 36 neither call is made**: the compat
  builder writes both into the extras on every API level, so the guard is in
  the notifier, and `OngoingNotificationFlowTest` (SDK 34) asserts the extras
  carry no request. **The watch never asks**: its chip is its Ongoing
  Activity, and Wear OS 6 is Android 16, so the API level alone would not keep
  it out; `liveUpdate` defaults to false and the watch's manifest has no such
  permission. `OngoingWorkoutTest` (data) covers the chip's text; seeing the
  chip needs an Android 16 phone with a dogfood build.
- **Actions.** Tap opens the workout (`TungHost.openWorkoutIntent`). **Log
  set** is a broadcast to `OngoingWorkoutReceiver` (in data's manifest) with
  the set the notification showed; it runs `Command.LogSet` through
  `container.commands`, no UI, and does nothing if that set is no longer
  open, so a stale notification cannot tick the wrong set. Like every
  `log_set` it adds no set. A current set with
  0 reps makes Log set open the workout instead (like the tick opening the
  reps sheet); it starts no rest timer. **Add set** replaces Log set when
  every set is ticked (`OngoingWorkout.addSetWorkoutExerciseId`): a broadcast
  (`ACTION_ADD_SET`, the workout exercise) that runs `Command.AddSet` and is
  ignored when stale (the workout is over, the exercise is gone, or a set is
  open again). **Finish** opens
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
  `WatchExercise.targetSets`, `WatchWorkoutState.startedBy` and
  `addSetWorkoutExerciseId` (for "all sets logged") carry what it needs.
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
  Settings → Devices shows the transport and offers "Choose UnifiedPush distributor"
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
  `androidx.health.connect:connect-client:1.1.0`, the stable release, which
  needs compileSdk 36), and
  `HealthConnectRecorder`, which does the work. `FakeHealthConnect` is in
  the `data-fixtures` module. `TungContainer(createHealthConnect = …)` is the seam:
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
  synced: Settings → Health Connect (`ui/settings/HealthConnectSection.kt`, one
  call in `HealthConnectSettingsScreen`) has a switch for each, which turns on only after
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

## Import

Issue #5; the design is `docs/architecture.md`, "Import from other apps".
Jefit is done; Strong and Hevy follow in the same frame.

- **core, `core/.../core/importing/`** (pure, tested). `splitSections` cuts a
  multi-section CSV export (`### NAME ####` … `######`) into tables **found by
  their header row**, never by line position (Jefit's ROUTINES holds three
  kinds of sub-table, each with its own header before every row); the same
  header right under data rows is a stray row and is dropped and counted.
  An `ImportAdapter` (`IMPORT_ADAPTERS`, `adapterFor(text)`) recognises its
  format and parses it into a plain `ImportBundle`: workouts (oldest first,
  finished, kg, sets logged only), routines, custom-exercise names,
  `skipped: Map<SkipReason, Int>`, `setTypes` (warm-up, drop, failure) and a
  `SetRowAccount`. Nothing in it is resolved yet.
- **`JefitImport`** (recognised by the WORKOUT SESSIONS and EXERCISE LOGS
  markers; the layout is documented in its KDoc). Set rows (EXERCISE SET
  LOGS) are **always lbs** and are converted and rounded to 0.5 kg; a log
  without set rows parses its `logs` text (`"15.0x8,25.0x8"`) **in the
  account's unit** (SETTING `mass`). A session with `starttime` 0 (Jefit has
  them) takes its logs' times; logs with `belongsession` 0 become one
  workout per `mydate` at noon, adopting a log-less session of that day if
  there is one. Each Jefit routine day with exercises becomes a Tung routine
  ("<package> · <day>", just the day when the package has one), exercises in
  `mysort` order, `setcount` → target sets, the most common `targetrep` →
  target reps; identical days once; programs are not imported.
- **Decisions** (taken for #5): warm-up, drop and failure sets are imported
  as ordinary logged sets (Tung has no set types) and the preview says how
  many; cardio (CARDIO LOGS, and exercise logs that CARDIO EXERCISE LOGS
  points at) is skipped and counted; 0-rep sets are skipped (they would not
  count as logged); PROFILE, EXERCISE RECORDS, FAVORITE EXERCISES and NOTES
  are not read.
- **Ids are deterministic:** `importId(source, kind, parts…)` =
  `nameBasedUuid("tung:import:<source>:<kind>:<parts>")`, with the
  Jefit user id and the rows' own ids as parts (workout: session id, or
  `date:<mydate>`; workout exercise: log id; set: log id and set index;
  routine: day id; routine exercise: day and row id). A custom exercise the
  review creates gets `importedCustomExerciseId(source, name)`. So a second
  import of the same, or a newer, export adds nothing.
- **Names.** `resolveImportNames`: the alias table
  `catalog/aliases/<source>.json` first (`{"source", "aliases": {name: key or
  "ext:<extended id>"}}`, compared with `normalizeExerciseName`, sorted by
  name), then core's exact-name rule (`ExerciseRef.ByName`), else
  `ImportMatch.Unmatched` for the review. **Only exact equivalents with the
  same kind of load go in the table** (a dumbbell or machine variant is not
  the barbell lift: its weights would poison "Last" and the pre-fill); the
  rest is the person's choice. `ImportResolutionTest` checks that every
  alias target exists. The tables ship as assets of `data/` like the
  catalogue.
- **Offline suggestions** (issue #71, `ImportSuggestions.kt`). An unmatched
  name carries up to five suggestions from `importSuggestions`: the load is
  read off the name (`inferEquipment`: dumbbell/db, barbell/bb, ez bar,
  kettlebell/kb, machine/smith/lever, cable, band, bodyweight/body weight;
  two different loads named, or none, is null), the other words are scored
  the way `matchExerciseName` scores them (`rankByName`), and among equal
  scores a candidate with the same equipment comes first
  (`ExerciseCandidate.equipment`, which the resolvers fill in). So "Dumbbell
  Deadlift" leads with Stiff-Legged Dumbbell Deadlift, not barbell
  `deadlift`; the words still beat the load (a dumbbell push press, which the
  catalogue lacks, leads with the barbell one). `nameUsage` counts per name
  the workouts it was logged in, its sets and the routines that list it.
- **Room** (version 3, `MIGRATION_2_3`): `Workout.importSource` (`jefit`)
  and `Workout.importBatchId` (one per import run, indexed), both nullable,
  synced as `import_source` and `import_batch_id` with the null-keeps rule
  (app `SyncMapping`, server `keepWhenNull`; server `V11__import_source.sql`,
  validated as a lower-case key and a UUID, shown by `WorkoutDto`, the export
  and MCP history, not taken by `PUT /workouts/{id}`).
- **data, `importing/`.** `Importer.apply(bundle, choices)` is **one Room
  transaction** with `IGNORE` on the deterministic ids: the chosen exercises
  (an extended entry gets its row, "Create" a custom one), every workout not
  here yet with its exercises and sets (ticked, stamped `updatedAt` now and
  dirty like every write), then the routines through
  `RoutineRepository.createFromPlan`, the `save_routine_plan` path, with the
  import's ids. A workout already here is left alone, so changed choices
  never half-update one. Tombstones of rows written again (a re-import after
  an undo) are removed, so sync brings them back. `undo` deletes the batch's
  workouts (`WorkoutRepository.deleteImportBatch`, the ordinary delete with
  tombstones) and the routines it created; custom exercises stay (there is no
  delete path for exercises). **This is the one bulk write outside
  `container.commands`**: no command expresses a whole history; a server
  import route (later) will call the same core adapter.
  `ImportService` (`container.imports`) reads (adapter, alias table from the
  assets, resolution, all on `Dispatchers.Default`), applies, undoes, and
  remembers the last import in the `settings` DataStore (`ImportHistory`).
- **app.** Settings → Data (`ui/settings/ImportSection.kt`, in `DataSettingsScreen`):
  "Import from Jefit" opens the system file picker (`ACTION_OPEN_DOCUMENT`,
  `text/*` and `*/*`), and "Undo last import" while one is remembered; it
  asks first ("Remove 138 imported workouts?", "Workouts you logged in Tung
  are kept."), since it deletes every workout the import added.
  **"Delete routines from the last import (N)"** (issue #79) shows while
  routines that import created (`LastImport.routineIds`) still exist, N
  counting those that do (live, over the routines Flow); it asks with
  `DeleteRoutinesDialog` ("The import's workouts are kept.", plus the program
  days that go) and runs one `DeleteRoutines` with those ids. The import's
  workouts are untouched, and undo still works afterwards: it deletes the
  batch's workouts and skips routines that are already gone. The
  file goes to `import?uri=…` (`ui/importing/`): read on IO (at most 64 MB),
  then the preview — date range, workouts · sets · routines, the set types
  imported as normal sets, what was skipped and why — and the review: each
  unmatched name has **Choose** (the picker with `PickerTarget.Choose`,
  which hands the exercise back through the import entry's saved state and
  adds nothing), **Create** (a custom exercise with the source's name) and
  **Skip**. Under each name: "In 12 workouts" or "Only in routines"
  (`ImportPreview.usage`), so the person sees which names carry history, and
  the best two offline suggestions as chips ("Lat pulldown?"); a chip is the
  same as Choose with that exercise. Above the rows, "Create all remaining"
  and "Skip all remaining" decide only the rows still "Not decided", and
  "8 of 11 decided" counts the rows (`ImportUiState.decidedCount`).
  **A decided row collapses** (issue #77, `DecidedRow`): the name, the usage
  line and one decision line in `accentText` with a check ("✓ Leverage Iso
  Row", "✓ Create “Heel Walking”", "✓ Skipped"), one node for a screen reader
  ("Machine Seated Row: imported as Leverage Iso Row"), and a quiet **Change**
  text button (described "Change Machine Seated Row"). No filled button, no
  chips, no proposal and no Choose/Create/Skip while it is collapsed: after
  Accept an accent-filled Choose read as the next step. Every decision path
  (Accept, a chip, Choose's pick, Create, Skip, the bulk actions) goes
  through the ViewModel's `choose` or the bulk functions and collapses the
  row the same way. **Change** (`reopen`) puts the name in
  `ImportUiState.reopened`: the row shows exactly the open layout again
  ("As …", the selected chip), but **the decision stands until a new one is
  made**, so the counter keeps it, Import stays enabled, and Choose then back
  from the picker leaves it decided; the next decision collapses it, and the
  bulk actions leave a reopened row alone (it is decided).
  Import is enabled once every name is decided; the summary says
  what was added and offers "Undo import".
- **"Suggest with the assistant"** (issue #71), above the rows when a BYOK
  provider is set up (`AiConfig.configured`): one `AiRequest.MatchExercises`
  on the tap, through `AiService.prepareMatch` and `send`, so "What is sent"
  comes first the first time and whenever "Show what is sent" is on (the
  Assistant's `PayloadSheet`). It sends the undecided names (all of them when
  every one is decided) with their offline top five, and the core catalogue;
  never history, routines, the account, the device or the server, and no
  custom exercise of the user's. While it runs the list says "Asking the
  assistant…"; a failure is one line with the Assistant's failure texts and
  the review works as before. Each row then shows "Assistant suggests: Lat
  pulldown" and its confidence tag, with **Accept** (Choose that exercise), or "Assistant: no
  good match", with the model's note; "Accept all high-confidence" decides the
  undecided rows whose proposal is high. **The confidence is a tag**
  (issue #75, `ui/common/ConfidenceTag.kt`) beside "Assistant suggests: Lat
  pulldown": high an accent-filled chip ("High", `accent` fill, `onAccent`
  text), medium an accent outline ("Medium", `accent` outline, `accentText`
  label), low a muted outline ("Low"); "no good match" is muted text with no
  tag. A screen reader hears "High confidence". **Accept** is the primary
  button on a high proposal and the secondary one on medium and low, and
  rests (disabled) once that exercise is the row's choice. **Nothing is applied without a
  tap**: a suggestion or a proposal never decides a row by itself.
- **History, not news.** Imported workouts count for History, "Last",
  the pre-fill and the picker's learning; `buildWorkoutReport` never marks one
  as a new record. Dates outside the current year show the year
  (`localDate`).
- **Tests.** Core: `ExportSectionsTest`, `JefitImportTest` and
  `ImportResolutionTest` against `core/src/jvmTest/resources/import/jefit/export-synthetic.csv`,
  a hand-written export in the real layout with made-up values (every quirk:
  sub-tables, lbs rows vs logs text, a log without rows, the 1970 session,
  loose logs, a stray header, warm-up and drop sets, cardio). **A real export
  is personal data and never goes in the repository**; `JefitRealExportTest`
  runs only with `TUNG_JEFIT_EXPORT=/path/to/export.csv` (`./gradlew :core:jvmTest
  --tests '*JefitRealExportTest' --rerun --no-daemon`) and prints the counts.
  `ImportSuggestionsTest` (the equipment words, the order, `nameUsage`, and
  the eleven names a real export left unmatched, printed with their top
  suggestions) and `AiMatchTest` (see "In-app AI (BYOK)").
  Data: `ImporterTest` (stamped and dirty, idempotent, undo with tombstones
  leaves other workouts, re-import after undo), `SyncMappingImportTest`,
  `DeleteRoutinesCommandTest` (`delete_routines`: one transaction, tombstones
  for routines, their exercises and program days, history kept, gone ids),
  `MigrationTest`, and `AiServiceTest`'s matching case. Server:
  `ImportColumnsTest`. Flows: `ImportFlowTest` (the picker answered through
  Robolectric's `receiveResult`, no app seam; "Delete routines from the last
  import" and undo after it; a suggestion chip and "Skip all
  remaining"; the assistant against a MockWebServer, "What is sent", the
  proposals and "Accept all high-confidence"; the confidence tags and
  Accept's style in the light and the dark theme; Accept collapsing a row,
  Change reopening it with the decision kept, the counter, and "Create all
  remaining" collapsing the rest). `AssistantFlowTest`
  checks that an `en-rSE` phone asks for English and an `sv` one for
  Swedish; core's `AiLanguageTest` the mapping and every request type.

## In-app AI (BYOK)

Issue #46, step 12 of `docs/architecture.md` ("AI features"), BYOK mode
only: the three MCP prompts ("Suggest a program", "Next workout", "Where have
I got stuck") as buttons on the phone, answered by the user's own provider
with the user's own key. **There is no server-side key, no operator key and no
free budget**: the call goes from the phone straight to the provider, and
tung-server never sees the key or the request.

- **core, `core/.../core/ai/`** (pure, tested): `AiRequest` (`Program`,
  `NextWorkout`, `Plateaus`, `FreeText` from voice, and `MatchExercises` from
  the import review, below); `AiContext`, built by `buildAiContext` from plain
  shapes: finished workouts of the last 90 days (at most 30, logged sets only,
  exercise key and name), routines, programs, today's date and weekday, the
  core catalogue's keys, unit kg, and `equipment` (the "My equipment" keys)
  only when the user has narrowed it; then "Suggest a program" and "Next
  workout" are told to use only exercises done with it. The shape has no field for an email, a name,
  a device id, a server URL or a note, so none can be sent. `buildAiPrompt`
  (English, framed like the server's MCP prompts, the answer's JSON shape in
  the system message, the app's language for the text fields).
  **The language is the one the app displays, by name** (issue #75): every
  prompt (`rules`, `matchRules`) says "in English" or "in Swedish" through
  `aiLanguageName(tag)` (`AiLanguage.kt`), which reads only the tag's
  language subtag and knows only the languages the app ships, anything else
  being English (the app's own fallback). The app passes the string resource
  `ai_language_tag` (`en` in `values/`, `sv` in `values-sv/`), so it is by
  construction the language of the resources on screen; never
  `locales[0].toLanguageTag()`: a Pixel in `en-SE-u-fw-mon-…` shows English,
  and a model handed that tag wrote Swedish. A new app language adds its
  `ai_language_tag` and a name in `aiLanguageName`.
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
  private IP literals, for Ollama on the home network). Both ask for
  `max_tokens` = `AI_MAX_TOKENS` = 16000 (the Test in Settings asks for 1):
  Claude 5.x thinks before it answers (adaptive thinking is on when
  `thinking` is omitted, its blocks come back with empty text), and at 4096
  the thinking could use the whole limit and leave no text (issue #73). To an
  Anthropic model id matching `ANTHROPIC_EFFORT_MODELS`
  (`^claude-(haiku|sonnet|opus|fable|mythos)-5`) the request also carries
  `output_config: {effort: "low"}` (top level); older models (Haiku 4.5,
  Sonnet 4.5) reject the field, so they never get it. **Never send
  `thinking: {type: "disabled"}`**: a 400 on Opus 5.5 and Sonnet 5.5. A
  response is read with `aiReply` → `AiReply`: `Text` (thinking blocks
  skipped), `OutOfRoom` (no text with `stop_reason: max_tokens` /
  `finish_reason: length`), `Declined` (`stop_reason: refusal`, OpenAI's
  `refusal` or `content_filter`), else `Empty`; `AiWireTest` has recorded
  responses for each (`anthropic-thinking-*.json`, `anthropic-refusal.json`,
  `openai-length.json`).
- **The fourth request, `MatchExercises(names, candidates, catalogue)`**
  (`AiMatch.kt`, issue #71): the import review's unmatched names, each with
  its offline candidates (`importSuggestions`), and the core catalogue as
  `AiCatalogueEntry` (key, English name, equipment). Its context is
  `AiMatchContext` (`buildAiMatchContext`: the names, at most
  `AI_MATCH_CANDIDATES` = 5 candidates each by `key` or `extended_id` with
  name and equipment, the catalogue) and nothing else: no field for history,
  an account, a device or a server, a custom exercise is never a candidate,
  and `buildAiPrompt` hands this request to `buildAiMatchPrompt` whatever
  training context it is given (`prepare` does not even read it). The prompt
  (English, strict JSON `{matches: [{name, ref: {key}|{extended_id}|null,
  confidence: high|medium|low, note}]}`) carries the alias table's load rule:
  never map across load types, prefer the same equipment, else a low
  confidence or null. Validation (`parseMatches`, `resolveAiAnswer`): a name
  not asked about, a second entry for a name, a key outside the catalogue and
  an extended id that does not exist are dropped (counted); null is "no good
  match" and kept; an unknown confidence reads as low. The answer is
  `AiProposal.Matches` (`ProposedMatch` per name), shown on the review's rows
  and never applied by itself. `AiMatchTest` has recorded responses of both
  providers (`src/test/resources/ai/*-match.json`).
- **data, `data/.../ai/`**: `AiSettings` (provider, base URL, model, JSON
  mode, "first request shown" and "always show" flags in the `settings`
  DataStore; the key in EncryptedSharedPreferences `ai_key` through
  `TokenStore`, excluded from backups like `sync_token`); `AiClient` (its own
  Ktor client: 120 s, as answers are not streamed and a model may think
  first; no retries, no redirects, statuses mapped to `AiFailure`);
  `AiService` (`prepare` builds the context and the request
  without the key, `prepareMatch` the import review's matching request,
  `send` sends exactly that body with the key added to the
  header and maps `AiReply.OutOfRoom` to `AiFailure.OUT_OF_ROOM` ("The model
  ran out of room before answering"), `Declined` to `DECLINED` ("The model
  declined this request") and `Empty` to `NO_ANSWER`, shown like every other
  failure through `aiFailureText`, `confirm` runs the commands, `test` sends a one-token request with
  no training data). `TungContainer(createAiKeyStore = …)` is the seam;
  `FlowTestApp.aiKeys` keeps the key in memory.
- **app**: the Assistant screen (`ui/ai/`), reached from the **last row on
  Home**; Settings → Assistant is `ui/settings/AiSection.kt`, one
  call in `AssistantSettingsScreen` (the Assistant screen's "Open settings" goes straight there). A program proposal is confirmed with "Save
  program": `SaveRoutinePlan` per routine, then `CreateProgram` (rotating)
  and `AddProgramDay` in order; the program is not activated. A next workout
  is `ApplyPlan`. Plateaus are only shown.

**Non-negotiable rules.** A call happens only on a tap, never in the
background (no WorkManager, no retry). The first request ever is shown in
full ("What is sent", the pretty-printed body and the URL; the key is in a
header and never shown) before it is sent, and the menu's "Show what is sent"
reviews every request while it is on; the import review's matching request
follows the same two rules. What is sent is training data and settings only,
and for matching only the names and the catalogue. A suggested exercise that is not in the catalogue is dropped,
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
  0–999), `Done` is exactly the Log set button (`LogSet`, which adds no set,
  rest timer, or the reps sheet when the set has none; with no set left to log
  it adds nothing either and echoes "There is no set to log", while the
  button offers Add set), `SameAgain` copies the set before it in
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
  built per user by `CommandService`) is core's `CommandExecutor` over
  `command/ExposedCommandStore` (see "Commands", "One rule, in core"):
  **one transaction** per command starting with `lockUser`, names resolved
  by core over `ServerExerciseResolver` (catalogue rows shadowed by the
  user's own, `core.json`'s names in every language, the extended catalogue
  from `/catalog/extended.json`), the rules over `ExposedCommandTx` (its
  pre-fill reads `lastSessionIn`, i.e. `/last`'s query, and its `setRest`
  makes the user's copy of a catalogue exercise through
  `copyCatalogueExercise`, shared with `PATCH /exercises/{id}`), workouts
  started by `agent`; the handler itself only caps names for the REST API
  (`MAX_COMMAND_NAME`) before anything runs and wakes the user's other
  devices after a commit. Its own changes stamp `GREATEST(now, updated_at + 1)`;
  deletes are tombstones. It answers `Rejected` for ids that do not exist,
  as the app does.
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

## Web

`web/` is the web app (the path was decided 2026-10-30, recorded in
`docs/architecture.md`, "Open decisions"): Kotlin/JS in the browser,
installable as a PWA and working without a network, over the same `core` as
everything else. Package `io.github.kanylbullen.tung.web`, UI in Compose
HTML (DOM elements as composables with the Compose runtime; the
`org.jetbrains.compose.runtime:runtime` and `org.jetbrains.compose.html:html-core`
libraries, no `org.jetbrains.compose` plugin). Build and layout:
`web/README.md`. What runs today: sign in, the first sync, History.

- **The log lives in memory and is persisted to IndexedDB**
  (`store/TungStore.kt`, `store/IndexedDb.kt`). The eight tables hold the
  **wire rows themselves** (`SyncExercise`, `SyncSet`, …, core's protocol
  types) with a dirty flag, so there are no entity classes of their own and
  nothing to map at sync; a tombstone is a (table, id, deletedAt, dirty).
  At start everything is read once (`IndexedDbStore.loadInto`); every
  change goes to memory first and appends `Write`s to a journal that is
  written in **one IndexedDB transaction issued whole** (`write`). The
  reason: an IndexedDB transaction closes as soon as control returns to the
  event loop with no request pending, and a coroutine resumed through
  Kotlin/JS's dispatcher does exactly that, so a rule that read IndexedDB
  row by row between suspensions would lose its transaction. `version`
  ticks after each batch (`bump`) and a screen recomputes what it shows
  (`remember(version)`). The catalogue's seventy are seeded at start from
  `catalog/core.json` (shipped as a resource) with the phone's ids and
  `updatedAt` 0, not dirty (`seedCatalogue`).
- **Sync is the phone's** (`sync/WebSync.kt`): core's `SyncPlanner` and
  `SyncApplier` with `fetch` against `POST /api/v1/sync`, the same loop as
  `data/src/commonMain/.../sync/SyncClient.kt` (chunks, the cursor after each response,
  pulling again while a response is full, two retries on 5xx and network, a
  401 signs out), and `TungStore.apply` is `SyncStore.apply` over memory:
  parents first, a row completed from the local one when the wire row
  leaves a required field null (`TableSpecs`, the phone's `SyncMapping`),
  the null-keeps fields (`started_by`, Health Connect, the import), a
  parent that is missing skips the row, an exercise in use is not deleted,
  a deleted parent takes its children (Room's cascades, by hand), and the
  dirty flags of a request's rows are cleared only with the `updatedAt`
  they went with. The loop is the one piece the phone has in `data/` and
  the web here; now that `data/` is multiplatform (#105) it moves into
  core behind a transport interface (agreed with that work, a follow-up).
  **The server must list `X-Tung-Device` in its CORS headers** (it does
  since this module: `Application.kt`, `allowHeader(DEVICE_HEADER)`); against
  an older server the browser's preflight is refused and every sync reads
  "could not reach the server" while sign-in works, which the phone never
  sees because it sends no preflight. Deploy the server before the web.
  Triggers: app start when signed in, after sign-in, "Sync now", the
  `online` event and the page becoming visible. No periodic sync yet. One
  request may take `REQUEST_TIMEOUT_MILLIS` (120 s, the phone's), then it
  is aborted and counts as unreachable (`Http.kt`).
- **Account** (`sync/WebAuth.kt`, `sync/WebSettings.kt`): email and
  password against `/api/v1/auth/login`, the default server
  `DEFAULT_SERVER_URL` in `WebContainer.kt` (the phone's
  `BuildConfig.DEFAULT_SERVER_URL`), `checkServerUrl` as the gate. The
  server, email, token, cursor, last result and device id (`X-Tung-Device`)
  are strings in IndexedDB's `meta` store. **The token is in IndexedDB like
  everything else**: a browser has no Keystore, and any script of the
  origin could read it wherever it was. Signing out forgets the token here
  and on the server and keeps the log in the browser, as the phone does;
  whether a shared computer should rather clear it is an open question.
  Sign in marks everything dirty except the untouched catalogue rows
  (`nameKey` set and `updatedAt` 0), as `SyncDao.markExercisesDirty` does.
- **No commands yet.** Nothing on the web writes training data; the first
  write screen brings a `CommandTx` over `TungStore` for core's
  `CommandExecutor` (one transaction = mutate memory, then one journal
  write), never a path of its own.
- **Strings** are Kotlin (`i18n/Strings.kt`: `English`, `Swedish`,
  `stringsFor(navigator.language)`), the phone's wording where the screen is
  the same; a new language is a new object. Exercise names come from the
  catalogue's `name.<lang>` in `core.json` (`exerciseDisplayName`), so the
  `ex_*` resources are not generated for the web. Dates and numbers are the
  browser's `Intl` (`ui/Format.kt`).
- **PWA** (`src/jsMain/resources/`): `index.html` loads `tung.js` (the
  webpack output name) and `styles.css` (the colour tokens as CSS
  variables, dark and light by `prefers-color-scheme`, Archivo from the
  phone's one `archivo_variable.ttf`, copied at build with its OFL);
  `manifest.webmanifest` (relative `start_url`, so a subpath works) and
  the icons (the launcher's plate as PNG, generated once with Pillow);
  `sw.js` precaches the shell and serves it cache-first, never touching
  another origin or anything but GET, and its cache name carries
  `@buildId@` (the commit and the build time, replaced by
  `jsProcessResources`), so a new build drops the old cache. The
  catalogue, the typeface and the licence are copied into the site from the
  repository's one copy of each by the build, never duplicated in `web/`.
- **Versions.** Compose Multiplatform **1.10.x is the last line built with
  Kotlin 2.2** (1.11 needs 2.3); raise it with Kotlin. Kotlin/JS downloads
  Node.js and Yarn into `~/.gradle` and pins npm packages in the root's
  `kotlin-js-store/yarn.lock`, which is committed.
- **Tests** (`web/src/jsTest`, Karma in headless Chrome, `:web:jsBrowserTest`):
  `TungStoreTest` (the merge rules), `HistoryRowsTest`, `IndexedDbStoreTest`
  (the real IndexedDB: written, reopened, read back), `WebSyncTest` (the
  loop and sign-in against a scripted transport: `WebSync` and `WebAuth`
  take a `PostJson`, `fetch` by default) and `StringsTest` (the tag mapping
  and the browser's formats). No screen test yet.
- **Not there yet:** every other screen, commands, a periodic sync, a
  second tab (two tabs each hold the log in memory; the last write per row
  wins at the next load), Sign in with Google.
- **Hosting**: GitHub Pages, `https://kanylbullen.github.io/tung/`,
  published from `main` by `.github/workflows/web-pages.yml` (its own
  workflow so only its deploy job gets `pages: write`; Pages' source is
  "GitHub Actions"). Every path falls back to the shell (`404.html` is a
  copy of `index.html`). The site has no server of its own: everything
  relative, so it works under `/tung/` or any other path.

## Building

```sh
cd /home/xup/tung
export JAVA_HOME=/home/xup/android/jdk ANDROID_HOME=/home/xup/android/sdk ANDROID_SDK_ROOT=/home/xup/android/sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug test --no-daemon
```

`test` runs `:core:jvmTest` (through its `test` alias), the unit tests of
`data`, `app` and `wear` (AGP runs them for both the debug and the release
variant) and `:server:test`. Only the core tests, fast and without AGP:
`./gradlew :core:jvmTest --no-daemon`. Results land in
`core/build/test-results/jvmTest/` and
`<module>/build/test-results/testDebugUnitTest/`. `test` compiles no iOS
code: `./gradlew :core:compileKotlinIosArm64 :data:compileKotlinIosArm64
--no-daemon` is the check CI adds, the one that catches a JVM-only type in
either `commonMain` (the Kotlin/Native compiler lands in `~/.konan`).
The web app is not in `test` either: `./gradlew :web:jsBrowserDistribution
:web:jsBrowserTest --no-daemon` builds its site and runs its tests in
headless Chrome (CI's `web` job; see "Web").

**On a phone, install `dogfood`, not `debug`:** `./gradlew :app:assembleDogfood`
gives `app/build/outputs/apk/dogfood/app-dogfood.apk`, release code (R8, not
debuggable) signed with the debug key, so `adb install -r` replaces a debug
build and keeps the data (with the release key in the environment, below, it
is signed with that instead, so it replaces a Play install; `adb install -r -d`,
its versionCode is lower). A debug build of a Compose app is several times
slower: on the Note9, scrolling the Exercises tab had 34 % janky frames and a
90th percentile of 150 ms as debug, 11 % and 32 ms as dogfood (2026-10-21).
Release has R8 on (`isMinifyEnabled`, `isShrinkResources`); the app's own
rules are in `app/proguard-rules.pro` and should stay empty unless the app
itself reflects on a class. The flow tests run unminified, so after a change
to dependencies or reflection, start the dogfood build once and sync.

APKs: `app/build/outputs/apk/debug/app-debug.apk` and
`wear/build/outputs/apk/debug/wear-debug.apk` (`:wear:assembleDebug`). The first build downloads
dependencies and takes a few minutes.

**A Play build is `scripts/release.sh`** (the console side: `deploy/play.md`).
The release inputs live in the root `build.gradle.kts` and reach `app/` and
`wear/` through `rootProject.extra`: the one release key from
`TUNG_KEYSTORE`, `TUNG_KEYSTORE_PASSWORD`, `TUNG_KEY_ALIAS` and
`TUNG_KEY_PASSWORD` (environment or `-P`; blank counts as missing; the key
is outside the checkout, `*.jks` is gitignored), and the build number from
`TUNG_VERSION_CODE`, which the script derives from the number of commits on
`main`. The phone's versionCode is `number × 10 + 1`, the watch's
`number × 10 + 2` (one applicationId, so Play wants distinct codes), and a
build without a number is number 1. The root's `releaseGate` refuses
`assembleRelease` and `bundleRelease` without the key or the number before
anything is packaged; the release variant's unit tests, which CI runs
without a key, are not affected. The watch is signed with the same key as the
phone, or the Data Layer will not pair them. **Play requires targetSdk 36
since 2026-08-31** (Wear OS 35), and the phone app meets it: AGP 8.9.1,
compileSdk 36 and build-tools 36.0.0 in every Android module, targetSdk 36 in
`app/` and, on purpose, 35 in `wear/`; CI reads both numbers back from the
built APKs. What Android 16 changes for an app targeting 36, and how each
item was checked against this app, is in `deploy/play.md`, "targetSdk": in
short, edge-to-edge was already on and predictive back needs nothing. **The
flow tests run under Robolectric SDK 34 and see neither**, so a change to
insets or to back handling is checked on a phone.

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
  the `data-fixtures` module, none by default), and Health Connect
  (`FlowTestApp.healthConnect`, a `FakeHealthConnect` from the same module:
  available, nothing granted, nothing to read by default), and the
  assistant's API key in memory (`FlowTestApp.aiKeys`), and Account's default
  server (`FlowTestApp.defaultServer`, a closed localhost port; a test that
  wants the default to answer points it at its `FakeSyncServer`). Those six
  `TungContainer` parameters, and `TungApp`'s `createSpeechInput`,
  `createGoogleSignIn` and `defaultServerUrl`, are the only test seams in production code. Sync tests talk to `FakeSyncServer` (the `data-fixtures`
  module, `data-fixtures/src/main/kotlin/.../sync/`, the protocol and the link routes on a
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
- **A value in the `settings` DataStore is checked with
  `waitUntilStored(expected) { … }`** (`FlowTest`), never read once with
  `runBlocking { ….first() }` right after a tap: a screen can show a choice
  before DataStore has written it (Training's rest is the screen's own
  value), and a read that throws counts as not yet.
- **`addExercise(query, name)` dismisses the first-time weight sheet** an
  exercise with no history opens (issue #55, `dismissSheet` runs the sheet
  scrim's click action, "Close sheet" in the app's language), so flows that add an exercise and then
  edit its cells read as before; `addExerciseFirstTime` keeps it open
  (`AddExerciseFlowTest`).
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
| danger | `#C9413A` | `#B3261E` | Fill only, white on it: the Delete a swipe reveals behind a set row |

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
- **`kotlinOptions {}` is gone.** The Android modules set their bytecode
  target in a top-level `kotlin { compilerOptions { jvmTarget } }` block
  (`data/`, multiplatform, inside `kotlin { androidTarget { compilerOptions } }`),
  the DSL Kotlin 2.2 keeps; the old block inside `android {}` is an error.
  KSP runs as KSP2 (the default since KSP 2.0): Room 2.6.x's generator
  crashed under it (`unexpected jvm signature V`), which is why Room is 2.8.
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
  open. Version + 1, the committed schema JSON, a `MigrationStep` in
  `DatabaseSchema.MIGRATIONS` and a `MigrationTest` case, always; see "Sync",
  "Schema".
