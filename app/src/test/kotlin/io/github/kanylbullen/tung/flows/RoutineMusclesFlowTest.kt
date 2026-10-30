package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.command.RoutinePlan
import io.github.kanylbullen.tung.core.command.RoutinePlanExercise
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * A routine shows the muscles it works, weighted by target sets: a small body
 * on its row on Home and the Routines tab, and the body with a line of its
 * muscles on its page. Four sets of back squat and three of bench press work
 * quads and glutes (4 each) and chest (3) most, so the rows' bodies are
 * described "Muscles: quads, glutes, chest".
 */
class RoutineMusclesFlowTest : FlowTest() {

    @Test
    fun routineRowsAndPageShowTheMusclesTheyWork() {
        runBlocking {
            app.container.commands.execute(
                Command.SaveRoutinePlan(
                    RoutinePlan(
                        "Push and legs",
                        listOf(
                            RoutinePlanExercise(ExerciseRef.ByKey("bench_press"), targetSets = 3, targetReps = 8),
                            RoutinePlanExercise(ExerciseRef.ByKey("back_squat"), targetSets = 4, targetReps = 5),
                        ),
                    ),
                ),
            )
        }
        val sep = str(R.string.list_separator)
        val top = listOf(R.string.muscle_name_quads, R.string.muscle_name_glutes, R.string.muscle_name_chest).joinToString(sep) { str(it) }
        val rowDescription = str(R.string.routine_muscles_description, top)

        // Home's routine group: the small body, described with the most worked muscles first.
        waitForText("Push and legs")
        assertShown(hasContentDescription(rowDescription))
        screenshot("1-home-routine-body")

        // The tab, not Home's "Routines" heading of the same text.
        compose.onNode(hasText(str(R.string.nav_routines)) and isSelectable()).performClick()
        waitForText(str(R.string.routine_new))
        assertShown(hasContentDescription(rowDescription))
        screenshot("2-routines-tab-bodies")

        clickText("Push and legs")
        waitForText(str(R.string.routine_add_exercise))
        assertShown(hasContentDescription(str(R.string.routine_muscles_description, ""), substring = true))
        screenshot("3-routine-page-body")
    }
}
