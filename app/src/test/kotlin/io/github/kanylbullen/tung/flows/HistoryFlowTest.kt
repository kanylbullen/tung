package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasText
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.ui.common.shortLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryFlowTest : FlowTest() {

    /**
     * With two finished workouts, History lists the newer one first; opening it
     * shows Bench press 80 kg × 8, and deleting it from the menu (after
     * confirming) leaves only the older one.
     */
    @Test
    fun listOpenAndDelete() {
        val older = seedFinishedWorkout(OLDER, catalogExerciseId("back_squat"), weightKg = 100.0, reps = 5)
        val newer = seedFinishedWorkout(NEWER, bench(), weightKg = 80.0, reps = 8)
        val olderDate = shortLocalDate(OLDER)
        val newerDate = shortLocalDate(NEWER)

        waitForText(str(R.string.home_recent))
        clickText(str(R.string.nav_history))
        waitGone(hasText(str(R.string.home_recent))) // left Home, which lists the same dates
        waitForText(newerDate)
        waitForText(olderDate)
        screenshot("1-history")

        val newerTop = compose.onNode(hasText(newerDate)).fetchSemanticsNode().boundsInRoot.top
        val olderTop = compose.onNode(hasText(olderDate)).fetchSemanticsNode().boundsInRoot.top
        assertTrue("newest first: $newerDate at $newerTop, $olderDate at $olderTop", newerTop < olderTop)

        clickText(newerDate)
        waitForText(str(R.string.ex_bench_press))
        assertShown(hasText(str(R.string.detail_set_line, "80", 8)))
        compose.onNode(hasText(str(R.string.ex_back_squat))).assertDoesNotExist()
        screenshot("2-detail")

        clickDescription(str(R.string.action_more))
        clickText(str(R.string.detail_delete))
        assertShown(hasText(str(R.string.detail_delete_body)))
        screenshot("3-delete-dialog")
        clickInDialog(str(R.string.detail_delete_confirm))

        waitForText(olderDate)
        waitGone(hasText(newerDate))
        screenshot("4-history-after-delete")

        assertEquals(1, count("SELECT COUNT(*) FROM workout"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE id = '$older'"))
        assertEquals(0, count("SELECT COUNT(*) FROM workout_exercise WHERE workoutId = '$newer'"))
    }

    private companion object {
        /** Fixed, distinct days, so the dates in the list are different and their order is known. */
        const val NEWER = 1_760_090_400_000L // 2025-10-10, 10:00 UTC
        const val OLDER = NEWER - 3 * 24 * 60 * 60_000L
    }
}
