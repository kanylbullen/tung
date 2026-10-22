package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.addedExerciseSets
import io.github.kanylbullen.tung.core.addsNextSet
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.prefillSet
import io.github.kanylbullen.tung.core.routineStartSets

/**
 * The rules of the commands, once, over a [CommandTx] (issue #87): what to
 * read, what to write and in which order, so the app's `AppCommandHandler`
 * and the server's `ServerCommandHandler` cannot drift apart. Each handler
 * validates, resolves names, opens its own transaction and calls in here;
 * the exercise row an [ExerciseRef] resolves to is the handler's business
 * (it needs the catalogue and the exercise table), the rest is not.
 *
 * Every refusal is decided before the first write. An id that does not
 * exist is [Rejected], naming it, on every platform.
 *
 * Moved so far: every workout command (`start_workout`, `start_routine`,
 * `add_exercise`, `remove_exercise`, `add_set`, `remove_set`, `set_weight`,
 * `set_reps`, `tick_set`, `log_set`, `finish_workout`, `delete_workout`,
 * `apply_plan`). The routine and program commands follow one by one; until
 * then their rules are still in the handlers.
 */
class CommandExecutor(private val ids: () -> String = { newId() }) {

    /** `start_workout` without a routine: the workout in progress (there is at most one), else a new one started now by [startedBy]. */
    suspend fun startWorkout(tx: CommandTx, startedBy: String?): CommandResult {
        tx.activeWorkout()?.let { return Done(mapOf(WORKOUT_ID to it.id)) }
        return Done(mapOf(WORKOUT_ID to insertWorkout(tx, startedBy, routineId = null, programDayId = null)))
    }

    /**
     * `start_routine`: the workout in progress if there is one (never a
     * second), else a new workout from the routine, recording [routineId] and
     * [programDayId]: its exercises in order with `targetSets` un-ticked sets
     * each, filled by [routineStartSets] (last session's set at the same
     * position, past its end the set before, 0 kg × `targetReps` with no
     * history), and the routine marked used.
     */
    suspend fun startRoutine(tx: CommandTx, routineId: String, programDayId: String?, startedBy: String?): CommandResult {
        tx.activeWorkout()?.let { return Done(mapOf(WORKOUT_ID to it.id)) }
        tx.routine(routineId) ?: return noSuch("routine", routineId)
        if (programDayId != null) tx.programDay(programDayId) ?: return noSuch("program day", programDayId)
        val workoutId = insertWorkout(tx, startedBy, routineId, programDayId)
        tx.routineExercises(routineId).forEachIndexed { position, re ->
            val row = WorkoutExerciseRow(ids(), workoutId, re.exerciseId, position)
            tx.insertWorkoutExercise(row)
            routineStartSets(re.targetSets, re.targetReps, tx.previousSession(re.exerciseId, workoutId))
                .forEachIndexed { setPosition, values ->
                    tx.insertSet(SetRow(ids(), row.id, setPosition, values.weightKg, values.reps, completed = false))
                }
        }
        tx.markRoutineUsed(routineId)
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    /**
     * `finish_workout`: sets that are not logged ([isLogged]: ticked with
     * reps) are dropped, then exercises left without sets, and a workout
     * without one logged set is deleted rather than kept empty (then no
     * [WORKOUT_ID] comes back). It ends at [endedAt], or now (at its start if
     * that lies ahead, a device clock that runs fast); an end before the start
     * is refused. A workout already finished is left as it is.
     */
    suspend fun finishWorkout(tx: CommandTx, workoutId: String, endedAt: Long? = null): CommandResult {
        val workout = tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        if (workout.endedAt != null) return Done(mapOf(WORKOUT_ID to workoutId))
        val end = endedAt ?: maxOf(tx.now(), workout.startedAt)
        if (end < workout.startedAt) return Rejected(listOf("ended_at is before started_at"))
        var logged = 0
        for (we in tx.workoutExercises(workoutId)) {
            val (keep, drop) = tx.sets(we.id).partition { isLogged(it.completed, it.reps) }
            logged += keep.size
            if (keep.isEmpty()) tx.deleteWorkoutExercise(we.id) else drop.forEach { tx.deleteSet(it.id) }
        }
        if (logged == 0) {
            tx.deleteWorkout(workoutId)
            return Done()
        }
        tx.endWorkout(workoutId, end)
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    /** `delete_workout`: the workout with its exercises and sets. */
    suspend fun deleteWorkout(tx: CommandTx, workoutId: String): CommandResult {
        tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        tx.deleteWorkout(workoutId)
        return Done()
    }

    /**
     * `apply_plan` once every exercise is a row: the workout in progress, or a
     * new one started by [startedBy], gets each of [exercises] appended in
     * order with its planned sets, none ticked; a number the plan leaves out
     * comes from the pre-fill rule ([plannedSetValues]).
     */
    suspend fun applyPlan(tx: CommandTx, exercises: List<Pair<String, List<PlannedSet>>>, startedBy: String?): CommandResult {
        val workoutId = tx.activeWorkout()?.id ?: insertWorkout(tx, startedBy, routineId = null, programDayId = null)
        for ((exerciseId, planned) in exercises) {
            val position = (tx.workoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) + 1
            val row = WorkoutExerciseRow(ids(), workoutId, exerciseId, position)
            tx.insertWorkoutExercise(row)
            plannedSetValues(planned, tx.previousSession(exerciseId, workoutId)).forEachIndexed { setPosition, values ->
                tx.insertSet(SetRow(ids(), row.id, setPosition, values.weightKg, values.reps, completed = false))
            }
        }
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    private suspend fun insertWorkout(tx: CommandTx, startedBy: String?, routineId: String?, programDayId: String?): String {
        val row = WorkoutRow(ids(), startedAt = tx.now(), endedAt = null, routineId = routineId, programDayId = programDayId, startedBy = startedBy)
        tx.insertWorkout(row)
        return row.id
    }

    /**
     * `add_exercise` once the exercise is the row [exerciseId]: appended after
     * the workout's last exercise with [sets] sets, or, when null, as many as
     * the exercise had last session (at least one), each pre-filled at its own
     * position by [addedExerciseSets]; nothing ticked.
     */
    suspend fun addExercise(tx: CommandTx, workoutId: String, exerciseId: String, sets: Int?): CommandResult {
        tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        val position = (tx.workoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) + 1
        val row = WorkoutExerciseRow(ids(), workoutId, exerciseId, position)
        tx.insertWorkoutExercise(row)
        addedExerciseSets(sets, tx.previousSession(exerciseId, workoutId)).forEachIndexed { setPosition, values ->
            tx.insertSet(SetRow(ids(), row.id, setPosition, values.weightKg, values.reps, completed = false))
        }
        return Done(mapOf(WORKOUT_EXERCISE_ID to row.id, EXERCISE_ID to exerciseId))
    }

    /**
     * `add_set`: [count] sets (null for one) after the exercise's last, each
     * pre-filled by the usual rule, a copy of the one before it
     * ([prefillSet]). The first added set's id comes back as [SET_ID].
     */
    suspend fun addSet(tx: CommandTx, workoutExerciseId: String, count: Int?): CommandResult {
        val we = tx.workoutExercise(workoutExerciseId) ?: return noSuch("exercise in a workout", workoutExerciseId)
        val ids = List((count ?: 1).coerceAtLeast(1)) { insertPrefilledSet(tx, we) }
        return Done(mapOf(SET_ID to ids.first()))
    }

    /**
     * `log_set`: ticks the set and nothing more (issue #64). Without a set id
     * it takes the current set by the default rule ([currentSet]: the first
     * un-ticked set of the first exercise that has one). A set with 0 reps is
     * refused, as it would not count as logged: every client asks for the reps
     * first. With [Command.LogSet.addNext] it also adds the next set when the
     * logged one was the last of its exercise and not past a routine's target
     * sets ([addsNextSet]), returned as [ADDED_SET_ID].
     */
    suspend fun logSet(tx: CommandTx, command: Command.LogSet): CommandResult {
        tx.workout(command.workoutId) ?: return noSuch("workout", command.workoutId)
        val setId = command.setId
            ?: currentSetId(tx, command.workoutId)
            ?: return Rejected(listOf("no un-ticked set left in workout ${command.workoutId}"))
        val set = tx.set(setId) ?: return noSuch("set", setId)
        val we = tx.workoutExercise(set.workoutExerciseId) ?: return noSuch("set", setId)
        if (we.workoutId != command.workoutId) return Rejected(listOf("set $setId is not in workout ${command.workoutId}"))
        if (set.reps <= 0) return Rejected(listOf("set $setId has 0 reps: set the reps first (set_reps), then log it"))
        tx.tickSet(setId, true)
        if (!command.addNext) return Done(mapOf(SET_ID to setId))
        val sets = tx.sets(we.id)
        val wasLast = sets.maxOf { it.position } == set.position
        val target = if (wasLast) tx.routineTargetSets(command.workoutId, we.exerciseId) else null
        val added = if (addsNextSet(wasLast, sets.size, target)) insertPrefilledSet(tx, we) else null
        return Done(listOfNotNull(SET_ID to setId, added?.let { ADDED_SET_ID to it }).toMap())
    }

    /** `tick_set`: the tick on a row, on or off, nothing else. */
    suspend fun tickSet(tx: CommandTx, setId: String, done: Boolean): CommandResult {
        tx.set(setId) ?: return noSuch("set", setId)
        tx.tickSet(setId, done)
        return Done(mapOf(SET_ID to setId))
    }

    /** `set_weight`: the set's weight; with [applyToOpenSets] every un-ticked set of the same exercise gets it too. */
    suspend fun setWeight(tx: CommandTx, setId: String, weightKg: Double, applyToOpenSets: Boolean): CommandResult {
        val ids = setsToWrite(tx, setId, applyToOpenSets) ?: return noSuch("set", setId)
        ids.forEach { tx.setWeight(it, weightKg) }
        return Done(mapOf(SET_ID to setId))
    }

    /** `set_reps`: the set's reps; with [applyToOpenSets] every un-ticked set of the same exercise gets them too. */
    suspend fun setReps(tx: CommandTx, setId: String, reps: Int, applyToOpenSets: Boolean): CommandResult {
        val ids = setsToWrite(tx, setId, applyToOpenSets) ?: return noSuch("set", setId)
        ids.forEach { tx.setReps(it, reps) }
        return Done(mapOf(SET_ID to setId))
    }

    /** `remove_set`. */
    suspend fun removeSet(tx: CommandTx, setId: String): CommandResult {
        tx.set(setId) ?: return noSuch("set", setId)
        tx.deleteSet(setId)
        return Done()
    }

    /** `remove_exercise`: the exercise and its sets. */
    suspend fun removeExercise(tx: CommandTx, workoutExerciseId: String): CommandResult {
        tx.workoutExercise(workoutExerciseId) ?: return noSuch("exercise in a workout", workoutExerciseId)
        tx.deleteWorkoutExercise(workoutExerciseId)
        return Done()
    }

    /**
     * The set and, with [openSets], every un-ticked set of the same exercise
     * ("Apply to all sets": ticked sets stay as logged, the edited one gets the
     * value even if ticked); null when the set does not exist.
     */
    private suspend fun setsToWrite(tx: CommandTx, setId: String, openSets: Boolean): List<String>? {
        val set = tx.set(setId) ?: return null
        if (!openSets) return listOf(setId)
        return tx.sets(set.workoutExerciseId).filter { it.id == setId || !it.completed }.map { it.id }
    }

    private suspend fun currentSetId(tx: CommandTx, workoutId: String): String? {
        val sets = tx.workoutExercises(workoutId).map { tx.sets(it.id) }
        val index = currentSet(sets.map { list -> list.map { it.completed } }) ?: return null
        return sets[index.exercise][index.set].id
    }

    /** A new last set for [we], pre-filled by the usual rule: the set before it, else last session's at this position, else 0 × 0. */
    private suspend fun insertPrefilledSet(tx: CommandTx, we: WorkoutExerciseRow): String {
        val existing = tx.sets(we.id)
        val values = prefillSet(existing.lastOrNull()?.values, tx.previousSession(we.exerciseId, we.workoutId), existing.size)
            ?: LoggedSet(weightKg = 0.0, reps = 0)
        val row = SetRow(ids(), we.id, (existing.maxOfOrNull { it.position } ?: -1) + 1, values.weightKg, values.reps, completed = false)
        tx.insertSet(row)
        return row.id
    }

    private fun noSuch(what: String, id: String): Rejected = Rejected(listOf("no $what $id"))
}
