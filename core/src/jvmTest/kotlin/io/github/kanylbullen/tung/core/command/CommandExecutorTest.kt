package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of `add_exercise`, `add_set` and `log_set` over an in-memory
 * store: what the app's and the server's command tests check against their
 * databases, once, where the rule lives (issue #87).
 */
class CommandExecutorTest {

    private val tx = MemoryCommandTx()
    private var ids = 0
    private val executor = CommandExecutor { "id${++ids}" }

    private fun done(block: suspend () -> CommandResult): CommandResult.Done {
        val result = runBlocking { block() }
        assertTrue("$result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    private fun rejected(block: suspend () -> CommandResult): CommandResult.Rejected {
        val result = runBlocking { block() }
        assertTrue("$result", result is CommandResult.Rejected)
        return result as CommandResult.Rejected
    }

    private fun values(workoutExerciseId: String) = runBlocking { tx.sets(workoutExerciseId) }.map { it.weightKg to it.reps }

    // --- add_exercise ---

    @Test
    fun withoutHistoryAnAddedExerciseHasOneEmptySet() {
        val workoutId = tx.addWorkout()
        val weId = done { executor.addExercise(tx, workoutId, "bench", sets = null) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(listOf(0.0 to 0), values(weId))
        assertTrue(runBlocking { tx.sets(weId) }.none { it.completed })
    }

    @Test
    fun withHistoryItGetsLastSessionsSetsAtTheirPositions() {
        tx.history["bench"] = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(85.0, 5))
        val workoutId = tx.addWorkout()
        val weId = done { executor.addExercise(tx, workoutId, "bench", sets = null) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8, 85.0 to 6, 85.0 to 5), values(weId))
        assertEquals(listOf(0, 1, 2), runBlocking { tx.sets(weId) }.map { it.position })
    }

    @Test
    fun aSetCountWinsOverHistoryAndPastItsEndRepeatsTheSetBefore() {
        tx.history["bench"] = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6))
        val workoutId = tx.addWorkout()
        val four = done { executor.addExercise(tx, workoutId, "bench", sets = 4) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8, 85.0 to 6, 85.0 to 6, 85.0 to 6), values(four))
        val one = done { executor.addExercise(tx, workoutId, "bench", sets = 1) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8), values(one))
        val squat = done { executor.addExercise(tx, workoutId, "squat", sets = 4) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals("no history: 0 × 0 each", List(4) { 0.0 to 0 }, values(squat))
    }

    @Test
    fun anAddedExerciseComesAfterTheWorkoutsLast() {
        val workoutId = tx.addWorkout()
        tx.addExercise(workoutId, "squat")
        tx.addExercise(workoutId, "row")
        val weId = done { executor.addExercise(tx, workoutId, "bench", sets = 1) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(2, runBlocking { tx.workoutExercise(weId) }?.position)
    }

    @Test
    fun addingToAWorkoutThatDoesNotExistWritesNothing() {
        assertEquals(listOf("no workout nope"), rejected { executor.addExercise(tx, "nope", "bench", sets = null) }.reasons)
        assertEquals(emptyList<String>(), tx.writes)
    }

    // --- add_set ---

    @Test
    fun addSetCopiesTheSetBeforeItAndACountAddsThatMany() {
        val workoutId = tx.addWorkout()
        val weId = tx.addExercise(workoutId, "bench")
        tx.addSet(weId, 60.0, 8)
        val added = done { executor.addSet(tx, weId, count = 3) }.ids.getValue(SET_ID)
        assertEquals(List(4) { 60.0 to 8 }, values(weId))
        val all = runBlocking { tx.sets(weId) }
        assertEquals("the first added set is returned", all[1].id, added)
        assertEquals(listOf(0, 1, 2, 3), all.map { it.position })
        done { executor.addSet(tx, weId, count = null) }
        assertEquals("without a count, one", 5, runBlocking { tx.sets(weId) }.size)
    }

    @Test
    fun addSetOnAnEmptyExerciseTakesLastSessionsFirstSetElseNothing() {
        tx.history["bench"] = listOf(LoggedSet(80.0, 8))
        val workoutId = tx.addWorkout()
        val bench = tx.addExercise(workoutId, "bench")
        val squat = tx.addExercise(workoutId, "squat")
        done { executor.addSet(tx, bench, count = null) }
        done { executor.addSet(tx, squat, count = null) }
        assertEquals(listOf(80.0 to 8), values(bench))
        assertEquals(listOf(0.0 to 0), values(squat))
    }

    @Test
    fun addSetOnAnExerciseThatDoesNotExistIsRefused() {
        assertEquals(listOf("no exercise in a workout nope"), rejected { executor.addSet(tx, "nope", count = null) }.reasons)
        assertEquals(emptyList<String>(), tx.writes)
    }

    // --- log_set ---

    /** A workout with bench press: [count] sets of 80 × 8, none ticked. */
    private fun benchWorkout(count: Int, routine: Boolean = false): Pair<String, String> {
        val workoutId = tx.addWorkout(routineId = if (routine) "push" else null)
        val weId = tx.addExercise(workoutId, "bench")
        repeat(count) { tx.addSet(weId, 80.0, 8) }
        return workoutId to weId
    }

    @Test
    fun logSetOnlyTicksEvenTheLastSet() {
        val (workoutId, weId) = benchWorkout(2)
        val (first, second) = runBlocking { tx.sets(weId) }
        assertEquals(first.id, done { executor.logSet(tx, Command.LogSet(workoutId, first.id)) }.ids[SET_ID])
        val two = done { executor.logSet(tx, Command.LogSet(workoutId, second.id)) }
        assertNull("no set is added after the last", two.ids[ADDED_SET_ID])
        assertEquals(listOf(true, true), runBlocking { tx.sets(weId) }.map { it.completed })
        assertEquals(listOf("tick ${first.id} true", "tick ${second.id} true"), tx.writes)
    }

    @Test
    fun withoutASetIdItTicksTheCurrentSetByTheDefaultRule() {
        val (workoutId, weId) = benchWorkout(2)
        val squat = tx.addExercise(workoutId, "squat")
        tx.addSet(squat, 100.0, 5)
        val (first, second) = runBlocking { tx.sets(weId) }
        assertEquals(first.id, done { executor.logSet(tx, Command.LogSet(workoutId)) }.ids[SET_ID])
        assertEquals(second.id, done { executor.logSet(tx, Command.LogSet(workoutId)) }.ids[SET_ID])
        assertEquals("then the next exercise's", runBlocking { tx.sets(squat) }.single().id, done { executor.logSet(tx, Command.LogSet(workoutId)) }.ids[SET_ID])
        assertEquals(listOf("no un-ticked set left in workout $workoutId"), rejected { executor.logSet(tx, Command.LogSet(workoutId)) }.reasons)
    }

    @Test
    fun addNextAddsACopyAfterTheLastSetOnly() {
        val (workoutId, weId) = benchWorkout(2)
        val (first, second) = runBlocking { tx.sets(weId) }
        assertNull(done { executor.logSet(tx, Command.LogSet(workoutId, first.id, addNext = true)) }.ids[ADDED_SET_ID])
        val added = done { executor.logSet(tx, Command.LogSet(workoutId, second.id, addNext = true)) }.ids.getValue(ADDED_SET_ID)
        val after = runBlocking { tx.sets(weId) }
        assertEquals(3, after.size)
        assertEquals(added, after.last().id)
        assertEquals(80.0 to 8, after.last().weightKg to after.last().reps)
        assertEquals(false, after.last().completed)
    }

    @Test
    fun addNextRespectsTheRoutinesTargetSets() {
        val (workoutId, weId) = benchWorkout(2, routine = true)
        tx.targets["bench"] = 2
        val (first, second) = runBlocking { tx.sets(weId) }
        done { executor.logSet(tx, Command.LogSet(workoutId, first.id, addNext = true)) }
        assertNull(
            "the routine's second set of two adds no third",
            done { executor.logSet(tx, Command.LogSet(workoutId, second.id, addNext = true)) }.ids[ADDED_SET_ID],
        )
        assertEquals(2, runBlocking { tx.sets(weId) }.size)
        tx.targets["bench"] = 4
        val third = done { executor.addSet(tx, weId, count = null) }.ids.getValue(SET_ID)
        assertEquals("under the target, add_next adds a fourth", 4, runBlocking {
            executor.logSet(tx, Command.LogSet(workoutId, third, addNext = true))
            tx.sets(weId)
        }.size)
    }

    // --- tick_set, set_weight, set_reps, remove_set, remove_exercise ---

    @Test
    fun tickSetTicksAndUnticksOneSet() {
        val (_, weId) = benchWorkout(2)
        val (first, second) = runBlocking { tx.sets(weId) }
        done { executor.tickSet(tx, first.id, done = true) }
        assertEquals(listOf(true, false), runBlocking { tx.sets(weId) }.map { it.completed })
        done { executor.tickSet(tx, first.id, done = false) }
        assertEquals(listOf(false, false), runBlocking { tx.sets(weId) }.map { it.completed })
        assertEquals(listOf("no set nope"), rejected { executor.tickSet(tx, "nope", done = true) }.reasons)
        assertEquals(second.id, runBlocking { tx.sets(weId) }[1].id)
    }

    @Test
    fun applyToOpenSetsWritesTheEditedSetAndEveryUntickedOneOfItsExercise() {
        val (_, weId) = benchWorkout(3)
        val other = tx.addExercise(runBlocking { tx.workoutExercise(weId) }?.workoutId.orEmpty(), "squat")
        tx.addSet(other, 100.0, 5)
        val (first, second, third) = runBlocking { tx.sets(weId) }
        done { executor.tickSet(tx, first.id, done = true) }
        done { executor.tickSet(tx, second.id, done = true) }
        done { executor.setWeight(tx, second.id, 82.5, applyToOpenSets = true) }
        done { executor.setReps(tx, third.id, 6, applyToOpenSets = true) }
        assertEquals(
            "the weight: the ticked first set stays, the edited second gets it even though ticked, the open third too; the reps: the third and the open sets, so not the ticked second",
            listOf(80.0 to 8, 82.5 to 8, 82.5 to 6),
            values(weId),
        )
        assertEquals("another exercise is untouched", listOf(100.0 to 5), values(other))
        done { executor.setWeight(tx, first.id, 70.0, applyToOpenSets = false) }
        assertEquals(listOf(70.0 to 8, 82.5 to 6, 82.5 to 6), values(weId))
        assertEquals(listOf("no set nope"), rejected { executor.setReps(tx, "nope", 5, applyToOpenSets = true) }.reasons)
    }

    @Test
    fun removeSetAndRemoveExerciseDeleteWhatTheyNameAndRefuseWhatIsGone() {
        val (workoutId, weId) = benchWorkout(2)
        val squat = tx.addExercise(workoutId, "squat")
        tx.addSet(squat, 100.0, 5)
        val (first, _) = runBlocking { tx.sets(weId) }
        done { executor.removeSet(tx, first.id) }
        assertEquals(1, runBlocking { tx.sets(weId) }.size)
        assertEquals(listOf("no set ${first.id}"), rejected { executor.removeSet(tx, first.id) }.reasons)
        done { executor.removeExercise(tx, squat) }
        assertNull(runBlocking { tx.workoutExercise(squat) })
        assertEquals("its sets go with it", emptyList<SetRow>(), runBlocking { tx.sets(squat) })
        assertEquals(listOf("no exercise in a workout $squat"), rejected { executor.removeExercise(tx, squat) }.reasons)
        assertEquals(listOf("delete set ${first.id}", "delete exercise $squat"), tx.writes)
    }

    @Test
    fun logSetRefusesWhatItCannotLogAndWritesNothing() {
        val (workoutId, weId) = benchWorkout(1)
        val set = runBlocking { tx.sets(weId) }.single()
        val other = tx.addWorkout()
        assertEquals(listOf("no workout nope"), rejected { executor.logSet(tx, Command.LogSet("nope", set.id)) }.reasons)
        assertEquals(listOf("no set nope"), rejected { executor.logSet(tx, Command.LogSet(workoutId, "nope")) }.reasons)
        assertEquals(listOf("set ${set.id} is not in workout $other"), rejected { executor.logSet(tx, Command.LogSet(other, set.id)) }.reasons)
        val empty = tx.addSet(weId, 0.0, 0)
        assertEquals(
            listOf("set $empty has 0 reps: set the reps first (set_reps), then log it"),
            rejected { executor.logSet(tx, Command.LogSet(workoutId, empty)) }.reasons,
        )
        assertEquals(emptyList<String>(), tx.writes)
    }
}
