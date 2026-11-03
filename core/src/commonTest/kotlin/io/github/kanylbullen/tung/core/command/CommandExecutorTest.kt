package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.runNow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun run(command: Command): CommandResult = runNow { executor.execute(command) }

    private fun done(command: Command): CommandResult.Done {
        val result = run(command)
        assertTrue(result is CommandResult.Done, "$command: $result")
        return result as CommandResult.Done
    }

    @Test
    fun anInvalidCommandIsRejectedBeforeAnyTransaction() {
        val result = run(Command.SetWeight("s1", -1.0))
        assertTrue(result is CommandResult.Rejected, "$result")
        assertEquals(0, store.transactions)
    }

    @Test
    fun aWorkoutRecordsWhoStartedItAndThereIsOnlyOne() {
        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        assertEquals("phone", runNow { tx.workout(workoutId) }?.startedBy)
        assertEquals(workoutId, done(Command.StartWorkout()).ids[WORKOUT_ID])
        assertEquals(2, store.transactions, "one transaction per command")
    }

    @Test
    fun anUnknownNameAsksAndWritesNothing() {
        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        tx.writes.clear()
        val question = run(Command.AddExercise(workoutId, ExerciseRef.ByName("Flying squirrel press")))
        assertTrue(question is CommandResult.NeedsAnswers, "$question")
        val plan = run(Command.ApplyPlan(WorkoutPlan(listOf(PlannedExercise(ExerciseRef.ByName("Flying squirrel press"), listOf(PlannedSet(80.0, 8)))))))
        assertTrue(plan is CommandResult.NeedsAnswers, "$plan")
        val routine = run(Command.SaveRoutinePlan(RoutinePlan("Push", listOf(RoutinePlanExercise(ExerciseRef.ByName("Flying squirrel press"), 3)))))
        assertTrue(routine is CommandResult.NeedsAnswers, "$routine")
        val inRoutine = run(Command.AddRoutineExercise("r1", ExerciseRef.ByName("Flying squirrel press")))
        assertTrue(inRoutine is CommandResult.NeedsAnswers, "$inRoutine")
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
        assertNull(runNow { tx.exercise(zercher.id) }, "a refusal makes no row")

        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        val added = done(Command.AddExercise(workoutId, ExerciseRef.Extended("Zercher_Squats"), sets = 1))
        assertEquals(zercher.id, added.ids[EXERCISE_ID])
        assertEquals(
            ExerciseRow(zercher.id, "Zercher Squats", nameKey = null, MuscleGroup.LEGS, Equipment.BARBELL, isCustom = false, restSeconds = null),
            runNow { tx.exercise(zercher.id) },
        )
        done(Command.AddExercise(workoutId, ExerciseRef.ByName("zercher squats"), sets = 1))
        assertEquals(1, tx.exerciseRows.count { it.id == zercher.id }, "the same row the second time")
        assertEquals(listOf(zercher.id, zercher.id), runNow { tx.workoutExercises(workoutId) }.map { it.exerciseId })
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
        assertEquals("phone", runNow { tx.workout(workoutId) }?.startedBy)
        assertEquals(listOf(bench.id, zercher.id), runNow { tx.workoutExercises(workoutId) }.map { it.exerciseId })
        assertEquals(1, store.transactions)

        val routineId = done(Command.SaveRoutinePlan(RoutinePlan("Legs", listOf(RoutinePlanExercise(ExerciseRef.Extended("Zercher_Squats"), 3, 5)))))
            .ids.getValue(ROUTINE_ID)
        assertEquals(listOf(zercher.id), runNow { tx.routineExercises(routineId) }.map { it.exerciseId })
        assertEquals("Legs", runNow { tx.routine(routineId) }?.name)
    }

    @Test
    fun aWorkoutLoggedAfterTheFactCarriesItsTimesThrough() {
        tx.now = 2_000_000_000_000L
        val start = 1_999_000_000_000L
        val workoutId = done(Command.StartWorkout(startedAt = start)).ids.getValue(WORKOUT_ID)
        assertEquals(start, runNow { tx.workout(workoutId) }?.startedAt)
        done(Command.AddExercise(workoutId, ExerciseRef.ById(bench.id), sets = 1))
        val set = runNow { tx.sets(runNow { tx.workoutExercises(workoutId) }.single().id) }.single()
        done(Command.SetReps(set.id, 8))
        done(Command.LogSet(workoutId, set.id))
        done(Command.FinishWorkout(workoutId, endedAt = start + 5_400_000L))
        assertEquals(start + 5_400_000L, runNow { tx.workout(workoutId) }?.endedAt)
        done(Command.SetWorkoutTime(workoutId, start - 86_400_000L, start - 86_400_000L + 3_600_000L))
        val row = runNow { tx.workout(workoutId) }
        assertEquals(start - 86_400_000L to start - 82_800_000L, row?.startedAt to row?.endedAt)
        val before2000 = run(Command.SetWorkoutTime(workoutId, 1L, 2L))
        assertTrue(before2000 is CommandResult.Rejected, "$before2000")
    }

    @Test
    fun theOtherCommandsReachTheRules() {
        val workoutId = done(Command.StartWorkout()).ids.getValue(WORKOUT_ID)
        done(Command.AddExercise(workoutId, ExerciseRef.ById(bench.id), sets = 2))
        val weId = runNow { tx.workoutExercises(workoutId) }.single().id
        val (first, second) = runNow { tx.sets(weId) }
        done(Command.SetWeight(first.id, 80.0, applyToOpenSets = true))
        done(Command.SetReps(first.id, 8, applyToOpenSets = true))
        assertEquals(mapOf(SET_ID to first.id, CommandIds.WORKOUT_EXERCISE_ID to weId), done(Command.SelectCurrent(workoutExerciseId = weId)).ids)
        assertEquals(first.id, done(Command.LogSet(workoutId)).ids[SET_ID])
        assertEquals(second.id, done(Command.LogSet(workoutId)).ids[SET_ID])
        assertEquals(workoutId, done(Command.FinishWorkout(workoutId)).ids[WORKOUT_ID])
        assertTrue(runNow { tx.workout(workoutId) }?.endedAt != null)
        val routineId = done(Command.SaveRoutine(workoutId, "Push")).ids.getValue(ROUTINE_ID)
        assertEquals(2, runNow { tx.routineExercises(routineId) }.single().targetSets)
    }
}
