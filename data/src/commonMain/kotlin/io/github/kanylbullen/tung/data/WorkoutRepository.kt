package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.ExerciseHistory
import io.github.kanylbullen.tung.core.HistorySet
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandRules
import io.github.kanylbullen.tung.core.command.CommandStore
import io.github.kanylbullen.tung.core.command.CommandTx
import io.github.kanylbullen.tung.core.command.PlannedSet
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.exerciseHistory
import io.github.kanylbullen.tung.core.picker.HistoricWorkout
import io.github.kanylbullen.tung.core.picker.SUGGESTION_HISTORY_LIMIT
import io.github.kanylbullen.tung.core.progress.ProgressSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** All workout reads and writes. Room runs suspend DAO calls off the main thread. */
class WorkoutRepository(
    private val db: TungDatabase,
    private val dao: WorkoutDao = db.workoutDao(),
    private val clock: () -> Long = ::systemMillis,
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

    /** Returns the workout in progress, creating one if there is none, started by [startedBy] (`core.StartedBy`): [CommandRules.startWorkout]. */
    suspend fun startOrResumeWorkout(startedBy: String? = null): String = db.transaction {
        checkNotNull(executor.startWorkout(commandTx(), startedBy).id(WORKOUT_ID))
    }

    /** Core's store over this database for the [CommandRules], inside a `db.transaction` the caller opened (issue #87). */
    internal fun commandTx(): CommandTx = RoomCommandTx(db, clock)

    /** Core's [CommandStore] over this database, with this repository's clock: what `AppCommandHandler`'s executor runs in. */
    internal fun commandStore(): CommandStore = RoomCommandStore(db, clock)

    private val executor = CommandRules()

    /**
     * Adds an exercise at the end with [sets] sets, or, when null, as many as
     * it had last session (one with no history), each filled in from last time
     * at its own position: [CommandRules.addExercise]'s rule, for callers
     * that are not commands (the companion watch's handler, tests). The
     * workout must exist.
     */
    suspend fun addExercise(workoutId: String, exerciseId: String, sets: Int? = null): String = db.transaction {
        when (val result = executor.addExercise(commandTx(), workoutId, exerciseId, sets)) {
            is CommandResult.Done -> result.ids.getValue(WORKOUT_EXERCISE_ID)
            else -> error("add_exercise refused: $result")
        }
    }

    /** Removes an exercise and its sets, with a tombstone for each; nothing for one that is gone. */
    suspend fun removeExercise(workoutExerciseId: String) {
        db.transaction { executor.removeExercise(commandTx(), workoutExerciseId) }
    }

    /**
     * Adds [count] sets after the last one, each pre-filled by the usual rule
     * (a copy of the set before it): [CommandRules.addSet]'s rule. One transaction.
     *
     * @return the id of the first added set, or null when there is no such exercise.
     */
    suspend fun addSet(workoutExerciseId: String, count: Int = 1): String? = db.transaction {
        executor.addSet(commandTx(), workoutExerciseId, count).id(SET_ID)
    }

    suspend fun updateWeight(setId: String, weightKg: Double) {
        db.transaction { executor.setWeight(commandTx(), setId, weightKg, applyToOpenSets = false) }
    }

    suspend fun updateReps(setId: String, reps: Int) {
        db.transaction { executor.setReps(commandTx(), setId, reps, applyToOpenSets = false) }
    }

    /**
     * "Apply to all sets" for weight: writes [weightKg] to [setId] and to every
     * set of its workout exercise that is not ticked. Ticked sets stay as logged;
     * the set being edited gets the value even if it is ticked.
     */
    suspend fun updateWeightForOpenSets(setId: String, weightKg: Double) {
        db.transaction { executor.setWeight(commandTx(), setId, weightKg, applyToOpenSets = true) }
    }

    /** "Apply to all sets" for reps; see [updateWeightForOpenSets]. */
    suspend fun updateRepsForOpenSets(setId: String, reps: Int) {
        db.transaction { executor.setReps(commandTx(), setId, reps, applyToOpenSets = true) }
    }

    suspend fun setCompleted(setId: String, completed: Boolean) {
        db.transaction { executor.tickSet(commandTx(), setId, completed) }
    }

    /**
     * "Log set": ticks [setId], [CommandRules.logSet]'s rule (a set with 0
     * reps is not logged). Only with [addNext] (the wire's `log_set.add_next`,
     * the behaviour before issue #64) does it also add the next set when this
     * was the last set of its exercise, never past a routine's target sets.
     * One transaction, so the screen sees the tick and the new set together.
     *
     * @return the id of the added set, or null if none was added (or the set could not be logged).
     */
    suspend fun logSet(setId: String, addNext: Boolean = false): String? = db.transaction {
        val workoutId = dao.getSet(setId)?.let { dao.getWorkoutExercise(it.workoutExerciseId) }?.workoutId
            ?: return@transaction null
        executor.logSet(commandTx(), Command.LogSet(workoutId, setId, addNext)).id(ADDED_SET_ID)
    }

    suspend fun deleteSet(setId: String) {
        db.transaction { executor.removeSet(commandTx(), setId) }
    }

    suspend fun getSet(setId: String): SetEntry? = dao.getSet(setId)

    suspend fun getWorkout(workoutId: String): Workout? = dao.getWorkout(workoutId)

    suspend fun getExercisesWithSets(workoutId: String): List<WorkoutExerciseWithSets> = dao.getExercisesWithSets(workoutId)

    suspend fun getSets(workoutExerciseId: String): List<SetEntry> = dao.getSets(workoutExerciseId)

    /**
     * A whole planned workout in one transaction: the workout in progress, or
     * a new one, gets each of [exercises] appended in order with its planned
     * sets, none ticked ([CommandRules.applyPlan]). The exercise rows must exist.
     *
     * @return the workout's id.
     */
    suspend fun applyPlan(exercises: List<Pair<String, List<PlannedSet>>>, startedBy: String? = null): String = db.transaction {
        checkNotNull(executor.applyPlan(commandTx(), exercises, startedBy).id(WORKOUT_ID))
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
     * empty row in the history ([CommandRules.finishWorkout]).
     *
     * @return true if the workout was saved, false if it was discarded (or does not exist).
     */
    suspend fun finishWorkout(workoutId: String): Boolean = db.transaction {
        executor.finishWorkout(commandTx(), workoutId).id(WORKOUT_ID) != null
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

    /** Deletes the workout with its exercises and sets, with a tombstone for each; nothing for one that is gone. */
    suspend fun deleteWorkout(workoutId: String) {
        db.transaction { executor.deleteWorkout(commandTx(), workoutId) }
    }

    /**
     * Deletes every workout one import wrote ([Workout.importBatchId]) through
     * the ordinary delete, tombstones included, in one transaction. Workouts
     * logged in Tung, and other imports, are untouched.
     * @return how many workouts were deleted.
     */
    suspend fun deleteImportBatch(batchId: String): Int = db.transaction {
        val ids = dao.importBatchWorkoutIds(batchId)
        val tx = commandTx()
        ids.forEach { executor.deleteWorkout(tx, it) }
        ids.size
    }
}
