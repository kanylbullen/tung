package io.github.kanylbullen.tung.data

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.kanylbullen.tung.ai.AiClient
import io.github.kanylbullen.tung.ai.AiService
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.ai.aiHttpClient
import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.health.HealthConnectGateway
import io.github.kanylbullen.tung.health.HealthConnectRecorder
import io.github.kanylbullen.tung.health.HealthConnectSettings
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.importing.ImportHistory
import io.github.kanylbullen.tung.importing.ImportService
import io.github.kanylbullen.tung.importing.Importer
import io.github.kanylbullen.tung.ongoing.OngoingWorkoutNotifier
import io.github.kanylbullen.tung.ongoing.ongoingWorkouts
import io.github.kanylbullen.tung.push.AndroidPushEnvironment
import io.github.kanylbullen.tung.push.PushEnvironment
import io.github.kanylbullen.tung.push.PushRegistrar
import io.github.kanylbullen.tung.sync.EncryptedTokenStore
import io.github.kanylbullen.tung.sync.SyncAccount
import io.github.kanylbullen.tung.sync.SyncClient
import io.github.kanylbullen.tung.sync.SyncScheduler
import io.github.kanylbullen.tung.sync.SyncSettings
import io.github.kanylbullen.tung.sync.SyncStore
import io.github.kanylbullen.tung.sync.TokenStore
import io.github.kanylbullen.tung.sync.WorkManagerSyncScheduler
import io.github.kanylbullen.tung.sync.syncHttpClient
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock

/**
 * Hand-rolled dependency container, shared by the phone app and the watch app.
 * No DI framework — there is one database per device.
 *
 * [createDatabase] is the main seam: the apps use the file-backed default, the
 * JVM flow tests hand in an in-memory database. [clock] says what day it is
 * for Home's "Today"; the app uses the system clock and zone, the flow tests
 * one they can set to a given weekday. [createTokenStore] keeps the sync
 * token in EncryptedSharedPreferences; the flow tests use one in memory,
 * because Robolectric has no Android Keystore; [createAiKeyStore] does the
 * same for the in-app assistant's API key. [createHealthConnect] is
 * Health Connect: the phone hands in the real gateway, the watch none, the
 * flow tests a fake.
 *
 * The apps never reach Room themselves: they read the repositories' Flows and
 * change data through [commands].
 */
class TungContainer(
    context: Context,
    val clock: Clock = Clock.systemDefaultZone(),
    createDatabase: (Context) -> TungDatabase = TungDatabase::build,
    createTokenStore: (Context) -> TokenStore = ::EncryptedTokenStore,
    /** Stamped on the workouts this device's commands start (`core.StartedBy`): `phone`, or `watch` on the watch. */
    private val startedBy: String = StartedBy.PHONE,
    /** Which push transports this device has; the flow tests hand in a fake (no Play services, no distributor there). */
    createPushEnvironment: (Context) -> PushEnvironment = ::AndroidPushEnvironment,
    /** Health Connect (CLAUDE.md, "Health Connect"): the phone's `TungApp` passes the real gateway; null (the watch) means none. */
    createHealthConnect: (Context) -> HealthConnectGateway? = { null },
    /** The in-app assistant's API key (`ai_key`); the flow tests keep it in memory, like the sync token. */
    createAiKeyStore: (Context) -> TokenStore = { EncryptedTokenStore(it, AiSettings.KEY_FILE, AiSettings.KEY_NAME) },
) {
    private val context: Context = context.applicationContext ?: context

    /** For work that outlives a screen (sync triggers); never cancelled, like the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database: TungDatabase by lazy { createDatabase(this.context) }
    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(database) }
    val routineRepository: RoutineRepository by lazy { RoutineRepository(database) }
    val programRepository: ProgramRepository by lazy { ProgramRepository(database) }
    val exerciseRepository: ExerciseRepository by lazy { ExerciseRepository(database.exerciseDao()) }
    val extendedCatalog: ExtendedCatalog by lazy { ExtendedCatalog.fromAssets(this.context) }

    /** `core.json` from the assets, read the first time a name is resolved or the muscles are needed. */
    private val coreCatalog: List<CoreExercise> by lazy {
        this.context.assets.open(CORE_CATALOG_ASSET).use { CoreCatalog.parse(it.reader().readText()) }
    }

    /** The core catalogue's names in every language, by key. */
    private val catalogueNames: Map<String, List<String>> by lazy {
        coreCatalog.associate { it.key to it.name.values.toList() }
    }

    /** Names to exercises, for the command handler and the assistant's answers (core's `resolveRef`). */
    private val exerciseResolver: AppExerciseResolver by lazy {
        AppExerciseResolver(database.exerciseDao(), { catalogueNames }, extendedCatalog)
    }

    /**
     * Import from other apps (CLAUDE.md, "Import"): the core adapters, the
     * alias tables in the assets (`aliases/<source>.json`), this device's
     * exercises for resolution, and the [Importer]'s one bulk write.
     */
    val imports: ImportService by lazy {
        ImportService(
            importer = Importer(database, workoutRepository, exerciseRepository, routineRepository, extendedCatalog),
            resolver = exerciseResolver,
            aliases = { source ->
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    runCatching {
                        this@TungContainer.context.assets.open("aliases/$source.json").use { ImportAliases.parse(it.reader().readText()) }
                    }.getOrDefault(ImportAliases.EMPTY)
                }
            },
            zone = { clock.zone },
            history = ImportHistory(settings),
        )
    }

    /** What each catalogue exercise trains (`core.MuscleIndex`), for the picker's suggestions. */
    val catalogueMuscles: CatalogueMuscles by lazy { CatalogueMuscles({ coreCatalog }, extendedCatalog) }

    /** What each catalogue exercise is (`core.catalog.CatalogueIndex`: muscles, instructions), for the exercise page. */
    val catalogueDetails: CatalogueDetails by lazy { CatalogueDetails({ coreCatalog }, extendedCatalog) }

    /**
     * Every change to training data goes through here: the screens' ViewModels
     * (and the watch) call [CommandHandler.execute] and never a repository for
     * a mutation. Reads stay on the repositories' Flows.
     */
    val commands: CommandHandler by lazy {
        AppCommandHandler(
            db = database,
            workouts = workoutRepository,
            exercises = exerciseRepository,
            routines = routineRepository,
            programs = programRepository,
            resolver = exerciseResolver,
            extendedCatalog = extendedCatalog,
            onWorkoutFinished = ::workoutFinished,
            startedBy = startedBy,
        )
    }

    /**
     * After `FinishWorkout`: Health Connect first (write the session, read the
     * vitals, each only when opted in), so what it stores goes up with the
     * sync that follows. [workoutId] is null when the workout was discarded.
     */
    private fun workoutFinished(workoutId: String?) {
        val recorder = healthConnectRecorder
        if (workoutId == null || recorder == null) {
            syncAccount.requestSync()
            return
        }
        appScope.launch {
            recorder.onWorkoutFinished(workoutId)
            syncAccount.requestSync()
        }
    }

    // --- Health Connect (health/), the phone only ---
    /** Null on a device without the integration (the watch); the Settings section then says it is unavailable. */
    val healthConnect: HealthConnectGateway? by lazy { createHealthConnect(this.context) }
    val healthConnectSettings: HealthConnectSettings by lazy { HealthConnectSettings(settings) }
    private val healthConnectRecorder: HealthConnectRecorder? by lazy {
        healthConnect?.let {
            HealthConnectRecorder(it, healthConnectSettings, workoutRepository, routineRepository) {
                this.context.getString(R.string.health_session_title)
            }
        }
    }

    /**
     * The ongoing notification of the workout in progress in this database
     * (CLAUDE.md, "Ongoing workout and push"). The phone starts it from
     * `TungApp.onCreate`; the watch builds its own over the state it shows.
     */
    val ongoingWorkout: OngoingWorkoutNotifier by lazy {
        OngoingWorkoutNotifier(this.context, ongoingWorkouts(this.context, workoutRepository, routineRepository), appScope)
    }

    /**
     * Small settings and state outside Room: the rest timer and its default,
     * sync's state. One instance per file, built here rather than with the
     * `preferencesDataStore` delegate, which is a process-wide singleton.
     */
    val settings: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create { this.context.preferencesDataStoreFile("settings") }
    }
    val restTimer: RestTimerController by lazy { RestTimerController(this.context, settings) }

    /** "My equipment": what the Exercises tab, the picker and the assistant keep to. Not synced. */
    val equipmentSettings: EquipmentSettings by lazy { EquipmentSettings(settings) }

    // --- sync with tung-server (sync/, CLAUDE.md "Sync") ---
    private val tokenStore: TokenStore by lazy { createTokenStore(this.context) }
    val syncSettings: SyncSettings by lazy { SyncSettings(settings) }
    private val syncStore: SyncStore by lazy { SyncStore(database) }
    private val syncScheduler: SyncScheduler by lazy { WorkManagerSyncScheduler(this.context) }
    val syncClient: SyncClient by lazy {
        SyncClient(syncStore, syncSettings, tokenStore, syncHttpClient(), onSignedOut = { syncAccount.signedOutByServer() })
    }
    val syncAccount: SyncAccount by lazy {
        SyncAccount(
            syncClient, syncSettings, tokenStore, syncStore, syncScheduler, appScope,
            beforeSignOut = { url, token -> push.onSignOut(url, token) },
        )
    }

    // --- push (push/, CLAUDE.md "Ongoing workout and push"): started by the phone, never by the watch ---
    private val pushEnvironment: PushEnvironment by lazy { createPushEnvironment(this.context) }
    val push: PushRegistrar by lazy {
        PushRegistrar(pushEnvironment, syncClient, settings, syncAccount.state.map { it.signedIn }, appScope)
    }

    // --- the in-app assistant with the user's own key (ai/, CLAUDE.md "In-app AI (BYOK)"): only on a tap ---
    private val aiKeyStore: TokenStore by lazy { createAiKeyStore(this.context) }
    val aiSettings: AiSettings by lazy { AiSettings(settings, aiKeyStore) }
    val ai: AiService by lazy {
        AiService(
            settings = aiSettings,
            client = AiClient(aiHttpClient()),
            workouts = workoutRepository,
            routines = routineRepository,
            programs = programRepository,
            resolver = exerciseResolver,
            catalogueKeys = { coreCatalog.map { it.key } },
            commands = commands,
            clock = clock,
            equipment = { equipmentSettings.current() },
            catalogue = { io.github.kanylbullen.tung.core.ai.aiCatalogue(coreCatalog) },
        )
    }

    private companion object {
        /** `catalog/core.json`, shipped as an asset like the extended catalogue. */
        const val CORE_CATALOG_ASSET = "core.json"
    }
}

/**
 * What the shared data layer needs from the application it runs in: the
 * phone's `TungApp` and the watch's `TungWearApp` implement it. [SyncWorker][io.github.kanylbullen.tung.sync.SyncWorker]
 * finds the container through it, and the rest timer's alert
 * ([RestAlerts][io.github.kanylbullen.tung.timer.RestAlerts]) asks it whether
 * an activity is on screen and what a "Rest over" notification opens.
 */
interface TungHost {
    val container: TungContainer

    /** Whether an activity of the app is on screen; a finished rest only posts a notification when not. */
    val isInForeground: Boolean

    /** The small icon of the app's notifications. */
    @get:DrawableRes
    val notificationIcon: Int

    /** What tapping "Rest over" or the ongoing notification opens: the app's main activity, told to show [workoutId]. */
    fun openWorkoutIntent(workoutId: String): Intent

    /** What the ongoing notification's "Finish" opens: [workoutId] with the finish dialog. Never finishes by itself. */
    fun finishWorkoutIntent(workoutId: String): Intent = openWorkoutIntent(workoutId)
}
