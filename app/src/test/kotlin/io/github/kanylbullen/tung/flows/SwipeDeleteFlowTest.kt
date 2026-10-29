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

        // Past the row's threshold and fast, as a thumb does it.
        fun swipeTheRow() = compose.onNode(weightCell).performTouchInput {
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

        swipeTheRow()
        waitFor(deleteButton)
        compose.onNode(deleteButton).performClick()
        waitGone(weightCell)
        screenshot("2-deleted")
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
    }
}
