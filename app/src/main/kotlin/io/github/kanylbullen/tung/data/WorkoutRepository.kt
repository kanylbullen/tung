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

    fun observeWorkout(id: Long): Flow<Workout?> = dao.observeWorkout(id)

    fun observeExercises(workoutId: Long): Flow<List<WorkoutExerciseWithSets>> =
        dao.observeExercisesWithSets(workoutId)

    /** Returns the workout in progress, creating one if there is none. */
    suspend fun startOrResumeWorkout(): Long = db.withTransaction {
        dao.getActiveWorkout()?.id ?: dao.insertWorkout(Workout(startedAt = clock()))
    }

    /** Adds an exercise at the end, with a first set already filled in from last time. */
    suspend fun addExercise(workoutId: Long, exerciseId: Long): Long = db.withTransaction {
        val position = dao.maxExercisePosition(workoutId) + 1
        val weId = dao.insertWorkoutExercise(
            WorkoutExercise(workoutId = workoutId, exerciseId = exerciseId, position = position)
        )
        insertPrefilledSet(weId, workoutId, exerciseId)
        weId
    }

    suspend fun removeExercise(workoutExerciseId: Long) = dao.deleteWorkoutExercise(workoutExerciseId)

    suspend fun addSet(workoutExerciseId: Long): Long? = db.withTransaction {
        val we = dao.getWorkoutExercise(workoutExerciseId) ?: return@withTransaction null
        insertPrefilledSet(we.id, we.workoutId, we.exerciseId)
    }

    private suspend fun insertPrefilledSet(workoutExerciseId: Long, workoutId: Long, exerciseId: Long): Long {
        val existing = dao.getSets(workoutExerciseId)
        val previous = dao.previousSessionSets(exerciseId, workoutId).map { it.toLogged() }
        val values = prefillSet(existing.lastOrNull()?.toLogged(), previous, existing.size)
            ?: LoggedSet(weightKg = 0.0, reps = 0)
        return dao.insertSet(
            SetEntry(
                workoutExerciseId = workoutExerciseId,
                position = (existing.maxOfOrNull { it.position } ?: -1) + 1,
                weightKg = values.weightKg,
                reps = values.reps,
                completed = false,
            )
        )
    }

    suspend fun updateWeight(setId: Long, weightKg: Double) = dao.updateWeight(setId, weightKg)

    suspend fun updateReps(setId: Long, reps: Int) = dao.updateReps(setId, reps)

    suspend fun setCompleted(setId: Long, completed: Boolean) = dao.setCompleted(setId, completed)

    suspend fun deleteSet(setId: Long) = dao.deleteSet(setId)

    suspend fun previousSessionSets(exerciseId: Long, excludeWorkoutId: Long): List<LoggedSet> =
        dao.previousSessionSets(exerciseId, excludeWorkoutId).map { it.toLogged() }

    suspend fun previousMaxWeight(exerciseId: Long, before: Long): Double? =
        dao.previousMaxWeight(exerciseId, before)

    suspend fun countLoggedSets(workoutId: Long): Int = dao.countLoggedSets(workoutId)

    /**
     * Ends the workout: drops unticked or empty sets, then exercises left with
     * no sets. A workout with nothing logged is deleted rather than kept as an
     * empty row in the history.
     *
     * @return true if the workout was saved, false if it was discarded.
     */
    suspend fun finishWorkout(workoutId: Long): Boolean = db.withTransaction {
        dao.deleteUnloggedSets(workoutId)
        dao.deleteEmptyExercises(workoutId)
        if (dao.countLoggedSets(workoutId) == 0) {
            dao.deleteWorkout(workoutId)
            false
        } else {
            dao.setEndedAt(workoutId, clock())
            true
        }
    }

    suspend fun deleteWorkout(workoutId: Long) = dao.deleteWorkout(workoutId)
}
