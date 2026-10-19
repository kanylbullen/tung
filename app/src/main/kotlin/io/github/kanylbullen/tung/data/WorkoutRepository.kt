package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.domain.LoggedSet
import io.github.kanylbullen.tung.domain.prefillSet
import kotlinx.coroutines.flow.Flow

/** All workout reads and writes. Room runs suspend DAO calls off the main thread. */
class WorkoutRepository(
    private val db: TungDatabase,
    private val dao: WorkoutDao = db.workoutDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeActiveWorkout(): Flow<Workout?> = dao.observeActiveWorkout()

    fun observeFinishedWorkouts(): Flow<List<WorkoutSummaryRow>> = dao.observeFinishedWorkouts()

    fun observeWorkout(id: String): Flow<Workout?> = dao.observeWorkout(id)

    fun observeExercises(workoutId: String): Flow<List<WorkoutExerciseWithSets>> =
        dao.observeExercisesWithSets(workoutId)

    /** Returns the workout in progress, creating one if there is none. */
    suspend fun startOrResumeWorkout(): String = db.withTransaction {
        dao.getActiveWorkout()?.id ?: run {
            val now = clock()
            val workout = Workout(id = newId(), startedAt = now, updatedAt = now)
            dao.insertWorkout(workout)
            workout.id
        }
    }

    /** Adds an exercise at the end, with a first set already filled in from last time. */
    suspend fun addExercise(workoutId: String, exerciseId: String): String = db.withTransaction {
        val position = dao.maxExercisePosition(workoutId) + 1
        val workoutExercise = WorkoutExercise(
            id = newId(),
            workoutId = workoutId,
            exerciseId = exerciseId,
            position = position,
            updatedAt = clock(),
        )
        dao.insertWorkoutExercise(workoutExercise)
        insertPrefilledSet(workoutExercise.id, workoutId, exerciseId)
        workoutExercise.id
    }

    suspend fun removeExercise(workoutExerciseId: String) = dao.deleteWorkoutExercise(workoutExerciseId)

    suspend fun addSet(workoutExerciseId: String): String? = db.withTransaction {
        val we = dao.getWorkoutExercise(workoutExerciseId) ?: return@withTransaction null
        insertPrefilledSet(we.id, we.workoutId, we.exerciseId)
    }

    private suspend fun insertPrefilledSet(workoutExerciseId: String, workoutId: String, exerciseId: String): String {
        val existing = dao.getSets(workoutExerciseId)
        val previous = dao.previousSessionSets(exerciseId, workoutId).map { it.toLogged() }
        val values = prefillSet(existing.lastOrNull()?.toLogged(), previous, existing.size)
            ?: LoggedSet(weightKg = 0.0, reps = 0)
        val set = SetEntry(
            id = newId(),
            workoutExerciseId = workoutExerciseId,
            position = (existing.maxOfOrNull { it.position } ?: -1) + 1,
            weightKg = values.weightKg,
            reps = values.reps,
            completed = false,
            updatedAt = clock(),
        )
        dao.insertSet(set)
        return set.id
    }

    suspend fun updateWeight(setId: String, weightKg: Double) = dao.updateWeight(setId, weightKg, clock())

    suspend fun updateReps(setId: String, reps: Int) = dao.updateReps(setId, reps, clock())

    suspend fun setCompleted(setId: String, completed: Boolean) = dao.setCompleted(setId, completed, clock())

    suspend fun deleteSet(setId: String) = dao.deleteSet(setId)

    suspend fun previousSessionSets(exerciseId: String, excludeWorkoutId: String): List<LoggedSet> =
        dao.previousSessionSets(exerciseId, excludeWorkoutId).map { it.toLogged() }

    suspend fun previousMaxWeight(exerciseId: String, before: Long): Double? =
        dao.previousMaxWeight(exerciseId, before)

    suspend fun countLoggedSets(workoutId: String): Int = dao.countLoggedSets(workoutId)

    /**
     * Ends the workout: drops unticked or empty sets, then exercises left with
     * no sets. A workout with nothing logged is deleted rather than kept as an
     * empty row in the history.
     *
     * @return true if the workout was saved, false if it was discarded.
     */
    suspend fun finishWorkout(workoutId: String): Boolean = db.withTransaction {
        dao.deleteUnloggedSets(workoutId)
        dao.deleteEmptyExercises(workoutId)
        if (dao.countLoggedSets(workoutId) == 0) {
            dao.deleteWorkout(workoutId)
            false
        } else {
            val now = clock()
            dao.setEndedAt(workoutId, endedAt = now, updatedAt = now)
            true
        }
    }

    suspend fun deleteWorkout(workoutId: String) = dao.deleteWorkout(workoutId)
}
