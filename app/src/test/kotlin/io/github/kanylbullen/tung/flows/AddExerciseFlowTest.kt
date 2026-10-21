package io.github.kanylbullen.tung.flows

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding an exercise to a workout (issue #55): the first time, straight into
 * its weight and reps and then how many sets; with history, as many sets as
 * last session, pre-filled, and no sheet.
 */
class AddExerciseFlowTest : FlowTest() {

    private val weightField get() = str(R.string.input_field_weight)
    private val repsField get() = str(R.string.input_field_reps)
    private fun chip(count: Int) = hasContentDescription(plural(R.plurals.workout_first_sets_choice, count, count))

    /** Weight and reps of every set of the workout in progress, in order. */
    private fun activeSets(): List<Pair<Double, Int>> = db.openHelper.readableDatabase.query(
        """
        SELECT s.weightKg, s.reps FROM set_entry s
        JOIN workout_exercise we ON s.workoutExerciseId = we.id JOIN workout w ON we.workoutId = w.id
        WHERE w.endedAt IS NULL ORDER BY we.position, s.position
        """.trimIndent(),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getDouble(0) to c.getInt(1)) } }

    private fun type(fieldDescription: String, text: String) {
        waitForDescription(fieldDescription)
        compose.onNode(hasContentDescription(fieldDescription)).performTextReplacement(text)
    }

    @Test
    fun theFirstTimeAskForWeightThenRepsAndHowManySets() {
        startWorkoutFromHome()
        addExerciseFirstTime("bench", str(R.string.ex_bench_press))

        // The weight sheet opens by itself for set 1.
        type(weightField, "60")
        screenshot("1-weight")
        compose.onNode(hasText(str(R.string.input_done))).performClick()

        // Done leads to the reps sheet, which in this flow asks for the number of sets too.
        waitGone(hasContentDescription(weightField))
        type(repsField, "8")
        waitFor(chip(3))
        compose.onNode(chip(3)).assertIsSelected()
        listOf(3, 4, 5, 6, 8).forEach { count ->
            assertShown(chip(count) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        }
        compose.onNode(chip(4)).performClick()
        compose.onNode(chip(4)).assertIsSelected()
        screenshot("2-reps-and-sets")
        compose.onNode(hasText(str(R.string.input_done))).performClick()
        waitGone(hasContentDescription(repsField))

        waitUntil { activeSets() == List(4) { 60.0 to 8 } }
        waitForDescription(str(R.string.workout_select_set, 4))
        // Set 1 is the current set: the Log set button acts on it.
        waitForText(str(R.string.workout_log_set, str(R.string.ex_bench_press), "60", "8"))
        val current = hasContentDescription(str(R.string.workout_set_number_description, 1)) and
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(R.string.workout_current_set))
        assertShown(current)
        screenshot("3-four-sets")
    }

    @Test
    fun dismissingTheWeightSheetLeavesOneEmptySet() {
        startWorkoutFromHome()
        addExerciseFirstTime("bench", str(R.string.ex_bench_press))
        waitForDescription(weightField)
        dismissSheet(weightField)
        assertEquals(listOf(0.0 to 0), activeSets())
        waitForDescription(str(R.string.workout_edit_weight, "0"))
        assertTrue("no reps sheet followed", compose.onAllNodes(hasContentDescription(repsField)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun dismissingTheRepsSheetKeepsTheWeightAndOneSet() {
        startWorkoutFromHome()
        addExerciseFirstTime("bench", str(R.string.ex_bench_press))
        type(weightField, "60")
        compose.onNode(hasText(str(R.string.input_done))).performClick()
        waitForDescription(repsField)
        dismissSheet(repsField)
        waitUntil { activeSets() == listOf(60.0 to 0) }
    }

    @Test
    fun withHistoryNoSheetOpensAndLastSessionsSetsAreThere() {
        val bench = bench()
        // Last session: three sets of bench press.
        val seeded = seedFinishedWorkout(startedAt = 1_000_000L, exerciseId = bench, weightKg = 80.0, reps = 8)
        db.openHelper.writableDatabase.apply {
            val weId = query("SELECT id FROM workout_exercise WHERE workoutId = ?", arrayOf(seeded)).use { c -> c.moveToFirst(); c.getString(0) }
            listOf(1 to 6, 2 to 5).forEach { (position, reps) ->
                execSQL(
                    "INSERT INTO set_entry (id, workoutExerciseId, position, weightKg, reps, completed, updatedAt, dirty) " +
                        "VALUES ('seed-$position', '$weId', $position, 85.0, $reps, 1, 1000000, 1)",
                )
            }
        }
        startWorkoutFromHome()
        addExerciseFirstTime("bench", str(R.string.ex_bench_press))

        waitUntil { activeSets() == listOf(80.0 to 8, 85.0 to 6, 85.0 to 5) }
        waitForDescription(str(R.string.workout_select_set, 3))
        screenshot("1-prefilled")
        assertTrue(
            "no sheet opens when the sets are already filled",
            compose.onAllNodes(hasContentDescription(weightField)).fetchSemanticsNodes().isEmpty(),
        )
    }
}
