package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.sync.SyncTable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A workout started by mistake can be abandoned (issue #81): "Discard
 * workout…" in the active workout's menu, and in the small menu on Home's
 * Resume card. Confirming deletes the workout with tombstones, saves nothing
 * and lands on Home, which offers Start again.
 */
class DiscardWorkoutFlowTest : FlowTest() {

    /** From the workout's own menu: Cancel keeps it, Discard deletes it, sets and all. */
    @Test
    fun discardFromTheWorkoutsMenu() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry"))

        clickDescription(str(R.string.action_more))
        clickText(str(R.string.workout_discard))
        waitFor(isDialog())
        screenshot("1-dialog")
        clickInDialog(str(R.string.action_cancel))
        waitGone(isDialog())
        assertShown(hasText(str(R.string.workout_finish)))
        assertEquals("cancel keeps the workout", 1, count("SELECT COUNT(*) FROM workout"))

        clickDescription(str(R.string.action_more))
        clickText(str(R.string.workout_discard))
        waitFor(isDialog())
        clickInDialog(str(R.string.workout_discard_confirm))
        waitForText(str(R.string.home_start_workout))
        screenshot("2-home")
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
        assertEquals(1, count("SELECT COUNT(*) FROM tombstones WHERE tableName = '${SyncTable.WORKOUTS.key}'"))
        assertEquals(1, count("SELECT COUNT(*) FROM tombstones WHERE tableName = '${SyncTable.SETS.key}'"))
    }

    /** From Home's Resume card, without opening the workout. */
    @Test
    fun discardFromHomesResumeCard() {
        startWorkoutFromHome()
        clickDescription(str(R.string.action_back))
        waitForText(str(R.string.home_resume_workout))
        screenshot("1-home")
        clickDescription(str(R.string.home_workout_menu))
        clickText(str(R.string.workout_discard))
        waitFor(isDialog())
        screenshot("2-dialog")
        clickInDialog(str(R.string.workout_discard_confirm))
        waitForText(str(R.string.home_start_workout))
        assertEquals(0, count("SELECT COUNT(*) FROM workout"))
        assertEquals(1, count("SELECT COUNT(*) FROM tombstones WHERE tableName = '${SyncTable.WORKOUTS.key}'"))
    }
}
