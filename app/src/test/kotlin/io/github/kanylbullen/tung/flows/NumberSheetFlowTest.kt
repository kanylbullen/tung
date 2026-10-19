package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
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
}
