package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberSheetFlowTest : FlowTest() {

    private val field get() = compose.onNode(hasContentDescription(str(R.string.input_field_weight)))
    private val done get() = compose.onNode(hasText(str(R.string.input_done)))

    private fun savedWeight(): Double =
        db.openHelper.readableDatabase.query("SELECT weightKg FROM set_entry").use { c ->
            check(c.count == 1) { "expected one set, found ${c.count}" }
            c.moveToFirst()
            c.getDouble(0)
        }

    /**
     * In the weight sheet, − and + move in 2.5 kg steps and never below zero,
     * "82,5" typed with a comma is saved as 82.5 kg, a weight over 999 shows the
     * error and cannot be confirmed, and a typed minus sign never gets in, so
     * nothing negative is saved.
     */
    @Test
    fun weightSheetStepsParsesAndRejects() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))

        clickDescription(str(R.string.workout_edit_weight, "0"))
        waitForDescription(str(R.string.input_field_weight))
        field.assert(hasText("0"))

        val plus = compose.onNode(hasContentDescription(str(R.string.input_plus_weight)))
        val minus = compose.onNode(hasContentDescription(str(R.string.input_minus_weight)))
        plus.performClick(); field.assert(hasText("2.5"))
        plus.performClick(); field.assert(hasText("5"))
        minus.performClick(); field.assert(hasText("2.5"))
        minus.performClick(); field.assert(hasText("0"))
        minus.performClick(); field.assert(hasText("0"))

        field.performTextReplacement("82,5")
        field.assert(hasText("82,5"))
        assertShown(hasText(str(R.string.input_hint)))
        done.assertIsEnabled()
        screenshot("1-typed-82-5")
        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, "82.5"))
        assertEquals(82.5, savedWeight(), 0.0)

        clickDescription(str(R.string.workout_edit_weight, "82.5"))
        waitForDescription(str(R.string.input_field_weight))

        field.performTextReplacement("1000")
        assertShown(hasText(str(R.string.input_error_weight)))
        done.assertIsNotEnabled()
        screenshot("2-too-heavy")

        // The sheet filters out the minus sign as it is typed: "-5" becomes "5".
        field.performTextReplacement("-5")
        field.assert(hasText("5"))
        done.assertIsEnabled()
        screenshot("3-minus-filtered")
        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, "5"))
        assertEquals(5.0, savedWeight(), 0.0)
    }

    /** The weight and reps of the one set in the workout in progress. */
    private fun activeSet(): Pair<Double, Int> =
        db.openHelper.readableDatabase.query(
            "SELECT s.weightKg, s.reps FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
                "JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL",
        ).use { c ->
            check(c.count == 1) { "expected one set in the active workout, found ${c.count}" }
            c.moveToFirst()
            c.getDouble(0) to c.getInt(1)
        }

    /**
     * A set prefilled with 80 kg × 8 from last time: tapping the big number and
     * typing replaces the value instead of appending to it (#7). "82,5" saves
     * 82.5 kg, not 8082.5, and "10" saves 10 reps, not 810.
     */
    @Test
    fun typingReplacesThePrefilledValue() {
        seedFinishedWorkout(startedAt = System.currentTimeMillis() - 2 * DAY, exerciseId = bench(), weightKg = 80.0, reps = 8)
        waitForText(str(R.string.home_recent))
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))

        clickDescription(str(R.string.workout_edit_weight, "80"))
        waitForDescription(str(R.string.input_field_weight))
        field.assert(hasText("80"))
        field.performClick()
        field.performTextInput("82,5")
        field.assert(hasText("82,5"))
        screenshot("1-weight-replaced")
        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, "82.5"))
        assertEquals(82.5, activeSet().first, 0.0)
        assertTrue(compose.onAllNodes(hasText("8082", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasContentDescription("8082", substring = true)).fetchSemanticsNodes().isEmpty())

        val repsField = compose.onNode(hasContentDescription(str(R.string.input_field_reps)))
        clickDescription(str(R.string.workout_edit_reps, "8"))
        waitForDescription(str(R.string.input_field_reps))
        repsField.assert(hasText("8"))
        repsField.performClick()
        repsField.performTextInput("10")
        repsField.assert(hasText("10"))
        screenshot("2-reps-replaced")
        done.performClick()
        waitForDescription(str(R.string.workout_edit_reps, "10"))
        assertEquals(10, activeSet().second)
        assertTrue(compose.onAllNodes(hasContentDescription("810", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /**
     * After tapping the number, − and + still step from the current value, and
     * typing after them appends at the end rather than replacing a stale selection.
     */
    @Test
    fun stepsAfterTappingTheNumber() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))

        clickDescription(str(R.string.workout_edit_weight, "0"))
        waitForDescription(str(R.string.input_field_weight))
        field.performClick()
        field.performTextInput("80")
        field.assert(hasText("80"))
        compose.onNode(hasContentDescription(str(R.string.input_plus_weight))).performClick()
        field.assert(hasText("82.5"))
        compose.onNode(hasContentDescription(str(R.string.input_minus_weight))).performClick()
        field.assert(hasText("80"))
        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, "80"))
        assertEquals(80.0, activeSet().first, 0.0)
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
