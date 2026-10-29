package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Swiping a set row to the left reveals a Delete button instead of deleting
 * the set (issue #81): a tap on the button deletes, a tap on the open row
 * closes it again without reaching the cell underneath.
 */
class SwipeDeleteFlowTest : FlowTest() {

    @Test
    fun swipeRevealsDeleteATapOnTheRowClosesItAndATapOnDeleteDeletes() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        val weightCell = hasContentDescription(str(R.string.workout_edit_weight, "0"))
        waitFor(weightCell)
        val deleteButton = hasContentDescription(str(R.string.workout_delete_set_number, 1))

        // Past the row's threshold and fast, as a thumb does it; [index] among the rows with 0 kg.
        fun swipeTheRow(index: Int = 0) = compose.onAllNodes(weightCell)[index].performTouchInput {
            swipeLeft(startX = right - 10f, endX = right - 410f, durationMillis = 300)
        }

        swipeTheRow()
        waitFor(deleteButton)
        screenshot("1-revealed")
        assertEquals("a swipe alone deletes nothing", 1, count("SELECT COUNT(*) FROM set_entry"))

        // A tap on the open row closes it; the weight sheet underneath does not open.
        compose.onNode(weightCell).performTouchInput { click() }
        waitGone(deleteButton)
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry"))
        assertTrue(
            "the tap reached the weight cell",
            compose.onAllNodes(hasContentDescription(str(R.string.input_field_weight))).fetchSemanticsNodes().isEmpty(),
        )

        // One row open at a time: opening set 2 closes set 1.
        clickDescription(str(R.string.workout_add_set_description, str(R.string.ex_bench_press)))
        val deleteButton2 = hasContentDescription(str(R.string.workout_delete_set_number, 2))
        waitUntil { compose.onAllNodes(weightCell).fetchSemanticsNodes().size == 2 }
        swipeTheRow()
        waitFor(deleteButton)
        swipeTheRow(index = 1)
        waitFor(deleteButton2)
        waitGone(deleteButton)
        screenshot("2-second-row")
        compose.onNode(deleteButton2).performClick()
        waitUntil { count("SELECT COUNT(*) FROM set_entry") == 1 }

        swipeTheRow()
        waitFor(deleteButton)
        compose.onNode(deleteButton).performClick()
        waitGone(weightCell)
        screenshot("3-deleted")
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
    }
}
