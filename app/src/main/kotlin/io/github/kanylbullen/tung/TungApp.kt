package io.github.kanylbullen.tung

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.data.AppCommandHandler
import io.github.kanylbullen.tung.data.AppExerciseResolver
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.fromAssets
import io.github.kanylbullen.tung.timer.RestTimerController
import io.github.kanylbullen.tung.wear.DataLayerStateWriter
import io.github.kanylbullen.tung.wear.WearCommandHandler
import io.github.kanylbullen.tung.wear.WearStatePublisher
import io.github.kanylbullen.tung.wear.isWearableApiAvailable
import java.time.Clock

/**
 * Hand-rolled dependency container. No DI framework — there is one database.
 *
 * [createDatabase] is the one seam: the app uses the file-backed default, the
 * JVM flow tests hand in an in-memory database. [clock] says what day it is
 * for Home's "Today"; the app uses the system clock and zone, the flow tests
 * one they can set to a given weekday.
 */
class AppContainer(
    context: Context,
    val clock: Clock = Clock.systemDefaultZone(),
    createDatabase: (Context) -> TungDatabase = TungDatabase::build,
) {
    private val database: TungDatabase by lazy { createDatabase(context) }
    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(database) }
    val routineRepository: RoutineRepository by lazy { RoutineRepository(database) }
    val programRepository: ProgramRepository by lazy { ProgramRepository(database) }
    val exerciseRepository: ExerciseRepository by lazy { ExerciseRepository(database.exerciseDao()) }
    val extendedCatalog: ExtendedCatalog by lazy { ExtendedCatalog.fromAssets(context) }

    /** The core catalogue's names in every language, by key, from `core.json`; read the first time a name is resolved. */
    private val catalogueNames: Map<String, List<String>> by lazy {
        context.assets.open(CORE_CATALOG_ASSET).use { CoreCatalog.parse(it.reader().readText()) }
            .associate { it.key to it.name.values.toList() }
    }

    /**
     * Every change to training data goes through here: the screens' ViewModels
     * call [CommandHandler.execute] and never a repository for a mutation.
     * Reads stay on the repositories' Flows.
     */
    val commands: CommandHandler by lazy {
        AppCommandHandler(
            db = database,
            workouts = workoutRepository,
            exercises = exerciseRepository,
            routines = routineRepository,
            programs = programRepository,
            resolver = AppExerciseResolver(database.exerciseDao(), { catalogueNames }, extendedCatalog),
            extendedCatalog = extendedCatalog,
        )
    }

    /** Small settings and state outside Room: the rest timer and its default. One instance per file. */
    private val settings: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }
    }
    val restTimer: RestTimerController by lazy { RestTimerController(context, settings) }

    /** Mirrors the active workout to the watch (`wear/`); started by [TungApp] and `WearSyncService`. */
    val wearPublisher: WearStatePublisher by lazy {
        WearStatePublisher(context, workoutRepository, routineRepository, restTimer, settings.data, DataLayerStateWriter(context))
    }
    val wearCommands: WearCommandHandler by lazy {
        WearCommandHandler(workoutRepository, routineRepository, restTimer, wearPublisher.selection)
    }
}

/** `catalog/core.json`, shipped as an asset like the extended catalogue. */
private const val CORE_CATALOG_ASSET = "core.json"

open class TungApp : Application() {
    val container: AppContainer by lazy { createContainer() }

    private var startedActivities = 0

    /** Whether an activity of the app is on screen; a finished rest only posts a notification when not. */
    val isInForeground: Boolean get() = startedActivities > 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { startedActivities++ }
            override fun onActivityStopped(activity: Activity) { startedActivities-- }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Without Play services there is no Data Layer and no watch to publish to (also the JVM tests).
        if (isWearableApiAvailable(this)) container.wearPublisher.start()
    }

    /** Overridden only by the test application of the flow tests. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
