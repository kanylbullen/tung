@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.ai.AiClient
import io.github.kanylbullen.tung.ai.AiService
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.ai.aiHttpClient
import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.ai.aiCatalogue
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.importing.ImportAliases
import io.github.kanylbullen.tung.importing.ImportHistory
import io.github.kanylbullen.tung.importing.ImportService
import io.github.kanylbullen.tung.importing.Importer
import io.github.kanylbullen.tung.push.NoPush
import io.github.kanylbullen.tung.push.PushControl
import io.github.kanylbullen.tung.sync.IosSyncScheduler
import io.github.kanylbullen.tung.sync.KeychainTokenStore
import io.github.kanylbullen.tung.sync.SyncAccount
import io.github.kanylbullen.tung.sync.SyncClient
import io.github.kanylbullen.tung.sync.SyncSettings
import io.github.kanylbullen.tung.sync.SyncStore
import io.github.kanylbullen.tung.sync.syncHttpClient
import io.github.kanylbullen.tung.timer.IosRestTimer
import io.github.kanylbullen.tung.timer.RestTimerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * The iPhone's container, the counterpart of the phone's `TungContainer` and
 * in the same layer: the database and the settings in the app's Application
 * Support directory, the session token and the assistant's key in the
 * Keychain, the rest timer over local notifications, sync while the app runs.
 * Workouts it starts are `phone`'s: an iPhone is a phone, and a server that
 * does not know a new `started_by` value refuses the sync. No push and no
 * health store yet.
 *
 * What lives in the app bundle comes in as functions, so this module never
 * reaches into `ui`: [readCatalogue] reads a catalogue file by its path under
 * `catalog/` (`core.json`, `extended.json`, `aliases/<source>.json`; ui ships
 * them as iOS-only Compose Resources), and [restOverTitle] and [restOverText]
 * are the "Rest over" notification's text in the app's language.
 */
class IosContainer(
    private val readCatalogue: suspend (path: String) -> String,
    private val restOverTitle: suspend () -> String,
    private val restOverText: suspend () -> String,
) : TungServices {

    /** For work that outlives a screen (sync); never cancelled, like the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val directory: String by lazy { iosAppDirectory() }
    private val database: TungDatabase by lazy { TungDatabase.build("$directory/tung.db") }
    private val settings by lazy { iosSettingsDataStore(directory) }

    override fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()
    override fun today(): LocalDate = Clock.System.todayIn(zone())
    override fun zone(): TimeZone = TimeZone.currentSystemDefault()

    override val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(database) }
    override val routineRepository: RoutineRepository by lazy { RoutineRepository(database) }
    override val programRepository: ProgramRepository by lazy { ProgramRepository(database) }
    override val exerciseRepository: ExerciseRepository by lazy { ExerciseRepository(database) }
    override val extendedCatalog: ExtendedCatalog by lazy { ExtendedCatalog({ readBlocking("extended.json") }) }

    /**
     * `core.json`, read the first time a name is resolved or the muscles are
     * needed. Warmed on a background thread when the container is made (see
     * `init`), so the first exercise name on screen does not wait for the file.
     */
    private val coreCatalog: List<CoreExercise> by lazy { CoreCatalog.parse(readBlocking("core.json")) }

    private val catalogueNames: Map<String, List<String>> by lazy {
        coreCatalog.associate { it.key to it.name.values.toList() }
    }

    /** Each catalogue key's name by language (`core.json`'s `name`). */
    private val namesByKey: Map<String, Map<String, String>> by lazy { coreCatalog.associate { it.key to it.name } }

    init {
        appScope.launch { coreCatalog }
    }

    /** The catalogue exercise [key] in the app's language ([iosAppLanguage]), else English; null for a key not in `core.json`. */
    fun catalogueName(key: String): String? {
        val names = namesByKey[key] ?: return null
        return names[iosAppLanguage()] ?: names["en"]
    }

    private val exerciseResolver: AppExerciseResolver by lazy {
        AppExerciseResolver(database.exerciseDao(), { catalogueNames }, extendedCatalog)
    }

    override val imports: ImportService by lazy {
        ImportService(
            importer = Importer(database, workoutRepository, exerciseRepository, routineRepository, extendedCatalog),
            resolver = exerciseResolver,
            aliases = { source ->
                runCatching { ImportAliases.parse(readCatalogue("aliases/$source.json")) }.getOrDefault(ImportAliases.EMPTY)
            },
            zone = ::zone,
            history = ImportHistory(settings),
        )
    }

    override val catalogueMuscles: CatalogueMuscles by lazy { CatalogueMuscles({ coreCatalog }, extendedCatalog) }
    override val catalogueDetails: CatalogueDetails by lazy { CatalogueDetails({ coreCatalog }, extendedCatalog) }

    override val commands: CommandHandler by lazy {
        AppCommandHandler(
            workouts = workoutRepository,
            resolver = exerciseResolver,
            onWorkoutFinished = { syncAccount.requestSync() },
            startedBy = StartedBy.PHONE,
        )
    }

    override val restTimer: RestTimerStore by lazy {
        IosRestTimer(
            settings,
            title = restOverTitle,
            body = restOverText,
        )
    }
    override val equipmentSettings: EquipmentSettings by lazy { EquipmentSettings(settings) }
    override val progressSettings: ProgressSettings by lazy { ProgressSettings(settings) }
    override val pastWorkouts: PastWorkoutSettings by lazy { PastWorkoutSettings(settings) }

    // --- sync with tung-server, while the app runs ---
    private val tokenStore by lazy { KeychainTokenStore(KEYCHAIN_SERVICE, "sync_token") }
    private val syncSettings: SyncSettings by lazy { SyncSettings(settings) }
    private val syncStore: SyncStore by lazy { SyncStore(database) }
    private val syncClient: SyncClient by lazy {
        SyncClient(
            syncStore, syncSettings, tokenStore, syncHttpClient(),
            onSignedOut = { syncAccount.signedOutByServer() },
            language = ::iosAppLanguage,
        )
    }
    override val syncAccount: SyncAccount by lazy {
        SyncAccount(
            syncClient, syncSettings, tokenStore, syncStore,
            IosSyncScheduler(appScope) { syncClient.syncOnce() },
            appScope,
        )
    }
    override val push: PushControl by lazy { NoPush() }

    // --- the in-app assistant with the user's own key ---
    private val aiKeyStore by lazy { KeychainTokenStore(KEYCHAIN_SERVICE, "ai_key") }
    override val aiSettings: AiSettings by lazy { AiSettings(settings, aiKeyStore) }
    override val ai: AiService by lazy {
        AiService(
            settings = aiSettings,
            client = AiClient(aiHttpClient()),
            workouts = workoutRepository,
            routines = routineRepository,
            programs = programRepository,
            resolver = exerciseResolver,
            catalogueKeys = { coreCatalog.map { it.key } },
            commands = commands,
            now = ::nowMillis,
            zone = ::zone,
            equipment = { equipmentSettings.current() },
            catalogue = { aiCatalogue(coreCatalog) },
        )
    }

    /** The app came to the front: keep the periodic sync while signed in, and sync now (the phone's `onStart`). */
    fun onForeground() {
        syncAccount.restoreSchedule()
        syncAccount.requestSync()
    }

    private companion object {
        /** The Keychain service of the app's secrets: its bundle id. */
        const val KEYCHAIN_SERVICE = "io.github.kanylbullen.tung"
    }

    /**
     * A catalogue file for the lazy loaders that are not suspending (core's
     * `ExtendedCatalog` reads on its own dispatcher, [coreCatalog] on the
     * warm-up above); reading a bundled file is quick.
     */
    private fun readBlocking(path: String): String = runBlocking { readCatalogue(path) }
}
