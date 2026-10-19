package io.github.kanylbullen.jarn

import android.app.Application
import android.content.Context
import io.github.kanylbullen.jarn.data.ExerciseRepository
import io.github.kanylbullen.jarn.data.JarnDatabase
import io.github.kanylbullen.jarn.data.WorkoutRepository

/** Hand-rolled dependency container. No DI framework — there is one database. */
class AppContainer(context: Context) {
    private val database: JarnDatabase by lazy { JarnDatabase.build(context) }
    val workoutRepository: WorkoutRepository by lazy { WorkoutRepository(database) }
    val exerciseRepository: ExerciseRepository by lazy { ExerciseRepository(database.exerciseDao()) }
}

class JarnApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
