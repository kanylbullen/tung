package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.ExerciseHistory
import io.github.kanylbullen.tung.core.HistorySet
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.addedExerciseSets
import io.github.kanylbullen.tung.core.exerciseHistory
import io.github.kanylbullen.tung.core.addsNextSet
import io.github.kanylbullen.tung.core.command.PlannedSet
import io.github.kanylbullen.tung.core.command.plannedSetValues
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.picker.HistoricWorkout
import io.github.kanylbullen.tung.core.picker.SUGGESTION_HISTORY_LIMIT
import io.github.kanylbullen.tung.core.prefillSet
import io.github.kanylbullen.tung.core.progress.ProgressSet
import io.github.kanylbullen.tung.core.sync.SyncTable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** All workout reads and writes. Room runs suspend DAO calls off the main thread. */
class WorkoutRepository(
    private val db: TungDatabase,
    private val dao: WorkoutDao = db.workoutDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeActiveWorkout(): Flow<Workout?> = dao.observeActiveWorkout()

    fun observeFinishedWorkouts(): Flow<List<WorkoutSummaryRow>> = dao.observeFinishedWorkouts()

    fun observeWorkout(id: String): Flow<Workout?> = dao.observeWorkout(id)

    /**
     * The latest [limit] finished workouts as the picker's suggestions read
     * them: start time and exercise ids in position order, newest first. Read
     * when the picker opens; nothing about it is stored.
     */
    suspend fun recentHistory(limit: Int = SUGGESTION_HISTORY_LIMIT): List<HistoricWorkout> =
        dao.recentExerciseOrder(limit).groupBy { it.workoutId }.values.map { rows ->
            HistoricWorkout(rows.first().startedAt, rows.map { it.exerciseId })
        }

    fun observeExercises(workoutId: String): Flow<List<WorkoutExerciseWithSets>> =
        dao.observeExercisesWithSets(workoutId)

    /** Returns the workout in progress, creating one if there is none, started by [startedBy] (`core.StartedBy`). */
    suspend fun startOrResumeWorkout(startedBy: String? = null): String = db.withTransaction {
        dao.getActiveWorkout()?.id ?: run {
            val now = clock()
            val workout = Workout(id = newId(), startedAt = now, updatedAt = now, startedBy = startedBy)
            dao.insertWorkout(workout)
            workout.id
        }
    }

    /**
     * Adds an exercise at the end with [sets] sets, or, when null, as many as
     * it had last session (one with no history), each filled in from last time
     * at its own position ([addedExerciseSets]).
     */
    suspend fun addExercise(workoutId: String, exerciseId: String, sets: Int? = null): String = db.withTransaction {
        val now = clock()
        val position = dao.maxExercisePosition(workoutId) + 1
        val workoutExercise = WorkoutExercise(
            id = newId(),
            workoutId = workoutId,
            exerciseId = exerciseId,
            position = position,
            updatedAt = now,
        )
        dao.insertWorkoutExercise(workoutExercise)
        val previous = dao.previousSessionSets(exerciseId, workoutId).map { it.toLogged() }
        addedExerciseSets(sets, previous).forEachIndexed { setPosition, values ->
            dao.insertSet(
                SetEntry(
                    id = newId(),
                    workoutExerciseId = workoutExercise.id,
                    position = setPosition,
                    weightKg = values.weightKg,
                    reps = values.reps,
                    completed = false,
                    updatedAt = now,
                ),
            )
        }
        workoutExercise.id
    }

    /** Removes an exercise and its sets, with a tombstone for each. */
    suspend fun removeExercise(workoutExerciseId: String) = db.withTransaction {
        val now = clock()
        db.recordDeletes(SyncTable.SETS, dao.setIds(workoutExerciseId), now)
        db.recordDeletes(SyncTable.WORKOUT_EXERCISES, listOf(workoutExerciseId), now)
        dao.deleteWorkoutExercise(workoutExerciseId)
    }

    /**
     * Adds [count] sets after the last one, each pre-filled by the usual rule
     * (a copy of the set before it). One transaction.
     *
     * @return the id of the first added set, or null when there is no such exercise.
     */
    suspend fun addSet(workoutExerciseId: String, count: Int = 1): String? = db.withTransaction {
        val we = dao.getWorkoutExercise(workoutExerciseId) ?: return@withTransaction null
        List(count.coerceAtLeast(1)) { insertPrefilledSet(we.id, we.workoutId, we.exerciseId) }.first()
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

    /**
     * "Apply to all sets" for weight: writes [weightKg] to [setId] and to every
     * set of its workout exercise that is not ticked. Ticked sets stay as logged;
     * the set being edited gets the value even if it is ticked.
     */
    suspend fun updateWeightForOpenSets(setId: String, weightKg: Double) =
        db.withTransaction {
            val now = clock()
            openSetIds(setId).forEach { dao.updateWeight(it, weightKg, now) }
        }

    /** "Apply to all sets" for reps; see [updateWeightForOpenSets]. */
    suspend fun updateRepsForOpenSets(setId: String, reps: Int) =
        db.withTransaction {
            val now = clock()
            openSetIds(setId).forEach { dao.updateReps(it, reps, now) }
        }

    private suspend fun openSetIds(setId: String): List<String> {
        val set = dao.getSet(setId) ?: return emptyList()
        return dao.getSets(set.workoutExerciseId).filter { it.id == setId || !it.completed }.map { it.id }
    }

    suspend fun setCompleted(setId: String, completed: Boolean) = dao.setCompleted(setId, completed, clock())

    /**
     * "Log set": ticks [setId] and, if it was the last set of its exercise,
     * adds the next one pre-filled by the usual rule (a copy of the set just
     * logged). An exercise from a routine is not padded past the routine's
     * target sets ([addsNextSet]). One transaction, so the screen sees the tick
     * and the new set together.
     *
     * @return the id of the added set, or null if none was added.
     */
    suspend fun logSet(setId: String): String? = db.withTransaction {
        val set = dao.getSet(setId) ?: return@withTransaction null
        dao.setCompleted(setId, true, clock())
        val we = dao.getWorkoutExercise(set.workoutExerciseId) ?: return@withTransaction null
        val sets = dao.getSets(we.id)
        val wasLast = sets.maxOf { it.position } == set.position
        val target = if (wasLast) dao.routineTargetSets(we.workoutId, we.exerciseId) else null
        if (addsNextSet(wasLast, sets.size, target)) insertPrefilledSet(we.id, we.workoutId, we.exerciseId) else null
    }

    suspend fun deleteSet(setId: String) = db.withTransaction {
        dao.getSet(setId) ?: return@withTransaction
        db.recordDeletes(SyncTable.SETS, listOf(setId), clock())
        dao.deleteSet(setId)
    }

    suspend fun getSet(setId: String): SetEntry? = dao.getSet(setId)

    suspend fun getWorkout(workoutId: String): Workout? = dao.getWorkout(workoutId)

    suspend fun getExercisesWithSets(workoutId: String): List<WorkoutExerciseWithSets> = dao.getExercisesWithSets(workoutId)

    suspend fun getSets(workoutExerciseId: String): List<SetEntry> = dao.getSets(workoutExerciseId)

    /**
     * A whole planned workout in one transaction: the workout in progress, or
     * a new one, gets each of [exercises] appended in order with its planned
     * sets, none ticked. A number the plan leaves out comes from the pre-fill
     * rule ([plannedSetValues]). The exercise rows must exist.
     *
     * @return the workout's id.
     */
    suspend fun applyPlan(exercises: List<Pair<String, List<PlannedSet>>>, startedBy: String? = null): String = db.withTransaction {
        val workoutId = startOrResumeWorkout(startedBy)
        exercises.forEach { (exerciseId, planned) ->
            val now = clock()
            val workoutExercise = WorkoutExercise(
                id = newId(),
                workoutId = workoutId,
                exerciseId = exerciseId,
                position = dao.maxExercisePosition(workoutId) + 1,
                updatedAt = now,
            )
            dao.insertWorkoutExercise(workoutExercise)
            val previous = dao.previousSessionSets(exerciseId, workoutId).map { it.toLogged() }
            plannedSetValues(planned, previous).forEachIndexed { position, values ->
                dao.insertSet(
                    SetEntry(
                        id = newId(),
                        workoutExerciseId = workoutExercise.id,
                        position = position,
                        weightKg = values.weightKg,
                        reps = values.reps,
                        completed = false,
                        updatedAt = now,
                    ),
                )
            }
        }
        workoutId
    }

    /** The user's history of [exerciseId] (sessions, last session, best set), live; core's [exerciseHistory] does the sums. */
    fun observeExerciseHistory(exerciseId: String): Flow<ExerciseHistory> =
        dao.observeExerciseHistory(exerciseId).map { rows ->
            exerciseHistory(
                rows.map {
                    HistorySet(it.workoutId, it.workoutExerciseId, it.startedAt, it.exercisePosition, it.setPosition, LoggedSet(it.weightKg, it.reps))
                },
            )
        }

    /** Every logged set of every finished workout, live, for the Progress tab; `core.progress` does the sums. */
    fun observeProgressSets(): Flow<List<ProgressSet>> =
        dao.observeProgressSets().map { rows -> rows.map { it.toProgressSet() } }

    /** [observeProgressSets] for one exercise. */
    fun observeProgressSets(exerciseId: String): Flow<List<ProgressSet>> =
        dao.observeProgressSets(exerciseId).map { rows -> rows.map { it.toProgressSet() } }

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
        val now = clock()
        db.recordDeletes(SyncTable.SETS, dao.unloggedSetIds(workoutId), now)
        dao.deleteUnloggedSets(workoutId)
        db.recordDeletes(SyncTable.WORKOUT_EXERCISES, dao.emptyExerciseIds(workoutId), now)
        dao.deleteEmptyExercises(workoutId)
        if (dao.countLoggedSets(workoutId) == 0) {
            deleteWorkoutAt(workoutId, now)
            false
        } else {
            dao.setEndedAt(workoutId, endedAt = now, updatedAt = now)
            true
        }
    }

    /**
     * Remembers the Health Connect session written for [workoutId]. Never
     * replaces one already stored, so a workout keeps the first session's id.
     * @return true if [healthConnectId] was stored.
     */
    suspend fun setHealthConnectId(workoutId: String, healthConnectId: String): Boolean =
        dao.setHealthConnectId(workoutId, healthConnectId, clock()) == 1

    /** Heart rate and calories read from Health Connect for [workoutId]; a null leaves that column as it is. */
    suspend fun setVitals(workoutId: String, avgHeartRate: Int?, maxHeartRate: Int?, activeCalories: Int?) {
        if (avgHeartRate == null && maxHeartRate == null && activeCalories == null) return
        dao.setVitals(workoutId, avgHeartRate, maxHeartRate, activeCalories, clock())
    }

    /** Deletes the workout with its exercises and sets, with a tombstone for each. */
    suspend fun deleteWorkout(workoutId: String) = db.withTransaction { deleteWorkoutAt(workoutId, clock()) }

    private suspend fun deleteWorkoutAt(workoutId: String, now: Long) {
        dao.getWorkout(workoutId) ?: return
        db.recordDeletes(SyncTable.SETS, dao.setIdsOfWorkout(workoutId), now)
        db.recordDeletes(SyncTable.WORKOUT_EXERCISES, dao.workoutExerciseIds(workoutId), now)
        db.recordDeletes(SyncTable.WORKOUTS, listOf(workoutId), now)
        dao.deleteWorkout(workoutId)
    }
}
