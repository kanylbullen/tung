package io.github.kanylbullen.tung

import android.app.Application
import android.content.Context
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.ExtendedCatalog
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository

/** Hand-rolled dependency container. No DI framework — there is one database. */
class AppContainer(context: Context) {
    private val database: TungDatabase by lazy { TungDatabase.build(context) }
    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(database) }
    val exerciseRepository: ExerciseRepository by lazy { ExerciseRepository(database.exerciseDao()) }
    val extendedCatalog: ExtendedCatalog by lazy { ExtendedCatalog.fromAssets(context) }
}

class TungApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
