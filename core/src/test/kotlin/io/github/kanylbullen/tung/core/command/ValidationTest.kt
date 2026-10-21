package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command.AddRoutineExercise
import io.github.kanylbullen.tung.core.command.Command.ApplyPlan
import io.github.kanylbullen.tung.core.command.Command.CreateProgram
import io.github.kanylbullen.tung.core.command.Command.CreateRoutine
import io.github.kanylbullen.tung.core.command.Command.LogSet
import io.github.kanylbullen.tung.core.command.Command.RemoveRoutineExercise
import io.github.kanylbullen.tung.core.command.Command.SaveRoutine
import io.github.kanylbullen.tung.core.command.Command.SaveRoutinePlan
import io.github.kanylbullen.tung.core.command.Command.SelectCurrent
import io.github.kanylbullen.tung.core.command.Command.SetProgramMode
import io.github.kanylbullen.tung.core.command.Command.SetReps
import io.github.kanylbullen.tung.core.command.Command.SetRest
import io.github.kanylbullen.tung.core.command.Command.SetTargetReps
import io.github.kanylbullen.tung.core.command.Command.SetWeight
import io.github.kanylbullen.tung.core.command.Command.StartWorkout
import io.github.kanylbullen.tung.core.command.Command.ToggleProgramWeekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationTest {

    private fun valid(command: Command) = assertEquals(command.toString(), Validation.Valid, validate(command))

    private fun invalid(command: Command): List<String> {
        val result = validate(command)
        assertTrue("$command should be invalid", result is Validation.Invalid)
        return (result as Validation.Invalid).reasons
    }

    private val bench = ExerciseRef.ByKey("bench_press")

    @Test
    fun weightIsZeroTo999() {
        valid(SetWeight("s", 0.0))
        valid(SetWeight("s", 82.5))
        valid(SetWeight("s", 999.0))
        invalid(SetWeight("s", -0.01))
        invalid(SetWeight("s", 999.01))
        invalid(SetWeight("s", Double.NaN))
        invalid(SetWeight("s", Double.POSITIVE_INFINITY))
    }

    @Test
    fun repsAreZeroTo999() {
        valid(SetReps("s", 0))
        valid(SetReps("s", 999))
        invalid(SetReps("s", -1))
        invalid(SetReps("s", 1000))
    }

    @Test
    fun aPlannedExerciseHasOneToFiftySets() {
        fun plan(sets: Int) = ApplyPlan(WorkoutPlan(listOf(PlannedExercise.uniform(bench, sets, reps = 10, weightKg = 60.0))))
        invalid(plan(0))
        valid(plan(1))
        valid(plan(50))
        invalid(plan(51))
    }

    @Test
    fun planNumbersAreCheckedPerSetAndMissingOnesAreFine() {
        valid(ApplyPlan(WorkoutPlan(listOf(PlannedExercise(bench, listOf(PlannedSet(null, null)))))))
        val reasons = invalid(
            ApplyPlan(WorkoutPlan(listOf(PlannedExercise(bench, listOf(PlannedSet(60.0, 10), PlannedSet(1000.0, -1)))))),
        )
        assertEquals("both numbers of set 2 are reported", 2, reasons.size)
        assertTrue(reasons.all { it.startsWith("exercise 1, set 2") })
    }

    @Test
    fun anEmptyPlanOrABlankNameIsInvalid() {
        invalid(ApplyPlan(WorkoutPlan(emptyList())))
        invalid(ApplyPlan(WorkoutPlan(listOf(PlannedExercise.uniform(ExerciseRef.ByName("  "), 3, 10, 50.0)))))
        invalid(SaveRoutine("w", ""))
        invalid(SaveRoutine("w", "   "))
        valid(SaveRoutine("w", "Push day"))
        invalid(CreateProgram(" ", ProgramMode.FIXED))
    }

    @Test
    fun aNewRoutineNeedsOnlyANameAndMayBeEmpty() {
        valid(CreateRoutine("Legs"))
        assertEquals(listOf("name must not be blank"), invalid(CreateRoutine("")))
        assertEquals(listOf("name must not be blank"), invalid(CreateRoutine("   ")))
    }

    @Test
    fun aRoutinePlanFollowsTheRoutineTargetRange() {
        fun plan(sets: Int) = SaveRoutinePlan(RoutinePlan("Legs", listOf(RoutinePlanExercise(bench, sets, 8))))
        invalid(plan(0))
        valid(plan(1))
        valid(plan(20))
        invalid(plan(21))
        invalid(SaveRoutinePlan(RoutinePlan("Legs", emptyList())))
        invalid(SaveRoutinePlan(RoutinePlan("", listOf(RoutinePlanExercise(bench, 3)))))
    }

    @Test
    fun otherRanges() {
        valid(SetRest("e", null))
        valid(SetRest("e", 0))
        valid(SetRest("e", MAX_REST_SECONDS))
        invalid(SetRest("e", -1))
        invalid(SetRest("e", MAX_REST_SECONDS + 1))
        valid(ToggleProgramWeekday("d", 1))
        valid(ToggleProgramWeekday("d", 7))
        invalid(ToggleProgramWeekday("d", 0))
        invalid(ToggleProgramWeekday("d", 8))
    }

    @Test
    fun idsAndCombinations() {
        valid(StartWorkout())
        valid(StartWorkout(routineId = "r", programDayId = "d"))
        invalid(StartWorkout(programDayId = "d"))
        valid(LogSet("w"))
        invalid(LogSet("w", setId = ""))
        invalid(SelectCurrent())
        valid(SelectCurrent(workoutExerciseId = "we"))
        invalid(SetWeight(" ", 50.0))
    }

    @Test
    fun anAddedRoutineExerciseFollowsTheRoutineRanges() {
        valid(AddRoutineExercise("r", bench))
        assertEquals(DEFAULT_ROUTINE_TARGET_SETS, AddRoutineExercise("r", bench).targetSets)
        valid(AddRoutineExercise("r", bench, targetSets = 1, targetReps = 1))
        valid(AddRoutineExercise("r", bench, targetSets = 20, targetReps = 999))
        invalid(AddRoutineExercise("r", bench, targetSets = 0))
        invalid(AddRoutineExercise("r", bench, targetSets = 21))
        invalid(AddRoutineExercise("r", bench, targetReps = 0))
        invalid(AddRoutineExercise("r", bench, targetReps = 1000))
        invalid(AddRoutineExercise(" ", bench))
        invalid(AddRoutineExercise("r", ExerciseRef.ByName(" ")))
        assertEquals(4, invalid(AddRoutineExercise("", ExerciseRef.ById(""), targetSets = 0, targetReps = -1)).size)
    }

    @Test
    fun targetRepsAreOneTo999OrNone() {
        valid(SetTargetReps("re", null))
        valid(SetTargetReps("re", 1))
        valid(SetTargetReps("re", 999))
        invalid(SetTargetReps("re", 0))
        invalid(SetTargetReps("re", 1000))
        invalid(SetTargetReps("", 8))
    }

    @Test
    fun removingARoutineExerciseAndSettingAModeNeedAnId() {
        valid(RemoveRoutineExercise("re"))
        invalid(RemoveRoutineExercise(" "))
        valid(SetProgramMode("p", ProgramMode.FIXED))
        valid(SetProgramMode("p", ProgramMode.ROTATING))
        invalid(SetProgramMode("", ProgramMode.ROTATING))
        // A bad mode never reaches validate: the enum cannot hold one, and the wire
        // decoder refuses an unknown key (CommandWireTest.routineEditingShapesAreChecked).
    }

    @Test
    fun everyProblemIsReported() {
        val reasons = invalid(SaveRoutinePlan(RoutinePlan(" ", listOf(RoutinePlanExercise(ExerciseRef.ByKey(""), 0, -1)))))
        assertEquals(4, reasons.size)
    }
}
