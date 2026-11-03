package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.DELETED_COUNT
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_DAY_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules of the commands ([CommandRules]) over an in-memory store: what
 * the app's and the server's command tests check against their databases,
 * once, where the rule lives (issue #87). [CommandExecutorTest] covers what
 * the executor adds on top: validation, resolution and the rows.
 */
class CommandRulesTest {

    private val tx = MemoryCommandTx()
    private var ids = 0
    private val executor = CommandRules { "id${++ids}" }

    private fun done(block: suspend () -> CommandResult): CommandResult.Done {
        val result = runNow { block() }
        assertTrue(result is CommandResult.Done, "$result")
        return result as CommandResult.Done
    }

    private fun rejected(block: suspend () -> CommandResult): CommandResult.Rejected {
        val result = runNow { block() }
        assertTrue(result is CommandResult.Rejected, "$result")
        return result as CommandResult.Rejected
    }

    private fun values(workoutExerciseId: String) = runNow { tx.sets(workoutExerciseId) }.map { it.weightKg to it.reps }

    // --- add_exercise ---

    @Test
    fun withoutHistoryAnAddedExerciseHasOneEmptySet() {
        val workoutId = tx.addWorkout()
        val weId = done { executor.addExercise(tx, workoutId, "bench", sets = null) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(listOf(0.0 to 0), values(weId))
        assertTrue(runNow { tx.sets(weId) }.none { it.completed })
    }

    @Test
    fun withHistoryItGetsLastSessionsSetsAtTheirPositions() {
        tx.history["bench"] = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(85.0, 5))
        val workoutId = tx.addWorkout()
        val weId = done { executor.addExercise(tx, workoutId, "bench", sets = null) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(listOf(80.0 to 8, 85.0 to 6, 85.0 to 5), values(weId))
        assertEquals(listOf(0, 1, 2), runNow { tx.sets(weId) }.map { it.position })
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
        assertEquals(List(4) { 0.0 to 0 }, values(squat), "no history: 0 × 0 each")
    }

    @Test
    fun anAddedExerciseComesAfterTheWorkoutsLast() {
        val workoutId = tx.addWorkout()
        tx.addExercise(workoutId, "squat")
        tx.addExercise(workoutId, "row")
        val weId = done { executor.addExercise(tx, workoutId, "bench", sets = 1) }.ids.getValue(WORKOUT_EXERCISE_ID)
        assertEquals(2, runNow { tx.workoutExercise(weId) }?.position)
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
        val all = runNow { tx.sets(weId) }
        assertEquals(all[1].id, added, "the first added set is returned")
        assertEquals(listOf(0, 1, 2, 3), all.map { it.position })
        done { executor.addSet(tx, weId, count = null) }
        assertEquals(5, runNow { tx.sets(weId) }.size, "without a count, one")
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
        val (first, second) = runNow { tx.sets(weId) }
        assertEquals(first.id, done { executor.logSet(tx, Command.LogSet(workoutId, first.id)) }.ids[SET_ID])
        val two = done { executor.logSet(tx, Command.LogSet(workoutId, second.id)) }
        assertNull(two.ids[ADDED_SET_ID], "no set is added after the last")
        assertEquals(listOf(true, true), runNow { tx.sets(weId) }.map { it.completed })
        assertEquals(listOf("tick ${first.id} true", "tick ${second.id} true"), tx.writes)
    }

    @Test
    fun withoutASetIdItTicksTheCurrentSetByTheDefaultRule() {
        val (workoutId, weId) = benchWorkout(2)
        val squat = tx.addExercise(workoutId, "squat")
        tx.addSet(squat, 100.0, 5)
        val (first, second) = runNow { tx.sets(weId) }
        assertEquals(first.id, done { executor.logSet(tx, Command.LogSet(workoutId)) }.ids[SET_ID])
        assertEquals(second.id, done { executor.logSet(tx, Command.LogSet(workoutId)) }.ids[SET_ID])
        assertEquals(runNow { tx.sets(squat) }.single().id, done { executor.logSet(tx, Command.LogSet(workoutId)) }.ids[SET_ID], "then the next exercise's")
        assertEquals(listOf("no un-ticked set left in workout $workoutId"), rejected { executor.logSet(tx, Command.LogSet(workoutId)) }.reasons)
    }

    @Test
    fun addNextAddsACopyAfterTheLastSetOnly() {
        val (workoutId, weId) = benchWorkout(2)
        val (first, second) = runNow { tx.sets(weId) }
        assertNull(done { executor.logSet(tx, Command.LogSet(workoutId, first.id, addNext = true)) }.ids[ADDED_SET_ID])
        val added = done { executor.logSet(tx, Command.LogSet(workoutId, second.id, addNext = true)) }.ids.getValue(ADDED_SET_ID)
        val after = runNow { tx.sets(weId) }
        assertEquals(3, after.size)
        assertEquals(added, after.last().id)
        assertEquals(80.0 to 8, after.last().weightKg to after.last().reps)
        assertEquals(false, after.last().completed)
    }

    @Test
    fun addNextRespectsTheRoutinesTargetSets() {
        val (workoutId, weId) = benchWorkout(2, routine = true)
        tx.targets["bench"] = 2
        val (first, second) = runNow { tx.sets(weId) }
        done { executor.logSet(tx, Command.LogSet(workoutId, first.id, addNext = true)) }
        assertNull(
            done { executor.logSet(tx, Command.LogSet(workoutId, second.id, addNext = true)) }.ids[ADDED_SET_ID],
            "the routine's second set of two adds no third",
        )
        assertEquals(2, runNow { tx.sets(weId) }.size)
        tx.targets["bench"] = 4
        val third = done { executor.addSet(tx, weId, count = null) }.ids.getValue(SET_ID)
        assertEquals(4,runNow {
            executor.logSet(tx, Command.LogSet(workoutId, third, addNext = true))
            tx.sets(weId)
        }.size,"under the target, add_next adds a fourth")
    }

    // --- start_workout, start_routine ---

    @Test
    fun startWorkoutReturnsTheOneInProgressElseStartsOneNow() {
        tx.now = 5_000L
        val first = done { executor.startWorkout(tx, "phone") }.ids.getValue(WORKOUT_ID)
        val row = runNow { tx.workout(first) }
        assertEquals(5_000L to "phone", row?.startedAt to row?.startedBy)
        assertEquals(first, done { executor.startWorkout(tx, "watch") }.ids.getValue(WORKOUT_ID), "the same one again")
        done { executor.finishWorkout(tx, first) }
        assertTrue(done { executor.startWorkout(tx, "phone") }.ids.getValue(WORKOUT_ID) != first, "a new one once it is over")
    }

    @Test
    fun aWorkoutLoggedAfterTheFactStartsWhenItWasNeverAhead() {
        tx.now = 100_000_000L
        val past = done { executor.startWorkout(tx, "phone", startedAt = 40_000_000L) }.ids.getValue(WORKOUT_ID)
        assertEquals(40_000_000L, runNow { tx.workout(past) }?.startedAt)
        done { executor.deleteWorkout(tx, past) }
        tx.writes.clear()
        assertEquals(
            listOf("started_at is in the future"),
            rejected { executor.startWorkout(tx, "phone", startedAt = 100_000_000L + 61_000L) }.reasons,
        )
        assertEquals(emptyList<String>(), tx.writes)
        val skewed = done { executor.startWorkout(tx, "phone", startedAt = 100_000_000L + 30_000L) }.ids.getValue(WORKOUT_ID)
        assertEquals(100_030_000L, runNow { tx.workout(skewed) }?.startedAt, "a clock a little ahead is fine")
        assertEquals(skewed, done { executor.startWorkout(tx, "phone", startedAt = 50_000_000L) }.ids[WORKOUT_ID], "the one in progress, never a second")
    }

    @Test
    fun startRoutineFillsTheTargetSetsFromLastSessionAndMarksTheRoutineUsed() {
        tx.history["bench"] = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(85.0, 5))
        val routineId = tx.addRoutine()
        tx.addRoutineExercise(routineId, "bench", targetSets = 2, targetReps = 8)
        tx.addRoutineExercise(routineId, "squat", targetSets = 2, targetReps = 5)
        val day = tx.addProgramDay(routineId)
        tx.now = 9_000L
        val workoutId = done { executor.startRoutine(tx, routineId, day, "phone") }.ids.getValue(WORKOUT_ID)
        val workout = runNow { tx.workout(workoutId) }
        assertEquals(Triple(routineId, day, "phone"), Triple(workout?.routineId, workout?.programDayId, workout?.startedBy))
        val (bench, squat) = runNow { tx.workoutExercises(workoutId) }
        assertEquals(listOf(80.0 to 8, 85.0 to 6), values(bench.id), "the routine's two, not last session's three")
        assertEquals(listOf(0.0 to 5, 0.0 to 5), values(squat.id), "no history: 0 kg at the target reps")
        assertEquals(9_000L, runNow { tx.routine(routineId) }?.lastUsedAt)
        assertEquals(workoutId, done { executor.startRoutine(tx, routineId, null, "phone") }.ids.getValue(WORKOUT_ID), "the one in progress, not a second")
    }

    @Test
    fun startRoutineRefusesAMissingRoutineOrDayAndWritesNothing() {
        val routineId = tx.addRoutine()
        assertEquals(listOf("no routine nope"), rejected { executor.startRoutine(tx, "nope", null, null) }.reasons)
        assertEquals(listOf("no program day nope"), rejected { executor.startRoutine(tx, routineId, "nope", null) }.reasons)
        assertEquals(emptyList<String>(), tx.writes)
    }

    // --- finish_workout, delete_workout ---

    @Test
    fun finishDropsWhatIsNotLoggedAndEndsTheWorkout() {
        val (workoutId, weId) = benchWorkout(3)
        val (first, second, third) = runNow { tx.sets(weId) }
        val empty = tx.addExercise(workoutId, "squat")
        tx.addSet(empty, 100.0, 5)
        val noReps = tx.addExercise(workoutId, "row")
        tx.addSet(noReps, 40.0, 0, completed = true)
        done { executor.tickSet(tx, first.id, true) }
        done { executor.tickSet(tx, third.id, true) }
        tx.now = 7_000L
        assertEquals(workoutId, done { executor.finishWorkout(tx, workoutId) }.ids[WORKOUT_ID])
        assertEquals(listOf(first.id, third.id), runNow { tx.sets(weId) }.map { it.id }, "the un-ticked second set is gone")
        assertEquals(listOf(weId), runNow { tx.workoutExercises(workoutId) }.map { it.id }, "exercises without a logged set are gone")
        assertEquals(7_000L, runNow { tx.workout(workoutId) }?.endedAt)
        assertEquals(workoutId, done { executor.finishWorkout(tx, workoutId) }.ids[WORKOUT_ID], "finishing again changes nothing")
        assertEquals(7_000L, runNow { tx.workout(workoutId) }?.endedAt)
    }

    @Test
    fun finishDiscardsAWorkoutWithNothingLogged() {
        val (workoutId, _) = benchWorkout(2)
        assertNull(done { executor.finishWorkout(tx, workoutId) }.ids[WORKOUT_ID], "no id: the workout is gone")
        assertNull(runNow { tx.workout(workoutId) })
        assertEquals(emptyList<WorkoutExerciseRow>(), tx.exercises)
        assertEquals(emptyList<SetRow>(), tx.allSets)
    }

    @Test
    fun finishRefusesAnEndInTheFuture() {
        tx.now = 100_000_000L
        val workoutId = tx.addWorkout(startedAt = 10_000_000L)
        val weId = tx.addExercise(workoutId, "bench")
        tx.addSet(weId, 80.0, 8, completed = true)
        tx.writes.clear()
        assertEquals(listOf("ended_at is in the future"), rejected { executor.finishWorkout(tx, workoutId, endedAt = 100_061_000L) }.reasons)
        assertEquals(emptyList<String>(), tx.writes)
        done { executor.finishWorkout(tx, workoutId, endedAt = 15_400_000L) }
        assertEquals(15_400_000L, runNow { tx.workout(workoutId) }?.endedAt, "ends when it really did")

        val ahead = tx.addWorkout(startedAt = 100_000_000L + 120_000L)
        tx.addSet(tx.addExercise(ahead, "bench"), 80.0, 8, completed = true)
        done { executor.finishWorkout(tx, ahead) }
        assertEquals(100_120_000L, runNow { tx.workout(ahead) }?.endedAt, "without ended_at a start a fast clock put ahead still finishes, at the start")
    }

    // --- set_workout_time ---

    @Test
    fun setWorkoutTimeMovesAFinishedWorkout() {
        tx.now = 200_000_000L
        val workoutId = tx.addWorkout(startedAt = 100_000_000L, endedAt = 103_600_000L)
        assertEquals(workoutId, done { executor.setWorkoutTime(tx, workoutId, 50_000_000L, 55_400_000L) }.ids[WORKOUT_ID])
        val row = runNow { tx.workout(workoutId) }
        assertEquals(50_000_000L to 55_400_000L, row?.startedAt to row?.endedAt)
        tx.writes.clear()
        done { executor.setWorkoutTime(tx, workoutId, 50_000_000L, 55_400_000L) }
        assertEquals(emptyList<String>(), tx.writes, "the same times again write nothing")
    }

    @Test
    fun setWorkoutTimeRefusesWhatCannotBe() {
        tx.now = 200_000_000L
        val finished = tx.addWorkout(startedAt = 100_000_000L, endedAt = 103_600_000L)
        val day = 24L * 60 * 60 * 1000
        assertEquals(listOf("a finished workout needs ended_at"), rejected { executor.setWorkoutTime(tx, finished, 90_000_000L, null) }.reasons)
        assertEquals(listOf("ended_at is before started_at"), rejected { executor.setWorkoutTime(tx, finished, 90_000_000L, 80_000_000L) }.reasons)
        assertEquals(listOf("ended_at is in the future"), rejected { executor.setWorkoutTime(tx, finished, 190_000_000L, 200_061_000L) }.reasons)
        assertEquals(listOf("a workout lasts at most 24 hours"), rejected { executor.setWorkoutTime(tx, finished, 10_000_000L, 10_000_001L + day) }.reasons)
        assertEquals(
            listOf("started_at is in the future", "ended_at is before started_at"),
            rejected { executor.setWorkoutTime(tx, finished, 300_000_000L, 190_000_000L) }.reasons,
        )
        assertEquals(listOf("no workout nope"), rejected { executor.setWorkoutTime(tx, "nope", 1L, 2L) }.reasons)

        val active = tx.addWorkout(startedAt = 150_000_000L)
        assertEquals(
            listOf("a workout in progress has no ended_at; finish it with finish_workout"),
            rejected { executor.setWorkoutTime(tx, active, 140_000_000L, 141_000_000L) }.reasons,
        )
        assertEquals(listOf("started_at is in the future"), rejected { executor.setWorkoutTime(tx, active, 200_061_000L, null) }.reasons)
        assertEquals(emptyList<String>(), tx.writes)
        done { executor.setWorkoutTime(tx, active, 140_000_000L, null) }
        val row = runNow { tx.workout(active) }
        assertEquals(140_000_000L to null, row?.startedAt to row?.endedAt)
    }

    @Test
    fun finishEndsAtTheGivenTimeNeverBeforeTheStart() {
        val workoutId = tx.addWorkout(startedAt = 10_000L)
        val weId = tx.addExercise(workoutId, "bench")
        tx.addSet(weId, 80.0, 8, completed = true)
        assertEquals(listOf("ended_at is before started_at"), rejected { executor.finishWorkout(tx, workoutId, endedAt = 9_000L) }.reasons)
        tx.now = 8_000L
        done { executor.finishWorkout(tx, workoutId) }
        assertEquals(10_000L, runNow { tx.workout(workoutId) }?.endedAt, "a clock behind the start ends it at the start")
        assertEquals(listOf("no workout nope"), rejected { executor.finishWorkout(tx, "nope") }.reasons)
    }

    @Test
    fun deleteWorkoutTakesItsExercisesAndSetsWithIt() {
        val (workoutId, _) = benchWorkout(2)
        val other = benchWorkout(1).first
        done { executor.deleteWorkout(tx, workoutId) }
        assertNull(runNow { tx.workout(workoutId) })
        assertEquals(listOf(other), tx.workouts.map { it.id })
        assertEquals(1, tx.allSets.size)
        assertEquals(listOf("no workout $workoutId"), rejected { executor.deleteWorkout(tx, workoutId) }.reasons)
    }

    // --- apply_plan ---

    @Test
    fun applyPlanAppendsThePlannedSetsToTheWorkoutInProgressOrANewOne() {
        tx.history["bench"] = listOf(LoggedSet(80.0, 8))
        val plan = listOf(
            "bench" to listOf(PlannedSet(null, 10), PlannedSet(null, 10)),
            "squat" to listOf(PlannedSet(100.0, 5)),
        )
        val workoutId = done { executor.applyPlan(tx, plan, "phone") }.ids.getValue(WORKOUT_ID)
        val (bench, squat) = runNow { tx.workoutExercises(workoutId) }
        assertEquals(listOf(80.0 to 10, 80.0 to 10), values(bench.id), "a missing weight comes from last session, then the set before")
        assertEquals(listOf(100.0 to 5), values(squat.id))
        assertEquals("phone", runNow { tx.workout(workoutId) }?.startedBy)
        assertEquals(workoutId, done { executor.applyPlan(tx, listOf("row" to listOf(PlannedSet(40.0, 12))), "phone") }.ids.getValue(WORKOUT_ID), "the second plan joins the workout in progress")
        assertEquals(listOf(0, 1, 2), runNow { tx.workoutExercises(workoutId) }.map { it.position })
    }

    // --- tick_set, set_weight, set_reps, remove_set, remove_exercise ---

    @Test
    fun tickSetTicksAndUnticksOneSet() {
        val (_, weId) = benchWorkout(2)
        val (first, second) = runNow { tx.sets(weId) }
        done { executor.tickSet(tx, first.id, done = true) }
        assertEquals(listOf(true, false), runNow { tx.sets(weId) }.map { it.completed })
        done { executor.tickSet(tx, first.id, done = false) }
        assertEquals(listOf(false, false), runNow { tx.sets(weId) }.map { it.completed })
        assertEquals(listOf("no set nope"), rejected { executor.tickSet(tx, "nope", done = true) }.reasons)
        assertEquals(second.id, runNow { tx.sets(weId) }[1].id)
    }

    @Test
    fun applyToOpenSetsWritesTheEditedSetAndEveryUntickedOneOfItsExercise() {
        val (_, weId) = benchWorkout(3)
        val other = tx.addExercise(runNow { tx.workoutExercise(weId) }?.workoutId.orEmpty(), "squat")
        tx.addSet(other, 100.0, 5)
        val (first, second, third) = runNow { tx.sets(weId) }
        done { executor.tickSet(tx, first.id, done = true) }
        done { executor.tickSet(tx, second.id, done = true) }
        done { executor.setWeight(tx, second.id, 82.5, applyToOpenSets = true) }
        done { executor.setReps(tx, third.id, 6, applyToOpenSets = true) }
        assertEquals(
            listOf(80.0 to 8, 82.5 to 8, 82.5 to 6),
            values(weId),
            "the weight: the ticked first set stays, the edited second gets it even though ticked, the open third too; the reps: the third and the open sets, so not the ticked second",
        )
        assertEquals(listOf(100.0 to 5), values(other), "another exercise is untouched")
        done { executor.setWeight(tx, first.id, 70.0, applyToOpenSets = false) }
        assertEquals(listOf(70.0 to 8, 82.5 to 8, 82.5 to 6), values(weId))
        assertEquals(listOf("no set nope"), rejected { executor.setReps(tx, "nope", 5, applyToOpenSets = true) }.reasons)
    }

    @Test
    fun removeSetAndRemoveExerciseDeleteWhatTheyNameAndRefuseWhatIsGone() {
        val (workoutId, weId) = benchWorkout(2)
        val squat = tx.addExercise(workoutId, "squat")
        tx.addSet(squat, 100.0, 5)
        val (first, _) = runNow { tx.sets(weId) }
        done { executor.removeSet(tx, first.id) }
        assertEquals(1, runNow { tx.sets(weId) }.size)
        assertEquals(listOf("no set ${first.id}"), rejected { executor.removeSet(tx, first.id) }.reasons)
        done { executor.removeExercise(tx, squat) }
        assertNull(runNow { tx.workoutExercise(squat) })
        assertEquals(emptyList<SetRow>(), runNow { tx.sets(squat) }, "its sets go with it")
        assertEquals(listOf("no exercise in a workout $squat"), rejected { executor.removeExercise(tx, squat) }.reasons)
        assertEquals(listOf("delete set ${first.id}", "delete exercise $squat"), tx.writes)
    }

    @Test
    fun logSetRefusesWhatItCannotLogAndWritesNothing() {
        val (workoutId, weId) = benchWorkout(1)
        val set = runNow { tx.sets(weId) }.single()
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

    // --- routines ---

    private fun routineOrder(routineId: String) = runNow { tx.routineExercises(routineId) }.map { it.exerciseId }

    private fun routinePositions(routineId: String) = runNow { tx.routineExercises(routineId) }.map { it.position }

    @Test
    fun createRoutineMakesAnEmptyRoutineWithATrimmedName() {
        val routineId = done { executor.createRoutine(tx, "  Legs ") }.ids.getValue(ROUTINE_ID)
        assertEquals(RoutineRow(routineId, "Legs", lastUsedAt = null), runNow { tx.routine(routineId) })
        assertEquals(emptyList<RoutineExerciseRow>(), runNow { tx.routineExercises(routineId) })
        assertEquals(listOf("insert routine $routineId"), tx.writes)
    }

    @Test
    fun saveRoutineTakesTheLoggedSetsPerExerciseAndTheMostCommonReps() {
        val workoutId = tx.addWorkout()
        val bench = tx.addExercise(workoutId, "bench")
        tx.addSet(bench, 80.0, 8, completed = true)
        tx.addSet(bench, 80.0, 8, completed = true)
        tx.addSet(bench, 80.0, 6, completed = true)
        tx.addSet(bench, 80.0, 6, completed = false)
        tx.addSet(bench, 80.0, 0, completed = true)
        val squat = tx.addExercise(workoutId, "squat")
        tx.addSet(squat, 100.0, 5, completed = false)
        val row = tx.addExercise(workoutId, "row")
        tx.addSet(row, 60.0, 10, completed = true)
        tx.addSet(row, 60.0, 12, completed = true)

        val routineId = done { executor.saveRoutine(tx, workoutId, " Push ") }.ids.getValue(ROUTINE_ID)
        assertEquals("Push", runNow { tx.routine(routineId) }?.name)
        assertEquals(
            listOf(Triple("bench", 3, 8), Triple("row", 2, 10)),
            runNow { tx.routineExercises(routineId) }.map { Triple(it.exerciseId, it.targetSets, it.targetReps) },
            "one target set per logged set, the most common reps (the first of a tie); squat had none logged",
        )
        assertEquals(listOf(0, 1), routinePositions(routineId))

        val again = done { executor.saveRoutine(tx, workoutId, "Push") }.ids.getValue(ROUTINE_ID)
        assertTrue(again != routineId, "saving twice makes two routines")
    }

    @Test
    fun saveRoutineRefusesAWorkoutWithNothingLoggedAndWritesNothing() {
        val workoutId = tx.addWorkout()
        tx.addSet(tx.addExercise(workoutId, "bench"), 80.0, 8, completed = false)
        assertEquals(listOf("the workout has no logged set to save"), rejected { executor.saveRoutine(tx, workoutId, "Push") }.reasons)
        assertEquals(listOf("no workout nope"), rejected { executor.saveRoutine(tx, "nope", "Push") }.reasons)
        assertEquals(emptyList<String>(), tx.writes)
    }

    @Test
    fun saveRoutinePlanTakesTheCallersIdsAndKeepsTargetSetsInRange() {
        val plan = listOf(RoutineExercisePlan("bench", 3, 8), RoutineExercisePlan("squat", 25, null))
        val imported = done { executor.saveRoutinePlan(tx, "Legs", plan, routineId = "r-import", routineExerciseIds = listOf("re-a", "re-b")) }
        assertEquals("r-import", imported.ids[ROUTINE_ID])
        assertEquals(
            listOf(RoutineExerciseRow("re-a", "r-import", "bench", 0, 3, 8), RoutineExerciseRow("re-b", "r-import", "squat", 1, 20, null)),
            runNow { tx.routineExercises("r-import") },
        )
        val own = done { executor.saveRoutinePlan(tx, "Legs", plan) }.ids.getValue(ROUTINE_ID)
        assertTrue(own.startsWith("id"), "new ids otherwise")
        assertEquals(listOf("bench", "squat"), routineOrder(own))
    }

    @Test
    fun renameRoutineTrimsAndRefusesWhatIsGone() {
        val routineId = tx.addRoutine(name = "Push")
        done { executor.renameRoutine(tx, routineId, " Push day ") }
        assertEquals("Push day", runNow { tx.routine(routineId) }?.name)
        assertEquals(listOf("no routine nope"), rejected { executor.renameRoutine(tx, "nope", "x") }.reasons)
    }

    @Test
    fun deleteRoutineTakesItsExercisesAndTheDaysThatStartIt() {
        val routineId = tx.addRoutine()
        val bench = tx.addRoutineExercise(routineId, "bench", 3)
        val squat = tx.addRoutineExercise(routineId, "squat", 3)
        val other = tx.addRoutine(name = "Pull")
        tx.addProgram("p1")
        val day1 = tx.addProgramDay(routineId, "p1")
        val day2 = tx.addProgramDay(other, "p1")
        val day3 = tx.addProgramDay(routineId, "p1")

        done { executor.deleteRoutine(tx, routineId) }
        assertNull(runNow { tx.routine(routineId) })
        assertEquals(listOf(day2), runNow { tx.programDays("p1") }.map { it.id })
        assertEquals(1, runNow { tx.programDay(day2) }?.position, "the other day keeps its position")
        assertEquals("Pull", runNow { tx.routine(other) }?.name, "the other routine stays")
        assertEquals(
            listOf("delete day $day1", "delete day $day3", "delete routine exercise $bench", "delete routine exercise $squat", "delete routine $routineId"),
            tx.writes,
        )
        assertEquals(listOf("no routine $routineId"), rejected { executor.deleteRoutine(tx, routineId) }.reasons)
    }

    @Test
    fun deleteRoutinesTakesEveryLiveOneOnceAndRefusesAListOfNone() {
        val a = tx.addRoutine(name = "A")
        val b = tx.addRoutine(name = "B")
        val kept = tx.addRoutine(name = "Kept")
        val benchOfA = tx.addRoutineExercise(a, "bench", 3)
        tx.addProgram("p1")
        val dayA = tx.addProgramDay(a, "p1")
        val dayKept = tx.addProgramDay(kept, "p1")

        val result = done { executor.deleteRoutines(tx, listOf(a, b, a, "gone")) }
        assertEquals("2", result.ids[DELETED_COUNT], "a duplicate counts once, an id that is gone is skipped")
        assertEquals(listOf(kept), runNow { tx.routines.map { it.id } })
        assertEquals(listOf(dayKept), runNow { tx.programDays("p1") }.map { it.id })
        assertEquals(
            listOf("delete day $dayA", "delete routine exercise $benchOfA", "delete routine $a", "delete routine $b"),
            tx.writes,
        )
        assertEquals(listOf("no routine $a"), rejected { executor.deleteRoutines(tx, listOf(a)) }.reasons)
        assertEquals(listOf("none of the 2 routines exists"), rejected { executor.deleteRoutines(tx, listOf(a, b)) }.reasons)
        assertEquals(4, tx.writes.size, "a refusal writes nothing")
    }

    @Test
    fun addRoutineExerciseAppendsLastWithItsTargets() {
        val routineId = tx.addRoutine()
        tx.addRoutineExercise(routineId, "bench", 3, 8)
        val result = done { executor.addRoutineExercise(tx, routineId, "squat", targetSets = 5, targetReps = 5) }
        val added = result.ids.getValue(ROUTINE_EXERCISE_ID)
        assertEquals("squat", result.ids[EXERCISE_ID])
        assertEquals(RoutineExerciseRow(added, routineId, "squat", 1, 5, 5), runNow { tx.routineExercise(added) })
        val clamped = done { executor.addRoutineExercise(tx, routineId, "row", targetSets = 99, targetReps = null) }.ids.getValue(ROUTINE_EXERCISE_ID)
        assertEquals(20, runNow { tx.routineExercise(clamped) }?.targetSets)
        assertEquals(listOf(0, 1, 2), routinePositions(routineId))
        assertEquals(listOf("no routine nope"), rejected { executor.addRoutineExercise(tx, "nope", "bench", 3, null) }.reasons)
    }

    @Test
    fun removeRoutineExerciseClosesTheGapAndWritesOnlyTheRowsThatMove() {
        val routineId = tx.addRoutine()
        val bench = tx.addRoutineExercise(routineId, "bench", 3)
        val squat = tx.addRoutineExercise(routineId, "squat", 3)
        val row = tx.addRoutineExercise(routineId, "row", 3)
        done { executor.removeRoutineExercise(tx, bench) }
        assertEquals(listOf("squat", "row"), routineOrder(routineId))
        assertEquals(listOf(0, 1), routinePositions(routineId))
        assertEquals(listOf("delete routine exercise $bench", "position $squat 0", "position $row 1"), tx.writes)
        assertEquals(listOf("no exercise in a routine $bench"), rejected { executor.removeRoutineExercise(tx, bench) }.reasons, "gone already")
        assertEquals(3, tx.writes.size)
    }

    @Test
    fun moveRoutineExerciseSwapsNeighboursAndStopsAtTheEnds() {
        val routineId = tx.addRoutine()
        val bench = tx.addRoutineExercise(routineId, "bench", 3)
        val squat = tx.addRoutineExercise(routineId, "squat", 3)
        val row = tx.addRoutineExercise(routineId, "row", 3)
        done { executor.moveRoutineExercise(tx, row, -1) }
        assertEquals(listOf("bench", "row", "squat"), routineOrder(routineId))
        assertEquals(listOf("position $row 1", "position $squat 2"), tx.writes, "only the two that changed")
        done { executor.moveRoutineExercise(tx, bench, -1) }
        done { executor.moveRoutineExercise(tx, squat, 1) }
        assertEquals(2, tx.writes.size, "past an end nothing moves")
        done { executor.moveRoutineExercise(tx, bench, 2) }
        assertEquals(listOf("row", "squat", "bench"), routineOrder(routineId))
        assertEquals(listOf("no exercise in a routine nope"), rejected { executor.moveRoutineExercise(tx, "nope", 1) }.reasons)
    }

    @Test
    fun targetSetsAndRepsAreWrittenOnlyWhenTheyChange() {
        val routineId = tx.addRoutine()
        val bench = tx.addRoutineExercise(routineId, "bench", 3, 8)
        done { executor.adjustTargetSets(tx, bench, 1) }
        assertEquals(4, runNow { tx.routineExercise(bench) }?.targetSets)
        done { executor.adjustTargetSets(tx, bench, 100) }
        assertEquals(20, runNow { tx.routineExercise(bench) }?.targetSets, "clamped to the range")
        done { executor.adjustTargetSets(tx, bench, 1) }
        done { executor.adjustTargetSets(tx, bench, -100) }
        done { executor.adjustTargetSets(tx, bench, -1) }
        assertEquals(listOf("target sets $bench 4", "target sets $bench 20", "target sets $bench 1"), tx.writes)

        tx.writes.clear()
        done { executor.setTargetReps(tx, bench, 12) }
        done { executor.setTargetReps(tx, bench, 12) }
        done { executor.setTargetReps(tx, bench, null) }
        done { executor.setTargetReps(tx, bench, null) }
        assertEquals(listOf("target reps $bench 12", "target reps $bench null"), tx.writes)
        assertEquals(listOf("no exercise in a routine nope"), rejected { executor.setTargetReps(tx, "nope", 8) }.reasons)
        assertEquals(listOf("no exercise in a routine nope"), rejected { executor.adjustTargetSets(tx, "nope", 1) }.reasons)
    }

    // --- programs ---

    @Test
    fun createProgramStartsInactiveAndEmptyWithATrimmedName() {
        val programId = done { executor.createProgram(tx, " PPL ", ProgramMode.ROTATING) }.ids.getValue(PROGRAM_ID)
        assertEquals(ProgramRow(programId, "PPL", ProgramMode.ROTATING, isActive = false), runNow { tx.program(programId) })
        assertEquals(emptyList<ProgramDayRow>(), runNow { tx.programDays(programId) })
        done { executor.renameProgram(tx, programId, " Push pull legs ") }
        assertEquals("Push pull legs", runNow { tx.program(programId) }?.name)
        assertEquals(listOf("no program nope"), rejected { executor.renameProgram(tx, "nope", "x") }.reasons)
    }

    @Test
    fun theModeSwitchesWithoutTouchingTheDays() {
        val routineId = tx.addRoutine()
        val programId = tx.addProgram(mode = ProgramMode.ROTATING)
        val day = tx.addProgramDay(routineId, programId, weekdays = 2)
        done { executor.setProgramMode(tx, programId, ProgramMode.FIXED) }
        done { executor.setProgramMode(tx, programId, ProgramMode.FIXED) }
        assertEquals(ProgramMode.FIXED, runNow { tx.program(programId) }?.mode)
        assertEquals(2, runNow { tx.programDay(day) }?.weekdays, "the day and its weekdays stay")
        assertEquals(listOf("mode $programId fixed"), tx.writes)
        assertEquals(listOf("no program nope"), rejected { executor.setProgramMode(tx, "nope", ProgramMode.FIXED) }.reasons)
    }

    @Test
    fun atMostOneProgramIsActive() {
        val one = tx.addProgram(active = true)
        val two = tx.addProgram(active = true)
        val three = tx.addProgram()
        done { executor.activateProgram(tx, three) }
        assertEquals(listOf(three), runNow { tx.activePrograms() }.map { it.id })
        assertEquals(listOf("active $one false", "active $two false", "active $three true"), tx.writes)
        done { executor.activateProgram(tx, three) }
        assertEquals(3, tx.writes.size, "already active: nothing written")
        done { executor.deactivateProgram(tx, one) }
        assertEquals(3, tx.writes.size, "already inactive: nothing written")
        done { executor.deactivateProgram(tx, three) }
        assertEquals(emptyList<ProgramRow>(), runNow { tx.activePrograms() })
        assertEquals(listOf("no program nope"), rejected { executor.activateProgram(tx, "nope") }.reasons)
        assertEquals(listOf("no program nope"), rejected { executor.deactivateProgram(tx, "nope") }.reasons)
    }

    @Test
    fun deleteProgramTakesItsDaysAndLeavesTheRoutines() {
        val routineId = tx.addRoutine()
        val programId = tx.addProgram()
        val day1 = tx.addProgramDay(routineId, programId)
        val day2 = tx.addProgramDay(routineId, programId)
        val other = tx.addProgram()
        val otherDay = tx.addProgramDay(routineId, other)
        done { executor.deleteProgram(tx, programId) }
        assertNull(runNow { tx.program(programId) })
        assertEquals(listOf(otherDay), runNow { tx.programDaysUsing(routineId) }.map { it.id })
        assertEquals(listOf("delete day $day1", "delete day $day2", "delete program $programId"), tx.writes)
        assertEquals(listOf("no program $programId"), rejected { executor.deleteProgram(tx, programId) }.reasons)
    }

    @Test
    fun addProgramDayNeedsAProgramAndARoutineAndGoesLast() {
        val routineId = tx.addRoutine()
        val programId = tx.addProgram()
        val first = done { executor.addProgramDay(tx, programId, routineId) }.ids.getValue(PROGRAM_DAY_ID)
        val second = done { executor.addProgramDay(tx, programId, routineId) }.ids.getValue(PROGRAM_DAY_ID)
        assertEquals(
            listOf(ProgramDayRow(first, programId, routineId, 0, 0), ProgramDayRow(second, programId, routineId, 1, 0)),
            runNow { tx.programDays(programId) },
        )
        assertEquals(listOf("no program nope"), rejected { executor.addProgramDay(tx, "nope", routineId) }.reasons)
        assertEquals(listOf("no routine nope"), rejected { executor.addProgramDay(tx, programId, "nope") }.reasons)
        assertEquals(2, tx.writes.size)
    }

    @Test
    fun daysAreRemovedMovedAndGivenWeekdays() {
        val routineId = tx.addRoutine()
        val programId = tx.addProgram()
        val day1 = tx.addProgramDay(routineId, programId)
        val day2 = tx.addProgramDay(routineId, programId)
        val day3 = tx.addProgramDay(routineId, programId)

        done { executor.removeProgramDay(tx, day1) }
        assertEquals(listOf(day2 to 0, day3 to 1), runNow { tx.programDays(programId) }.map { it.id to it.position })
        assertEquals(listOf("delete day $day1", "day position $day2 0", "day position $day3 1"), tx.writes)
        assertEquals(listOf("no program day $day1"), rejected { executor.removeProgramDay(tx, day1) }.reasons)

        tx.writes.clear()
        done { executor.moveProgramDay(tx, day3, -1) }
        assertEquals(listOf(day3, day2), runNow { tx.programDays(programId) }.map { it.id })
        done { executor.moveProgramDay(tx, day3, -1) }
        assertEquals(listOf("day position $day3 0", "day position $day2 1"), tx.writes, "past the top nothing moves")
        assertEquals(listOf("no program day nope"), rejected { executor.moveProgramDay(tx, "nope", 1) }.reasons)

        tx.writes.clear()
        done { executor.toggleProgramWeekday(tx, day2, 1) }
        done { executor.toggleProgramWeekday(tx, day2, 3) }
        assertEquals(1 or 4, runNow { tx.programDay(day2) }?.weekdays, "Monday and Wednesday")
        done { executor.toggleProgramWeekday(tx, day2, 1) }
        assertEquals(4, runNow { tx.programDay(day2) }?.weekdays)
        assertEquals(listOf("no program day nope"), rejected { executor.toggleProgramWeekday(tx, "nope", 1) }.reasons)
    }

    // --- exercises and select_current ---

    @Test
    fun setRestWritesOnlyAChangeAndRefusesAnUnknownExercise() {
        val bench = tx.addExerciseRow(restSeconds = null)
        assertEquals(mapOf(EXERCISE_ID to bench), done { executor.setRest(tx, bench, 120) }.ids)
        done { executor.setRest(tx, bench, 120) }
        done { executor.setRest(tx, bench, 0) }
        done { executor.setRest(tx, bench, null) }
        done { executor.setRest(tx, bench, null) }
        assertEquals(listOf("rest $bench 120", "rest $bench 0", "rest $bench null"), tx.writes)
        assertEquals(listOf("no exercise nope"), rejected { executor.setRest(tx, "nope", 60) }.reasons)
    }

    @Test
    fun createExerciseMakesACustomRowNamedAsTyped() {
        val id = done { executor.createExercise(tx, "  Zercher squat ", MuscleGroup.LEGS, Equipment.BARBELL) }.ids.getValue(EXERCISE_ID)
        assertEquals(
            ExerciseRow(id, "Zercher squat", nameKey = null, MuscleGroup.LEGS, Equipment.BARBELL, isCustom = true, restSeconds = null),
            runNow { tx.exercise(id) },
        )
        assertEquals(listOf("insert exercise row $id"), tx.writes)
    }

    @Test
    fun selectCurrentResolvesASetWithoutWritingAndRefusesATickedOne() {
        val (_, weId) = benchWorkout(3)
        val (first, second, third) = runNow { tx.sets(weId) }
        runNow { tx.tickSet(first.id, true) }
        tx.writes.clear()
        assertEquals(mapOf(SET_ID to second.id, WORKOUT_EXERCISE_ID to weId), done { executor.selectCurrent(tx, Command.SelectCurrent(setId = second.id)) }.ids)
        assertEquals(
            mapOf(SET_ID to second.id, WORKOUT_EXERCISE_ID to weId),
            done { executor.selectCurrent(tx, Command.SelectCurrent(workoutExerciseId = weId)) }.ids,
            "the first un-ticked set of the exercise",
        )
        assertEquals(listOf("set ${first.id} is already ticked"), rejected { executor.selectCurrent(tx, Command.SelectCurrent(setId = first.id)) }.reasons)
        assertEquals(listOf("no set nope"), rejected { executor.selectCurrent(tx, Command.SelectCurrent(setId = "nope")) }.reasons)
        assertEquals(listOf("no exercise in a workout nope"), rejected { executor.selectCurrent(tx, Command.SelectCurrent(workoutExerciseId = "nope")) }.reasons)
        runNow { tx.tickSet(second.id, true); tx.tickSet(third.id, true) }
        tx.writes.clear()
        assertEquals(listOf("no un-ticked set in $weId"), rejected { executor.selectCurrent(tx, Command.SelectCurrent(workoutExerciseId = weId)) }.reasons)
        assertEquals(emptyList<String>(), tx.writes, "select_current writes nothing")
    }
}
