package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.addedExerciseSets
import io.github.kanylbullen.tung.core.addsNextSet
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.prefillSet

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
 * Moved so far: `add_exercise`, `add_set`, `log_set`. The other commands
 * follow one by one; until then their rules are still in the handlers.
 */
class CommandExecutor(private val ids: () -> String = { newId() }) {

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
