package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.command.CommandRules
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.RoutineExercisePlan
import io.github.kanylbullen.tung.core.command.id
import kotlinx.coroutines.flow.Flow

/**
 * All routine reads, and the Room-side entry to the routine writes for
 * callers that are not commands (the import, tests): each runs core's rule
 * ([CommandRules], issue #87) over this database, as `AppCommandHandler`
 * does for the commands.
 */
class RoutineRepository(
    private val db: TungDatabase,
    private val dao: RoutineDao = db.routineDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val executor = CommandRules()

    private fun tx() = RoomCommandTx(db, clock)

    fun observeRoutines(): Flow<List<RoutineSummaryRow>> = dao.observeRoutineSummaries()

    fun observeRoutine(id: String): Flow<Routine?> = dao.observeRoutine(id)

    /** Every routine's exercises with their target sets, for [routineMuscles]. */
    fun observeRoutineExerciseLinks(): Flow<List<RoutineExerciseLink>> = dao.observeRoutineExerciseLinks()

    fun observeRoutineExercises(routineId: String): Flow<List<RoutineExerciseWithExercise>> =
        dao.observeRoutineExercises(routineId)

    /**
     * Saves [workoutId] as a new routine called [name] ([CommandRules.saveRoutine]):
     * its exercises in workout order, each with one target set per logged set
     * and the most common rep count as target reps. Saving the same workout
     * twice makes two routines.
     *
     * @return the new routine's id, or null if the workout has no logged set.
     */
    suspend fun saveFromWorkout(workoutId: String, name: String): String? = db.withTransaction {
        executor.saveRoutine(tx(), workoutId, name).id(ROUTINE_ID)
    }

    /**
     * Creates a routine called [name] from a plan rather than a workout
     * ([CommandRules.saveRoutinePlan]): the [exercises] in order, each an
     * exercise id with its target sets (kept within the allowed range) and
     * target reps. The exercise rows must exist.
     *
     * [routineId] and [routineExerciseIds] (one per exercise) are new ids
     * unless the caller has its own: an import passes its deterministic ids.
     *
     * @return the new routine's id.
     */
    suspend fun createFromPlan(
        name: String,
        exercises: List<Triple<String, Int, Int?>>,
        routineId: String? = null,
        routineExerciseIds: List<String>? = null,
    ): String = db.withTransaction {
        val plan = exercises.map { (exerciseId, targetSets, targetReps) -> RoutineExercisePlan(exerciseId, targetSets, targetReps) }
        val result = if (routineId == null) {
            executor.saveRoutinePlan(tx(), name, plan)
        } else {
            executor.saveRoutinePlan(tx(), name, plan, routineId, routineExerciseIds)
        }
        checkNotNull(result.id(ROUTINE_ID)) { "$result" }
    }

    /** Creates an empty routine called [name] ([CommandRules.createRoutine]) and returns its id. */
    suspend fun create(name: String): String = db.withTransaction {
        checkNotNull(executor.createRoutine(tx(), name).id(ROUTINE_ID))
    }

    /**
     * Starts a workout from [routineId] ([CommandRules.startRoutine]): the
     * routine's exercises in order, each with its target number of sets,
     * unticked and pre-filled from the previous session position by position.
     * Marks the routine used. [programDayId] is recorded on the workout when
     * it is started from a program day.
     *
     * There is at most one workout in progress: if one exists, its id is
     * returned and nothing is created.
     *
     * @return the workout's id, or null if the routine no longer exists.
     */
    suspend fun start(routineId: String, programDayId: String? = null, startedBy: String? = null): String? = db.withTransaction {
        executor.startRoutine(tx(), routineId, programDayId, startedBy).id(WORKOUT_ID)
    }

    suspend fun rename(routineId: String, name: String) = db.withTransaction {
        executor.renameRoutine(tx(), routineId, name)
        Unit
    }

    /**
     * Deletes the routine, its exercises and the program days that use it,
     * with a tombstone for each ([CommandRules.deleteRoutine]). Workouts
     * started from it are kept; a routine already gone is left alone.
     */
    suspend fun delete(routineId: String) = db.withTransaction {
        executor.deleteRoutine(tx(), routineId)
        Unit
    }

    /** How many program days use any of [routineIds], i.e. go with them when they are deleted. */
    suspend fun programDaysUsing(routineIds: Collection<String>): Int =
        routineIds.distinct().sumOf { db.programDao().getDaysUsing(it).size }
}
