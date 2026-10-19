package io.github.kanylbullen.tung.flows

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityFlowTest : FlowTest() {

    private fun hasCustomAction(label: String) = SemanticsMatcher("has custom action '$label'") { node ->
        node.config.getOrNull(SemanticsActions.CustomActions)?.any { it.label == label } == true
    }

    /**
     * With no swipe in progress, the "Delete" label behind each set row is not
     * in the semantics tree, so TalkBack does not read it on every set. Deleting
     * stays possible with the "Delete set" action on the parts of the row a
     * screen reader focuses, and that action deletes the set.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun swipeBackgroundIsHiddenAndTheDeleteActionWorks() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        val weightCell = hasContentDescription(str(R.string.workout_edit_weight, "0"))
        waitFor(weightCell)
        assertEquals(1, count("SELECT COUNT(*) FROM set_entry"))

        assertTrue(
            "the swipe background is in the semantics tree",
            compose.onAllNodes(hasText(str(R.string.workout_delete)))
                .fetchSemanticsNodes().isEmpty(),
        )

        val deleteSet = str(R.string.workout_delete_set)
        for (part in listOf(
            hasContentDescription(str(R.string.workout_set_number_description, 1)),
            weightCell,
            hasContentDescription(str(R.string.workout_edit_reps, str(R.string.workout_reps_empty))),
            hasContentDescription(str(R.string.workout_mark_done, 1)),
        )) {
            compose.onNode(part and hasCustomAction(deleteSet)).assertExists()
        }

        compose.onNode(weightCell).performCustomAccessibilityActionWithLabel(deleteSet)
        waitGone(weightCell)
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry"))
    }
}
