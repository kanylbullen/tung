package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.RoutineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Routines tab (issue #53): in the bottom bar between Home and Exercises,
 * reached from Home's "All" too, and the way to build a routine from scratch.
 */
class RoutinesTabFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)

    /** A tab in the bottom bar; its label alone is not unique, as Home's Routines section has the same title. */
    private fun tab(label: Int): SemanticsMatcher = hasText(str(label)) and isSelectable()

    private fun left(matcher: SemanticsMatcher): Float = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.left

    /** The tab's own top bar: only the Routines tab has "+ New routine". */
    private fun waitForRoutinesTab() {
        waitForText(str(R.string.routine_new))
        assertShown(tab(R.string.nav_routines) and isSelected())
    }

    /**
     * The bar reads Home · Routines · Exercises · History. Home's "All" lands
     * on the Routines tab, which lists "Push day" under the Programs row and
     * stays the open tab when the activity is recreated; the Routines tab
     * itself does the same from Exercises.
     */
    @Test
    fun theTabIsInTheBarAndHomesAllLandsOnIt() {
        seedRoutine("Push day")

        waitForText("Push day")
        val order = listOf(R.string.nav_home, R.string.nav_routines, R.string.nav_exercises, R.string.nav_history).map(::tab)
        order.forEach { assertShown(it) }
        val lefts = order.map(::left)
        assertEquals("Home · Routines · Exercises · History", lefts.sorted(), lefts)
        assertShown(tab(R.string.nav_home) and isSelected())
        screenshot("1-home")

        clickText(str(R.string.routines_all))
        waitForRoutinesTab()
        waitGone(hasText(str(R.string.home_recent)))
        assertShown(hasText("Push day"))
        assertShown(hasText(str(R.string.programs_title)))
        assertShown(hasContentDescription(str(R.string.routine_start_description, "Push day")) and isEnabled())
        screenshot("2-routines-tab")

        compose.activityRule.scenario.recreate()
        waitForRoutinesTab()
        assertShown(hasText("Push day"))

        compose.onNode(tab(R.string.nav_exercises)).performClick()
        waitGone(hasText(str(R.string.routine_new)))
        compose.onNode(tab(R.string.nav_routines)).performClick()
        waitForRoutinesTab()
        assertShown(hasText("Push day"))
    }

    /**
     * Someone with no routines opens the tab, taps "+ New routine", calls it
     * "Legs" and lands on its empty detail, which says to add exercises; back
     * on the tab its Start is disabled. "+ Exercise" adds Bench press, after
     * which Start is enabled, and Start opens the workout with its three sets.
     */
    @Test
    fun aNewRoutineIsFilledAndStartedFromTheTab() {
        waitForText(str(R.string.home_empty))
        compose.onNode(tab(R.string.nav_routines)).performClick()
        waitForRoutinesTab()
        waitForText(str(R.string.routines_empty))
        screenshot("1-empty-tab")

        clickText(str(R.string.routine_new))
        val field = hasSetTextAction() and hasAnyAncestor(isDialog())
        waitFor(field)
        assertShown(hasText(str(R.string.routine_new_title)))
        // A blank name is refused in the dialog, as when saving a workout.
        clickInDialog(str(R.string.routine_create))
        waitForText(str(R.string.routine_error_name))
        compose.onNode(field).performTextReplacement("Legs")
        screenshot("2-name-dialog")
        clickInDialog(str(R.string.routine_create))
        waitGone(isDialog())

        waitForText(str(R.string.routine_empty_hint))
        assertShown(hasText("Legs"))
        assertShown(hasText(str(R.string.routine_add_exercise)))
        waitUntil { count("SELECT COUNT(*) FROM routine WHERE name = 'Legs' AND lastUsedAt IS NULL AND dirty = 1") == 1 }
        assertEquals(0, count("SELECT COUNT(*) FROM routine_exercise"))
        screenshot("3-empty-routine")

        clickDescription(str(R.string.action_back))
        waitForRoutinesTab()
        val start = hasContentDescription(str(R.string.routine_start_description, "Legs"))
        assertShown(start and isNotEnabled())

        clickText("Legs")
        clickText(str(R.string.routine_add_exercise))
        searchPicker("bench press")
        clickText(benchPress)
        waitForText(str(R.string.routine_add_exercise))
        waitForText(benchPress)
        waitGone(hasText(str(R.string.routine_empty_hint)))
        waitUntil { count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '${bench()}' AND position = 0 AND targetSets = 3") == 1 }
        screenshot("4-filled-routine")

        clickDescription(str(R.string.action_back))
        waitForRoutinesTab()
        waitFor(start and isEnabled())
        screenshot("5-tab-start-enabled")
        compose.onNode(start).performClick()

        waitForText(str(R.string.workout_finish))
        waitForText(benchPress)
        waitForDescription(str(R.string.workout_mark_done, 3))
        compose.onNode(hasContentDescription(str(R.string.workout_mark_done, 4))).assertDoesNotExist()
        assertEquals(
            1,
            count("SELECT COUNT(*) FROM workout w JOIN routine r ON w.routineId = r.id WHERE w.endedAt IS NULL AND r.name = 'Legs'"),
        )
        screenshot("6-workout")
    }

    private fun seedRoutine(name: String): String = runBlocking {
        val source = seedFinishedWorkout(System.currentTimeMillis() - DAY, bench(), 60.0, 8)
        checkNotNull(RoutineRepository(db).saveFromWorkout(source, name))
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
