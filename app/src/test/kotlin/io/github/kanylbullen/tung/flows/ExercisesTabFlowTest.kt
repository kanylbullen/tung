package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.ui.exercises.EXERCISES_LIST_TAG
import io.github.kanylbullen.tung.ui.exercises.EXERCISE_PAGE_TAG
import io.github.kanylbullen.tung.ui.exercises.groupChipTag
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Exercises tab (issue #54): the whole catalogue by muscle group with a
 * chip per group, a row opens the exercise page, and the page adds the
 * exercise to a routine or to the workout in progress.
 */
class ExercisesTabFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)

    private fun heading(text: String) = hasText(text) and isHeading()

    private fun openTab() {
        clickText(str(R.string.nav_exercises))
        waitFor(hasTestTag(EXERCISES_LIST_TAG))
    }

    private fun searchTab(query: String) {
        compose.onNode(hasSetTextAction()).performTextReplacement(query)
    }

    /** Opens the page of the row called [name], after searching the tab for [query]. */
    private fun openPage(query: String, name: String) {
        openTab()
        searchTab(query)
        clickText(name)
        waitFor(hasTestTag(EXERCISE_PAGE_TAG))
        waitForText(str(R.string.exercise_add_to_routine))
    }

    /**
     * The tab lists the core exercises and the extended catalogue together
     * under group headings. The Back chip leaves only the back section, with
     * an extended row ("Alternating Kettlebell Row", shown by its English name)
     * next to Barbell row; "All" brings the other groups back.
     */
    @Test
    fun theTabShowsTheWholeCatalogueByGroupAndAChipFilters() {
        openTab()
        waitForText(benchPress)
        assertShown(heading(str(R.string.muscle_chest)))
        screenshot("1-tab")

        compose.onNode(hasTestTag(groupChipTag(MuscleGroup.BACK))).performClick()
        waitGone(heading(str(R.string.muscle_chest)))
        assertShown(heading(str(R.string.muscle_back)))
        waitForText("Alternating Kettlebell Row")
        val list = compose.onNode(hasTestTag(EXERCISES_LIST_TAG))
        list.performScrollToNode(hasText(str(R.string.ex_barbell_row)))
        assertShown(hasText(str(R.string.ex_barbell_row)))
        compose.onNode(hasText(benchPress)).assertDoesNotExist()
        screenshot("2-back-chip")

        compose.onNode(hasTestTag(groupChipTag(null))).performClick()
        waitForText(benchPress)
        assertShown(heading(str(R.string.muscle_chest)))

        // The search covers the extended catalogue too, still grouped.
        searchTab("zercher")
        waitForText("Zercher Squats")
        assertShown(heading(str(R.string.muscle_legs)))
        compose.onNode(hasText(benchPress)).assertDoesNotExist()
        screenshot("3-search-extended")
    }

    /**
     * Bench press, logged once at 100 kg × 5: its page names it, draws the
     * muscle map (described for a screen reader), shows the history and the
     * numbered instructions, and offers no "Add to workout" without a workout
     * in progress.
     */
    @Test
    fun theExercisePageShowsMusclesHistoryAndInstructions() {
        seedFinishedWorkout(System.currentTimeMillis() - DAY, bench(), 100.0, 5)
        openPage("bench", benchPress)

        val map = str(
            Res.string.exercise_map_description,
            str(Res.string.muscle_name_chest),
            listOf(str(Res.string.muscle_name_triceps), str(Res.string.muscle_name_front_delts)).joinToString(", "),
        )
        assertShown(hasContentDescription(map))
        assertShown(hasText(str(Res.string.exercise_map_primary)))
        assertShown(hasText(str(Res.string.exercise_map_secondary)))
        compose.onNode(hasText(str(R.string.exercise_add_to_workout))).assertDoesNotExist()
        screenshot("1-page")

        val page = compose.onNode(hasTestTag(EXERCISE_PAGE_TAG))
        page.performScrollToNode(hasText(str(R.string.exercise_set_value, "100", 5)))
        assertShown(hasText(str(R.string.exercise_best_set)))
        assertShown(hasText(plural(R.plurals.exercise_sessions, 1)))
        page.performScrollToNode(hasText(str(R.string.detail_set_line, "100", 5)))
        assertShown(hasText(str(R.string.detail_set_line, "100", 5)))
        screenshot("2-history")

        page.performScrollToNode(heading(str(R.string.exercise_instructions)))
        page.performScrollToNode(hasText("Lie back on a flat bench", substring = true))
        assertShown(hasText("Lie back on a flat bench", substring = true))
        screenshot("3-instructions")
    }

    /**
     * An extended exercise nobody has picked yet: "Add to routine" lists the
     * routines, a tap adds it (creating its Room row) and says so, the page
     * then has a history section, and the routine lists the exercise.
     */
    @Test
    fun addAnExtendedExerciseToARoutine() {
        val source = seedFinishedWorkout(System.currentTimeMillis() - 3 * DAY, bench(), 60.0, 8)
        checkNotNull(runBlocking { RoutineRepository(db).saveFromWorkout(source, "Push day") })
        val zercher = extendedExerciseId("Zercher_Squats")

        openPage("zercher", "Zercher Squats")
        compose.onNode(heading(str(R.string.exercise_history))).assertDoesNotExist()
        clickText(str(R.string.exercise_add_to_routine))
        waitForText(str(R.string.exercise_choose_routine))
        screenshot("1-routine-sheet")
        clickText("Push day")
        waitForText(str(R.string.exercise_added_to_routine, "Push day"))
        waitUntil { count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '$zercher'") == 1 }
        assertEquals(1, count("SELECT COUNT(*) FROM exercise WHERE id = '$zercher' AND isCustom = 0"))
        assertEquals(
            "with the defaults: 3 sets, no target reps",
            1,
            count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '$zercher' AND targetSets = 3 AND targetReps IS NULL"),
        )
        // Now a row in Room: the page shows its (empty) history.
        assertShown(hasText(str(R.string.exercise_not_logged)))
        screenshot("2-added")

        clickDescription(str(Res.string.action_back))
        clickText(str(R.string.nav_home))
        clickText(str(R.string.routines_all))
        waitGone(hasText(str(R.string.home_recent)))
        clickText("Push day")
        waitForText(benchPress)
        waitForText("Zercher Squats")
        screenshot("3-routine")
    }

    /** With a workout in progress the page offers "Add to workout", which adds it and opens the workout. */
    @Test
    fun addToTheWorkoutInProgress() {
        val workoutId = checkNotNull(
            runBlocking { app.container.commands.execute(Command.StartWorkout()) }.id(CommandIds.WORKOUT_ID),
        )
        openPage("bench", benchPress)
        clickText(str(R.string.exercise_add_to_workout))
        waitForText(str(R.string.workout_finish))
        waitForText(benchPress)
        waitUntil { count("SELECT COUNT(*) FROM workout_exercise WHERE workoutId = '$workoutId' AND exerciseId = '${bench()}'") == 1 }
        screenshot("1-in-workout")
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
