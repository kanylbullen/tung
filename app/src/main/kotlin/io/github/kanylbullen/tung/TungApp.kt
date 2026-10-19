package io.github.kanylbullen.tung

import android.app.Application
import android.content.Context
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.fromAssets

/**
 * Hand-rolled dependency container. No DI framework — there is one database.
 *
 * [createDatabase] is the one seam: the app uses the file-backed default, the
 * JVM flow tests hand in an in-memory database.
 */
class AppContainer(
    context: Context,
    createDatabase: (Context) -> TungDatabase = TungDatabase::build,
) {
    private val database: TungDatabase by lazy { createDatabase(context) }
    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(database) }
    val routineRepository: RoutineRepository by lazy { RoutineRepository(database) }
    val exerciseRepository: ExerciseRepository by lazy { ExerciseRepository(database.exerciseDao()) }
    val extendedCatalog: ExtendedCatalog by lazy { ExtendedCatalog.fromAssets(context) }
}

open class TungApp : Application() {
    val container: AppContainer by lazy { createContainer() }

    /** Overridden only by the test application of the flow tests. */
    protected open fun createContainer(): AppContainer = AppContainer(this)
}
