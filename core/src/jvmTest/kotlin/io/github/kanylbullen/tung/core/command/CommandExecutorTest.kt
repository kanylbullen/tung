package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_DAY_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the commands over an in-memory store: what the app's and the
 * server's command tests check against their databases, once, where the rule
 * lives (issue #87).
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

    // --- start_workout, start_routine ---

    @Test
    fun startWorkoutReturnsTheOneInProgressElseStartsOneNow() {
        tx.now = 5_000L
        val first = done { executor.startWorkout(tx, "phone") }.ids.getValue(WORKOUT_ID)
        val row = runBlocking { tx.workout(first) }
        assertEquals(5_000L to "phone", row?.startedAt to row?.startedBy)
        assertEquals("the same one again", first, done { executor.startWorkout(tx, "watch") }.ids.getValue(WORKOUT_ID))
        done { executor.finishWorkout(tx, first) }
        assertTrue("a new one once it is over", done { executor.startWorkout(tx, "phone") }.ids.getValue(WORKOUT_ID) != first)
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
        val workout = runBlocking { tx.workout(workoutId) }
        assertEquals(Triple(routineId, day, "phone"), Triple(workout?.routineId, workout?.programDayId, workout?.startedBy))
        val (bench, squat) = runBlocking { tx.workoutExercises(workoutId) }
        assertEquals("the routine's two, not last session's three", listOf(80.0 to 8, 85.0 to 6), values(bench.id))
        assertEquals("no history: 0 kg at the target reps", listOf(0.0 to 5, 0.0 to 5), values(squat.id))
        assertEquals(9_000L, runBlocking { tx.routine(routineId) }?.lastUsedAt)
        assertEquals("the one in progress, not a second", workoutId, done { executor.startRoutine(tx, routineId, null, "phone") }.ids.getValue(WORKOUT_ID))
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
        val (first, second, third) = runBlocking { tx.sets(weId) }
        val empty = tx.addExercise(workoutId, "squat")
        tx.addSet(empty, 100.0, 5)
        val noReps = tx.addExercise(workoutId, "row")
        tx.addSet(noReps, 40.0, 0, completed = true)
        done { executor.tickSet(tx, first.id, true) }
        done { executor.tickSet(tx, third.id, true) }
        tx.now = 7_000L
        assertEquals(workoutId, done { executor.finishWorkout(tx, workoutId) }.ids[WORKOUT_ID])
        assertEquals("the un-ticked second set is gone", listOf(first.id, third.id), runBlocking { tx.sets(weId) }.map { it.id })
        assertEquals("exercises without a logged set are gone", listOf(weId), runBlocking { tx.workoutExercises(workoutId) }.map { it.id })
        assertEquals(7_000L, runBlocking { tx.workout(workoutId) }?.endedAt)
        assertEquals("finishing again changes nothing", workoutId, done { executor.finishWorkout(tx, workoutId) }.ids[WORKOUT_ID])
        assertEquals(7_000L, runBlocking { tx.workout(workoutId) }?.endedAt)
    }

    @Test
    fun finishDiscardsAWorkoutWithNothingLogged() {
        val (workoutId, _) = benchWorkout(2)
        assertNull("no id: the workout is gone", done { executor.finishWorkout(tx, workoutId) }.ids[WORKOUT_ID])
        assertNull(runBlocking { tx.workout(workoutId) })
        assertEquals(emptyList<WorkoutExerciseRow>(), tx.exercises)
        assertEquals(emptyList<SetRow>(), tx.allSets)
    }

    @Test
    fun finishEndsAtTheGivenTimeNeverBeforeTheStart() {
        val workoutId = tx.addWorkout(startedAt = 10_000L)
        val weId = tx.addExercise(workoutId, "bench")
        tx.addSet(weId, 80.0, 8, completed = true)
        assertEquals(listOf("ended_at is before started_at"), rejected { executor.finishWorkout(tx, workoutId, endedAt = 9_000L) }.reasons)
        tx.now = 8_000L
        done { executor.finishWorkout(tx, workoutId) }
        assertEquals("a clock behind the start ends it at the start", 10_000L, runBlocking { tx.workout(workoutId) }?.endedAt)
        assertEquals(listOf("no workout nope"), rejected { executor.finishWorkout(tx, "nope") }.reasons)
    }

    @Test
    fun deleteWorkoutTakesItsExercisesAndSetsWithIt() {
        val (workoutId, _) = benchWorkout(2)
        val other = benchWorkout(1).first
        done { executor.deleteWorkout(tx, workoutId) }
        assertNull(runBlocking { tx.workout(workoutId) })
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
        val (bench, squat) = runBlocking { tx.workoutExercises(workoutId) }
        assertEquals("a missing weight comes from last session, then the set before", listOf(80.0 to 10, 80.0 to 10), values(bench.id))
        assertEquals(listOf(100.0 to 5), values(squat.id))
        assertEquals("phone", runBlocking { tx.workout(workoutId) }?.startedBy)
        assertEquals("the second plan joins the workout in progress", workoutId, done { executor.applyPlan(tx, listOf("row" to listOf(PlannedSet(40.0, 12))), "phone") }.ids.getValue(WORKOUT_ID))
        assertEquals(listOf(0, 1, 2), runBlocking { tx.workoutExercises(workoutId) }.map { it.position })
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
        assertEquals(listOf(70.0 to 8, 82.5 to 8, 82.5 to 6), values(weId))
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

    // --- routines ---

    private fun routineOrder(routineId: String) = runBlocking { tx.routineExercises(routineId) }.map { it.exerciseId }

    private fun routinePositions(routineId: String) = runBlocking { tx.routineExercises(routineId) }.map { it.position }

    @Test
    fun createRoutineMakesAnEmptyRoutineWithATrimmedName() {
        val routineId = done { executor.createRoutine(tx, "  Legs ") }.ids.getValue(ROUTINE_ID)
        assertEquals(RoutineRow(routineId, "Legs", lastUsedAt = null), runBlocking { tx.routine(routineId) })
        assertEquals(emptyList<RoutineExerciseRow>(), runBlocking { tx.routineExercises(routineId) })
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
        assertEquals("Push", runBlocking { tx.routine(routineId) }?.name)
        assertEquals(
            "one target set per logged set, the most common reps (the first of a tie); squat had none logged",
            listOf(Triple("bench", 3, 8), Triple("row", 2, 10)),
            runBlocking { tx.routineExercises(routineId) }.map { Triple(it.exerciseId, it.targetSets, it.targetReps) },
        )
        assertEquals(listOf(0, 1), routinePositions(routineId))

        val again = done { executor.saveRoutine(tx, workoutId, "Push") }.ids.getValue(ROUTINE_ID)
        assertTrue("saving twice makes two routines", again != routineId)
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
            runBlocking { tx.routineExercises("r-import") },
        )
        val own = done { executor.saveRoutinePlan(tx, "Legs", plan) }.ids.getValue(ROUTINE_ID)
        assertTrue("new ids otherwise", own.startsWith("id"))
        assertEquals(listOf("bench", "squat"), routineOrder(own))
    }

    @Test
    fun renameRoutineTrimsAndRefusesWhatIsGone() {
        val routineId = tx.addRoutine(name = "Push")
        done { executor.renameRoutine(tx, routineId, " Push day ") }
        assertEquals("Push day", runBlocking { tx.routine(routineId) }?.name)
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
        assertNull(runBlocking { tx.routine(routineId) })
        assertEquals(listOf(day2), runBlocking { tx.programDays("p1") }.map { it.id })
        assertEquals("the other day keeps its position", 1, runBlocking { tx.programDay(day2) }?.position)
        assertEquals("the other routine stays", "Pull", runBlocking { tx.routine(other) }?.name)
        assertEquals(
            listOf("delete day $day1", "delete day $day3", "delete routine exercise $bench", "delete routine exercise $squat", "delete routine $routineId"),
            tx.writes,
        )
        assertEquals(listOf("no routine $routineId"), rejected { executor.deleteRoutine(tx, routineId) }.reasons)
    }

    @Test
    fun addRoutineExerciseAppendsLastWithItsTargets() {
        val routineId = tx.addRoutine()
        tx.addRoutineExercise(routineId, "bench", 3, 8)
        val result = done { executor.addRoutineExercise(tx, routineId, "squat", targetSets = 5, targetReps = 5) }
        val added = result.ids.getValue(ROUTINE_EXERCISE_ID)
        assertEquals("squat", result.ids[EXERCISE_ID])
        assertEquals(RoutineExerciseRow(added, routineId, "squat", 1, 5, 5), runBlocking { tx.routineExercise(added) })
        val clamped = done { executor.addRoutineExercise(tx, routineId, "row", targetSets = 99, targetReps = null) }.ids.getValue(ROUTINE_EXERCISE_ID)
        assertEquals(20, runBlocking { tx.routineExercise(clamped) }?.targetSets)
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
        assertEquals("gone already", listOf("no exercise in a routine $bench"), rejected { executor.removeRoutineExercise(tx, bench) }.reasons)
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
        assertEquals("only the two that changed", listOf("position $row 1", "position $squat 2"), tx.writes)
        done { executor.moveRoutineExercise(tx, bench, -1) }
        done { executor.moveRoutineExercise(tx, squat, 1) }
        assertEquals("past an end nothing moves", 2, tx.writes.size)
        done { executor.moveRoutineExercise(tx, bench, 2) }
        assertEquals(listOf("row", "squat", "bench"), routineOrder(routineId))
        assertEquals(listOf("no exercise in a routine nope"), rejected { executor.moveRoutineExercise(tx, "nope", 1) }.reasons)
    }

    @Test
    fun targetSetsAndRepsAreWrittenOnlyWhenTheyChange() {
        val routineId = tx.addRoutine()
        val bench = tx.addRoutineExercise(routineId, "bench", 3, 8)
        done { executor.adjustTargetSets(tx, bench, 1) }
        assertEquals(4, runBlocking { tx.routineExercise(bench) }?.targetSets)
        done { executor.adjustTargetSets(tx, bench, 100) }
        assertEquals("clamped to the range", 20, runBlocking { tx.routineExercise(bench) }?.targetSets)
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
        assertEquals(ProgramRow(programId, "PPL", ProgramMode.ROTATING, isActive = false), runBlocking { tx.program(programId) })
        assertEquals(emptyList<ProgramDayRow>(), runBlocking { tx.programDays(programId) })
        done { executor.renameProgram(tx, programId, " Push pull legs ") }
        assertEquals("Push pull legs", runBlocking { tx.program(programId) }?.name)
        assertEquals(listOf("no program nope"), rejected { executor.renameProgram(tx, "nope", "x") }.reasons)
    }

    @Test
    fun theModeSwitchesWithoutTouchingTheDays() {
        val routineId = tx.addRoutine()
        val programId = tx.addProgram(mode = ProgramMode.ROTATING)
        val day = tx.addProgramDay(routineId, programId, weekdays = 2)
        done { executor.setProgramMode(tx, programId, ProgramMode.FIXED) }
        done { executor.setProgramMode(tx, programId, ProgramMode.FIXED) }
        assertEquals(ProgramMode.FIXED, runBlocking { tx.program(programId) }?.mode)
        assertEquals("the day and its weekdays stay", 2, runBlocking { tx.programDay(day) }?.weekdays)
        assertEquals(listOf("mode $programId fixed"), tx.writes)
        assertEquals(listOf("no program nope"), rejected { executor.setProgramMode(tx, "nope", ProgramMode.FIXED) }.reasons)
    }

    @Test
    fun atMostOneProgramIsActive() {
        val one = tx.addProgram(active = true)
        val two = tx.addProgram(active = true)
        val three = tx.addProgram()
        done { executor.activateProgram(tx, three) }
        assertEquals(listOf(three), runBlocking { tx.activePrograms() }.map { it.id })
        assertEquals(listOf("active $one false", "active $two false", "active $three true"), tx.writes)
        done { executor.activateProgram(tx, three) }
        assertEquals("already active: nothing written", 3, tx.writes.size)
        done { executor.deactivateProgram(tx, one) }
        assertEquals("already inactive: nothing written", 3, tx.writes.size)
        done { executor.deactivateProgram(tx, three) }
        assertEquals(emptyList<ProgramRow>(), runBlocking { tx.activePrograms() })
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
        assertNull(runBlocking { tx.program(programId) })
        assertEquals(listOf(otherDay), runBlocking { tx.programDaysUsing(routineId) }.map { it.id })
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
            runBlocking { tx.programDays(programId) },
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
        assertEquals(listOf(day2 to 0, day3 to 1), runBlocking { tx.programDays(programId) }.map { it.id to it.position })
        assertEquals(listOf("delete day $day1", "day position $day2 0", "day position $day3 1"), tx.writes)
        assertEquals(listOf("no program day $day1"), rejected { executor.removeProgramDay(tx, day1) }.reasons)

        tx.writes.clear()
        done { executor.moveProgramDay(tx, day3, -1) }
        assertEquals(listOf(day3, day2), runBlocking { tx.programDays(programId) }.map { it.id })
        done { executor.moveProgramDay(tx, day3, -1) }
        assertEquals("past the top nothing moves", listOf("day position $day3 0", "day position $day2 1"), tx.writes)
        assertEquals(listOf("no program day nope"), rejected { executor.moveProgramDay(tx, "nope", 1) }.reasons)

        tx.writes.clear()
        done { executor.toggleProgramWeekday(tx, day2, 1) }
        done { executor.toggleProgramWeekday(tx, day2, 3) }
        assertEquals("Monday and Wednesday", 1 or 4, runBlocking { tx.programDay(day2) }?.weekdays)
        done { executor.toggleProgramWeekday(tx, day2, 1) }
        assertEquals(4, runBlocking { tx.programDay(day2) }?.weekdays)
        assertEquals(listOf("no program day nope"), rejected { executor.toggleProgramWeekday(tx, "nope", 1) }.reasons)
    }
}
