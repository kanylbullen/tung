package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.clampTargetSets
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.routineStartSets
import io.github.kanylbullen.tung.core.routineTarget
import io.github.kanylbullen.tung.core.sync.SyncTable
import kotlinx.coroutines.flow.Flow

/** All routine reads and writes, and the two bridges to workouts: save from one, start one. */
class RoutineRepository(
    private val db: TungDatabase,
    private val dao: RoutineDao = db.routineDao(),
    private val workoutDao: WorkoutDao = db.workoutDao(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeRoutines(): Flow<List<RoutineSummaryRow>> = dao.observeRoutineSummaries()

    fun observeRoutine(id: String): Flow<Routine?> = dao.observeRoutine(id)

    suspend fun exists(routineId: String): Boolean = dao.getRoutine(routineId) != null

    fun observeRoutineExercises(routineId: String): Flow<List<RoutineExerciseWithExercise>> =
        dao.observeRoutineExercises(routineId)

    /**
     * Saves [workoutId] as a new routine called [name]: its exercises in workout
     * order, each with one target set per logged set and the most common rep
     * count as target reps ([routineTarget]). Saving the same workout twice
     * makes two routines.
     *
     * @return the new routine's id, or null if the workout has no logged set.
     */
    suspend fun saveFromWorkout(workoutId: String, name: String): String? = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a routine needs a name" }
        val exercises = workoutDao.getExercisesWithSets(workoutId)
            .map { e ->
                e.exercise.id to routineTarget(
                    e.sortedSets.filter { isLogged(it.completed, it.reps) }.map { it.toLogged() },
                )
            }
            .filter { (_, target) -> target.sets > 0 }
        if (exercises.isEmpty()) return@withTransaction null

        val now = clock()
        val routine = Routine(id = newId(), name = trimmed, updatedAt = now, lastUsedAt = null)
        dao.insertRoutine(routine)
        exercises.forEachIndexed { position, (exerciseId, target) ->
            dao.insertRoutineExercise(
                RoutineExercise(
                    id = newId(),
                    routineId = routine.id,
                    exerciseId = exerciseId,
                    position = position,
                    targetSets = clampTargetSets(target.sets),
                    targetReps = target.reps,
                    updatedAt = now,
                ),
            )
        }
        routine.id
    }

    /**
     * Creates a routine called [name] from a plan rather than a workout: the
     * [exercises] in order, each an exercise id with its target sets (kept
     * within the allowed range) and target reps. The exercise rows must exist.
     *
     * @return the new routine's id.
     */
    suspend fun createFromPlan(name: String, exercises: List<Triple<String, Int, Int?>>): String = db.withTransaction {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a routine needs a name" }
        val now = clock()
        val routine = Routine(id = newId(), name = trimmed, updatedAt = now, lastUsedAt = null)
        dao.insertRoutine(routine)
        exercises.forEachIndexed { position, (exerciseId, targetSets, targetReps) ->
            dao.insertRoutineExercise(
                RoutineExercise(
                    id = newId(),
                    routineId = routine.id,
                    exerciseId = exerciseId,
                    position = position,
                    targetSets = clampTargetSets(targetSets),
                    targetReps = targetReps,
                    updatedAt = now,
                ),
            )
        }
        routine.id
    }

    /**
     * Starts a workout from [routineId]: the routine's exercises in order, each
     * with its target number of sets, unticked and pre-filled from the previous
     * session position by position ([routineStartSets]). Marks the routine used.
     *
     * [programDayId] is recorded on the workout when it is started from a
     * program day; a rotating program follows it to suggest the next day.
     *
     * There is at most one workout in progress: if one exists, its id is
     * returned and nothing is created.
     *
     * @return the workout's id, or null if the routine no longer exists.
     */
    suspend fun start(routineId: String, programDayId: String? = null, startedBy: String? = null): String? = db.withTransaction {
        workoutDao.getActiveWorkout()?.let { return@withTransaction it.id }
        dao.getRoutine(routineId) ?: return@withTransaction null
        val now = clock()
        val workout = Workout(
            id = newId(),
            startedAt = now,
            routineId = routineId,
            updatedAt = now,
            programDayId = programDayId,
            startedBy = startedBy,
        )
        workoutDao.insertWorkout(workout)
        dao.getRoutineExercises(routineId).forEachIndexed { position, re ->
            val workoutExercise = WorkoutExercise(
                id = newId(),
                workoutId = workout.id,
                exerciseId = re.exerciseId,
                position = position,
                updatedAt = now,
            )
            workoutDao.insertWorkoutExercise(workoutExercise)
            val previous = workoutDao.previousSessionSets(re.exerciseId, workout.id).map { it.toLogged() }
            routineStartSets(re.targetSets, re.targetReps, previous).forEachIndexed { setPosition, values ->
                workoutDao.insertSet(
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
        }
        markUsed(routineId, now)
        workout.id
    }

    /** Records that a workout was started from [routineId] at [at]. */
    suspend fun markUsed(routineId: String, at: Long = clock()) =
        dao.setLastUsedAt(routineId, lastUsedAt = at, updatedAt = clock())

    suspend fun rename(routineId: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "a routine needs a name" }
        dao.rename(routineId, trimmed, clock())
    }

    /**
     * Puts the routine's exercises in the order of [orderedIds]. Only rows whose
     * position changes are written. Ids that do not belong to the routine are
     * ignored; exercises missing from the list keep their place after the rest.
     */
    suspend fun reorder(routineId: String, orderedIds: List<String>) = db.withTransaction {
        val current = dao.getRoutineExercises(routineId)
        val byId = current.associateBy { it.id }
        val ordered = orderedIds.distinct().mapNotNull { byId[it] }
        val rest = current.filterNot { it in ordered }
        val now = clock()
        (ordered + rest).forEachIndexed { position, re ->
            if (re.position != position) dao.setPosition(re.id, position, now)
        }
    }

    /**
     * Moves one exercise [delta] places (-1 up, +1 down) within its routine,
     * reading the order inside the transaction so quick taps never act on a
     * stale list. A move past either end does nothing.
     */
    suspend fun move(routineExerciseId: String, delta: Int) = db.withTransaction {
        val re = dao.getRoutineExercise(routineExerciseId) ?: return@withTransaction
        val ids = dao.getRoutineExercises(re.routineId).map { it.id }.toMutableList()
        val from = ids.indexOf(routineExerciseId)
        val to = from + delta
        if (from < 0 || to !in ids.indices) return@withTransaction
        ids.add(to, ids.removeAt(from))
        reorder(re.routineId, ids)
    }

    suspend fun setTargetSets(routineExerciseId: String, targetSets: Int) =
        dao.setTargetSets(routineExerciseId, clampTargetSets(targetSets), clock())

    /** Adds [delta] to an exercise's target sets, kept within the allowed range; reads the current value itself. */
    suspend fun adjustTargetSets(routineExerciseId: String, delta: Int) = db.withTransaction {
        val re = dao.getRoutineExercise(routineExerciseId) ?: return@withTransaction
        val target = clampTargetSets(re.targetSets + delta)
        if (target != re.targetSets) dao.setTargetSets(re.id, target, clock())
    }

    /** The reps an exercise aims at per set; null for none. Writes only when the value changes. */
    suspend fun setTargetReps(routineExerciseId: String, targetReps: Int?) = db.withTransaction {
        val re = dao.getRoutineExercise(routineExerciseId) ?: return@withTransaction
        if (re.targetReps != targetReps) dao.setTargetReps(re.id, targetReps, clock())
    }

    /**
     * Appends [exerciseId] to the routine, after its last exercise, with
     * [targetSets] (kept within the allowed range) and [targetReps]. The
     * exercise row must exist.
     *
     * @return the new routine exercise's id, or null if the routine no longer exists.
     */
    suspend fun addExercise(routineId: String, exerciseId: String, targetSets: Int, targetReps: Int?): String? = db.withTransaction {
        dao.getRoutine(routineId) ?: return@withTransaction null
        val re = RoutineExercise(
            id = newId(),
            routineId = routineId,
            exerciseId = exerciseId,
            position = dao.maxPosition(routineId) + 1,
            targetSets = clampTargetSets(targetSets),
            targetReps = targetReps,
            updatedAt = clock(),
        )
        dao.insertRoutineExercise(re)
        re.id
    }

    /**
     * Removes one exercise from its routine, with a tombstone, and closes the
     * position gap so the rest keep their order from 0. Workouts started from
     * the routine are kept.
     */
    suspend fun removeExercise(routineExerciseId: String) = db.withTransaction {
        val re = dao.getRoutineExercise(routineExerciseId) ?: return@withTransaction
        val now = clock()
        db.recordDeletes(SyncTable.ROUTINE_EXERCISES, listOf(routineExerciseId), now)
        dao.deleteRoutineExercise(routineExerciseId)
        dao.getRoutineExercises(re.routineId).forEachIndexed { position, rest ->
            if (rest.position != position) dao.setPosition(rest.id, position, now)
        }
    }

    /**
     * Deletes the routine, its exercises and the program days that use it
     * (both cascade), with a tombstone for each. Workouts started from it are kept.
     */
    suspend fun delete(routineId: String) = db.withTransaction {
        dao.getRoutine(routineId) ?: return@withTransaction
        val now = clock()
        db.recordDeletes(SyncTable.PROGRAM_DAYS, dao.programDayIdsUsing(routineId), now)
        db.recordDeletes(SyncTable.ROUTINE_EXERCISES, dao.routineExerciseIds(routineId), now)
        db.recordDeletes(SyncTable.ROUTINES, listOf(routineId), now)
        dao.deleteRoutine(routineId)
    }
}
