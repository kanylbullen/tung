package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasText
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Home lists only the most recently used routines, a tap opens one, and "All" opens the Routines tab with every one. */
class HomeRoutinesFlowTest : FlowTest() {

    /** shown. */
    private val shown = 3

    private val names = listOf("Alpha day", "Bravo day", "Charlie day", "Delta day", "Echo day")

    @Test
    fun homeShowsTheFirstRoutinesAndAllShowsTheRest() {
        runBlocking {
            names.forEach { name ->
                app.container.commands.execute(
                    Command.SaveRoutinePlan(
                        RoutinePlan(name, listOf(RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 3, targetReps = 8))),
                    ),
                )
            }
        }

        // None has been used, so they sort by name: the first three are on Home, the rest are not.
        names.take(shown).forEach { waitForText(it) }
        names.drop(shown).forEach { waitGone(hasText(it)) }
        screenshot("1-home")

        // A tap on a routine on Home opens it, showing its exercises before Start.
        clickText(names.first())
        waitForText(str(R.string.ex_bench_press))
        screenshot("2-detail")
        clickDescription(str(R.string.action_back))

        clickText(str(R.string.routines_all))
        names.forEach { waitForText(it) }
        screenshot("3-tab")
    }
}
