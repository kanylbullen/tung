package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [CommandExecutor] adds around [CommandRules]: validation before any
 * transaction, name resolution that asks rather than guesses, the row an
 * extended-catalogue entry gets, and who started a workout. The rules
 * themselves are [CommandRulesTest].
 */
class CommandExecutorTest {

    private val store = MemoryCommandStore()
    private val tx get() = store.tx
    private val bench = ExerciseCandidate(
        catalogExerciseId("bench_press"), listOf("Bench press", "Bänkpress"), nameKey = "bench_press",
        equipment = Equipment.BARBELL, muscleGroup = MuscleGroup.CHEST,
    )
    private val zercher = ExerciseCandidate(
        extendedExerciseId("Zercher_Squats"), listOf("Zercher Squats"), extendedId = "Zercher_Squats",
        equipment = Equipment.BARBELL, muscleGroup = MuscleGroup.LEGS,
    )
    private val executor = CommandExecutor(store, CandidateResolver(listOf(bench, zercher)), startedBy = "phone")

    private fun run(command: Command): CommandResult = runBlocking { executor.execute(command) }

    private fun done(command: Command): CommandResult.Done {
        val result = run(command)
        assertTrue("$command: $result", result is CommandResult.Done)
        return result as CommandResult.Done
    }

    @Test
    fun anInvalidCommandIsRejectedBeforeAnyTransaction() {
        val result = run(Command.SetWeight("s1", -1.0))
        assertTrue("$result", result is CommandResult.Rejected)
        assertEquals(0, store.transactions)
    }

    @Test
    fun aWorkoutRecordsWhoStartedItAndThereIsOnlyOne() {
        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        assertEquals("phone", runBlocking { tx.workout(workoutId) }?.startedBy)
        assertEquals(workoutId, done(Command.StartWorkout()).ids[WORKOUT_ID])
        assertEquals("one transaction per command", 2, store.transactions)
    }

    @Test
    fun anUnknownNameAsksAndWritesNothing() {
        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        tx.writes.clear()
        val question = run(Command.AddExercise(workoutId, ExerciseRef.ByName("Flying squirrel press")))
        assertTrue("$question", question is CommandResult.NeedsAnswers)
        val plan = run(Command.ApplyPlan(WorkoutPlan(listOf(PlannedExercise(ExerciseRef.ByName("Flying squirrel press"), listOf(PlannedSet(80.0, 8)))))))
        assertTrue("$plan", plan is CommandResult.NeedsAnswers)
        val routine = run(Command.SaveRoutinePlan(RoutinePlan("Push", listOf(RoutinePlanExercise(ExerciseRef.ByName("Flying squirrel press"), 3)))))
        assertTrue("$routine", routine is CommandResult.NeedsAnswers)
        val inRoutine = run(Command.AddRoutineExercise("r1", ExerciseRef.ByName("Flying squirrel press")))
        assertTrue("$inRoutine", inRoutine is CommandResult.NeedsAnswers)
        assertEquals(emptyList<String>(), tx.writes)
    }

    @Test
    fun anExtendedEntryGetsItsRowOnlyWhenTheCommandRuns() {
        assertEquals(
            CommandResult.Rejected(listOf("no workout nope")),
            run(Command.AddExercise("nope", ExerciseRef.Extended("Zercher_Squats"))),
        )
        assertEquals(
            CommandResult.Rejected(listOf("no routine nope")),
            run(Command.AddRoutineExercise("nope", ExerciseRef.Extended("Zercher_Squats"))),
        )
        assertNull("a refusal makes no row", runBlocking { tx.exercise(zercher.id) })

        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        val added = done(Command.AddExercise(workoutId, ExerciseRef.Extended("Zercher_Squats"), sets = 1))
        assertEquals(zercher.id, added.ids[EXERCISE_ID])
        assertEquals(
            ExerciseRow(zercher.id, "Zercher Squats", nameKey = null, MuscleGroup.LEGS, Equipment.BARBELL, isCustom = false, restSeconds = null),
            runBlocking { tx.exercise(zercher.id) },
        )
        done(Command.AddExercise(workoutId, ExerciseRef.ByName("zercher squats"), sets = 1))
        assertEquals("the same row the second time", 1, tx.exerciseRows.count { it.id == zercher.id })
        assertEquals(listOf(zercher.id, zercher.id), runBlocking { tx.workoutExercises(workoutId) }.map { it.exerciseId })
    }

    @Test
    fun aPlanResolvesEveryExerciseThenRunsInOneTransaction() {
        val plan = WorkoutPlan(
            listOf(
                PlannedExercise(ExerciseRef.ByKey("bench_press"), listOf(PlannedSet(80.0, 8), PlannedSet(80.0, 8))),
                PlannedExercise(ExerciseRef.ByName("Zercher squats"), listOf(PlannedSet(60.0, 5))),
            ),
        )
        val workoutId = done(Command.ApplyPlan(plan)).ids.getValue(WORKOUT_ID)
        assertEquals("phone", runBlocking { tx.workout(workoutId) }?.startedBy)
        assertEquals(listOf(bench.id, zercher.id), runBlocking { tx.workoutExercises(workoutId) }.map { it.exerciseId })
        assertEquals(1, store.transactions)

        val routineId = done(Command.SaveRoutinePlan(RoutinePlan("Legs", listOf(RoutinePlanExercise(ExerciseRef.Extended("Zercher_Squats"), 3, 5)))))
            .ids.getValue(ROUTINE_ID)
        assertEquals(listOf(zercher.id), runBlocking { tx.routineExercises(routineId) }.map { it.exerciseId })
        assertEquals("Legs", runBlocking { tx.routine(routineId) }?.name)
    }

    @Test
    fun theOtherCommandsReachTheRules() {
        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        done(Command.AddExercise(workoutId, ExerciseRef.ById(bench.id), sets = 2))
        val weId = runBlocking { tx.workoutExercises(workoutId) }.single().id
        val (first, second) = runBlocking { tx.sets(weId) }
        done(Command.SetWeight(first.id, 80.0, applyToOpenSets = true))
        done(Command.SetReps(first.id, 8, applyToOpenSets = true))
        assertEquals(mapOf(SET_ID to first.id, CommandIds.WORKOUT_EXERCISE_ID to weId), done(Command.SelectCurrent(workoutExerciseId = weId)).ids)
        assertEquals(first.id, done(Command.LogSet(workoutId)).ids[SET_ID])
        assertEquals(second.id, done(Command.LogSet(workoutId)).ids[SET_ID])
        assertEquals(workoutId, done(Command.FinishWorkout(workoutId)).ids[WORKOUT_ID])
        assertTrue(runBlocking { tx.workout(workoutId) }?.endedAt != null)
        val routineId = done(Command.SaveRoutine(workoutId, "Push")).ids.getValue(ROUTINE_ID)
        assertEquals(2, runBlocking { tx.routineExercises(routineId) }.single().targetSets)
    }
}
