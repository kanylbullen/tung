package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.data.RoutineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Editing a saved routine (issue #41): add an exercise through the same
 * picker the workout uses, give it target reps in the reps sheet, clear
 * another's target and remove it.
 */
class RoutineEditFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)
    private val barbellRow get() = str(R.string.ex_barbell_row)
    private fun targetReps(n: Int) = plural(Res.plurals.routine_target_reps, n, n)

    /**
     * "Push day" has Bench press, 1 set of 8. Its owner opens it, taps
     * "+ Exercise", searches "row" in the picker and taps Barbell row, which
     * lands last with 3 sets and no target reps. Tapping "No target reps"
     * opens the reps sheet: 0 is refused, 10 is saved. Then the bench press
     * row's menu clears its target and removes it, and the row moves up to
     * the first place.
     */
    @Test
    fun addAnExerciseSetItsRepsAndRemoveAnother() {
        val source = seedFinishedWorkout(System.currentTimeMillis() - 3 * DAY, bench(), 60.0, 8)
        val routineId = checkNotNull(runBlocking { RoutineRepository(db).saveFromWorkout(source, "Push day") })

        waitForText("Push day")
        clickText(str(R.string.routines_all))
        waitGone(hasText(str(R.string.home_recent)))
        clickText("Push day")
        waitForText(benchPress)
        assertShown(hasText(targetReps(8)))
        screenshot("1-routine")

        // + Exercise opens the workout's picker; a tap adds to the routine and comes back.
        clickText(str(Res.string.routine_add_exercise))
        searchPicker("barbell row")
        screenshot("2-picker")
        clickText(barbellRow)
        waitForText(str(Res.string.routine_add_exercise))
        waitForText(barbellRow)
        waitUntil { count("SELECT COUNT(*) FROM routine_exercise") == 2 }
        val row = catalogExerciseId("barbell_row")
        assertEquals(
            "appended last with 3 sets and no target reps",
            1,
            count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '$row' AND position = 1 AND targetSets = 3 AND targetReps IS NULL"),
        )
        assertShown(hasText(str(Res.string.routine_no_target_reps)))
        screenshot("3-added")

        // Target reps in the reps sheet: 0 is not a target, 10 is.
        clickDescription(str(Res.string.routine_edit_target_reps, barbellRow))
        val field = hasContentDescription(str(Res.string.input_field_reps))
        waitFor(field)
        assertShown(hasText(str(Res.string.routine_target_reps_title, barbellRow)))
        compose.onNode(field).performTextReplacement("0")
        waitForText(str(Res.string.routine_error_target_reps))
        compose.onNode(hasText(str(R.string.input_done)) and isEnabled()).assertDoesNotExist()
        screenshot("4-reps-sheet")
        compose.onNode(field).performTextReplacement("10")
        clickText(str(R.string.input_done))
        waitGone(field)
        waitUntil { count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '$row' AND targetReps = 10") == 1 }
        assertShown(hasText(targetReps(10)))

        // The bench press row's menu: clear its target, then remove it.
        clickDescription(str(Res.string.routine_exercise_menu, benchPress))
        clickText(str(Res.string.routine_clear_target_reps))
        waitUntil { count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '${bench()}' AND targetReps IS NULL") == 1 }
        assertShown(hasText(str(Res.string.routine_no_target_reps)))

        val benchRow = string("SELECT id FROM routine_exercise WHERE exerciseId = '${bench()}'")
        clickDescription(str(Res.string.routine_exercise_menu, benchPress))
        screenshot("5-row-menu")
        clickText(str(Res.string.routine_remove_exercise))
        waitGone(hasText(benchPress))
        waitUntil { count("SELECT COUNT(*) FROM routine_exercise") == 1 }
        assertEquals("the gap is closed", 1, count("SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '$row' AND position = 0"))
        assertEquals(
            "the delete is a tombstone for sync",
            1,
            count("SELECT COUNT(*) FROM tombstones WHERE tableName = '${SyncTable.ROUTINE_EXERCISES.key}' AND id = '$benchRow'"),
        )
        assertEquals("the workout it was saved from stays", 1, count("SELECT COUNT(*) FROM workout WHERE id = '$source'"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine WHERE id = '$routineId'"))
        screenshot("6-edited")
    }

    private fun string(sql: String): String =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
