package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * A routine name drawn as "<group> · <day>" stays one string for a screen
 * reader and a test, however narrow the row: an imported "Your Adaptive Plan
 * · Full Body Workout" is found whole on its Home row, on the row of the
 * workout started from it, and on the Routines tab, where a tap opens it.
 * How the line is cut is [io.github.kanylbullen.tung.ui.common.routineNameWidths]'s,
 * tested on its own; the screenshots show the day kept whole at 411 dp.
 */
class RoutineNameFlowTest : FlowTest() {

    @Test
    fun aLongNameIsOneStringOnEveryRow() {
        val name = "Your Adaptive Plan · Full Body Workout"
        runBlocking {
            val commands = app.container.commands
            val saved = commands.execute(
                Command.SaveRoutinePlan(RoutinePlan(name, listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 1, targetReps = 8)))),
            )
            val routineId = checkNotNull((saved as CommandResult.Done).ids[CommandIds.ROUTINE_ID])
            val started = commands.execute(Command.StartRoutine(routineId))
            val workoutId = checkNotNull((started as CommandResult.Done).ids[CommandIds.WORKOUT_ID])
            commands.execute(Command.LogSet(workoutId))
            commands.execute(Command.FinishWorkout(workoutId))
        }

        // Home: the routine's row and the finished workout's row each carry the whole name.
        waitUntil { compose.onAllNodes(hasText(name)).fetchSemanticsNodes().size == 2 }
        screenshot("1-home")

        compose.onNode(hasText(str(R.string.nav_routines)) and isSelectable()).performClick()
        waitForText(str(R.string.routine_new))
        screenshot("2-routines-tab")
        clickText(name)
        waitForText(str(R.string.routine_add_exercise))
        screenshot("3-routine")
    }
}
