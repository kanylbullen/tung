package io.github.kanylbullen.tung.flows

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.parseWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerFlowTest : FlowTest() {

    private val field get() = compose.onNode(hasContentDescription(str(R.string.input_field_weight)))
    private val ruler get() = compose.onNode(hasContentDescription(str(R.string.input_ruler_weight)))
    private val done get() = compose.onNode(hasText(str(R.string.input_done)))
    private val applyToAll get() = compose.onNode(hasText(str(R.string.input_apply_all)))

    private fun fieldText(): String =
        field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** The ruler, saying it points at [kilos] (as the app writes it, "82.5"). */
    private fun rulerAt(kilos: String) =
        hasContentDescription(str(R.string.input_ruler_weight)) and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.input_ruler_weight_state, kilos))

    /** Weight, reps and ticked for each set of the workout in progress, in order. */
    private fun activeSets(): List<Triple<Double, Int, Boolean>> =
        db.openHelper.readableDatabase.query(
            "SELECT s.weightKg, s.reps, s.completed FROM set_entry s " +
                "JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
                "JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL ORDER BY s.position",
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(Triple(c.getDouble(0), c.getInt(1), c.getInt(2) != 0))
            }
        }

    /**
     * A horizontal swipe on the ruler moves the big number to a value on the
     * grid (1 kg below 30, 2.5 kg above), and Done saves that value. A vertical
     * swipe on the ruler leaves the number alone.
     */
    @Test
    fun swipingTheRulerSetsAWeightOnTheGrid() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))

        clickDescription(str(R.string.workout_edit_weight, "0"))
        waitForDescription(str(R.string.input_field_weight))
        assertShown(rulerAt("0"))
        screenshot("1-ruler-at-zero")

        ruler.performTouchInput { swipeUp() }
        compose.waitForIdle()
        field.assert(hasText("0"))

        ruler.performTouchInput { swipeLeft(startX = right - 20f, endX = left + 20f, durationMillis = 400) }
        compose.waitForIdle()
        val text = fieldText()
        val kg = checkNotNull(parseWeight(text)) { "the field holds '$text', not a weight" }
        assertNotEquals("the swipe did not move the number", 0.0, kg, 0.0)
        assertEquals("$kg kg is not on the ruler's grid", WEIGHT_RULER.snap(kg), kg, 0.0)
        assertShown(rulerAt(text))
        screenshot("2-after-swipe")

        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, text))
        assertEquals(listOf(kg), activeSets().map { it.first })
    }

    /**
     * − and + step by the ruler's local step: 1 kg below 30, 2.5 kg from 30
     * up, so 20 + gives 21, 30 + gives 32.5 and 30 − gives 29 (#17).
     */
    @Test
    fun stepsFollowTheLocalStep() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        clickDescription(str(R.string.workout_edit_weight, "0"))
        waitForDescription(str(R.string.input_field_weight))

        field.performTextReplacement("20")
        clickDescription(plural(R.plurals.input_plus_weight, 1, "1"))
        field.assert(hasText("21"))
        assertShown(rulerAt("21"))
        screenshot("1-twenty-plus-one")

        // At 30 the step up is 2.5 and the step down is 1, and the buttons say so.
        field.performTextReplacement("30")
        clickDescription(plural(R.plurals.input_plus_weight, 2, "2.5"))
        field.assert(hasText("32.5"))
        assertShown(rulerAt("32.5"))
        screenshot("2-thirty-plus")

        field.performTextReplacement("30")
        clickDescription(plural(R.plurals.input_minus_weight, 1, "1"))
        field.assert(hasText("29"))
        assertShown(rulerAt("29"))
        screenshot("3-thirty-minus")

        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, "29"))
        assertEquals(listOf(29.0), activeSets().map { it.first })
    }

    /**
     * A tap on the ruler, away from the centre line, jumps the number to the
     * nearest multiple of 10 kg under the finger, and Done saves it.
     */
    @Test
    fun tappingTheRulerJumpsToATen() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        clickDescription(str(R.string.workout_edit_weight, "0"))
        waitForDescription(str(R.string.input_field_weight))
        assertShown(rulerAt("0"))

        // About 120 dp right of the centre line: some 8 or 9 ticks of 1 kg, so 10 kg.
        ruler.performTouchInput { click(Offset(centerX + width * 0.3f, centerY)) }
        compose.waitForIdle()
        val text = fieldText()
        val kg = checkNotNull(parseWeight(text)) { "the field holds '$text', not a weight" }
        assertNotEquals("the tap did not move the number", 0.0, kg, 0.0)
        assertEquals("$kg kg is not a multiple of 10", 0.0, kg % 10.0, 1e-9)
        assertShown(rulerAt(text))
        screenshot("1-after-tap")

        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, text))
        assertEquals(listOf(kg), activeSets().map { it.first })
    }

    /**
     * Typing moves the ruler to the typed value, and the ruler never rewrites
     * what was typed: not to its grid, not to its range.
     */
    @Test
    fun typingMovesTheRulerAndWins() {
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        clickDescription(str(R.string.workout_edit_weight, "0"))
        waitForDescription(str(R.string.input_field_weight))

        field.performTextReplacement("82,5")
        assertShown(rulerAt("82.5"))
        field.assert(hasText("82,5"))
        screenshot("1-typed-82-5")

        // Off the grid: the ruler stops between ticks, the text stays as typed.
        field.performTextReplacement("82,3")
        assertShown(rulerAt("82.3"))
        field.assert(hasText("82,3"))

        // Past the ruler's end: it stops at 500, the 600 stays.
        field.performTextReplacement("600")
        assertShown(rulerAt("500"))
        field.assert(hasText("600"))

        done.performClick()
        waitForDescription(str(R.string.workout_edit_weight, "600"))
        assertEquals(listOf(600.0), activeSets().map { it.first })
    }

    /**
     * Three sets of 80 × 8 with the first ticked: setting 90 on set 2 with
     * "Apply to all sets" on writes 90 to sets 2 and 3, and the ticked set 1
     * stays at 80. The switch is off again the next time the sheet opens.
     */
    @Test
    fun applyToAllSetsSkipsTickedSets() {
        seedFinishedWorkout(startedAt = System.currentTimeMillis() - 2 * DAY, exerciseId = bench(), weightKg = 80.0, reps = 8)
        waitForText(str(R.string.home_recent))
        startWorkoutFromHome()
        val benchPress = str(R.string.ex_bench_press)
        addExercise("bench", benchPress)
        repeat(2) { clickDescription(str(R.string.workout_add_set_description, benchPress)) }
        compose.waitUntil(5_000) { activeSets().size == 3 }
        assertEquals(List(3) { Triple(80.0, 8, false) }, activeSets())

        clickDescription(str(R.string.workout_mark_done, 1))
        waitForDescription(str(R.string.workout_mark_undone, 1))

        val weightCells = hasContentDescription(str(R.string.workout_edit_weight, "80"))
        compose.waitUntil(5_000) { compose.onAllNodes(weightCells).fetchSemanticsNodes().size == 3 }
        compose.onAllNodes(weightCells)[1].performClick()
        waitForDescription(str(R.string.input_field_weight))
        field.performTextReplacement("90")
        applyToAll.assertIsOff()
        applyToAll.performClick()
        applyToAll.assertIsOn()
        screenshot("1-apply-to-all-on")
        done.performClick()
        waitGone(hasContentDescription(str(R.string.input_field_weight)))

        compose.waitUntil(5_000) { activeSets().map { it.first } == listOf(80.0, 90.0, 90.0) }
        assertEquals(
            listOf(Triple(80.0, 8, true), Triple(90.0, 8, false), Triple(90.0, 8, false)),
            activeSets(),
        )
        screenshot("2-sets-after-apply")

        // Off by default every time the sheet opens.
        val cell90 = hasContentDescription(str(R.string.workout_edit_weight, "90"))
        compose.waitUntil(5_000) { compose.onAllNodes(cell90).fetchSemanticsNodes().size == 2 }
        compose.onAllNodes(cell90)[1].performClick()
        waitForDescription(str(R.string.input_field_weight))
        applyToAll.assertIsOff()
        assertTrue(activeSets()[0].third)
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
