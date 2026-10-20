package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.localWeekday
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.shadows.ShadowToast

class RoutineFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)

    private fun exerciseCount(n: Int): String = app.resources.getQuantityString(R.plurals.exercise_count, n, n)

    private fun routineDetails(exercises: Int, last: String): String =
        exerciseCount(exercises) + str(R.string.row_separator) + last

    /**
     * Someone benches 80 × 8 twice, finishes, taps "Save as routine" on the
     * summary, sees the weekday and the exercise offered as the name, calls it
     * "Push day" and saves. Home then lists it above the recent workouts as one
     * exercise, never done, and the routine asks for 2 sets of 8.
     */
    @Test
    fun saveAFinishedWorkoutAsARoutine() {
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        enterInSheet(str(R.string.workout_edit_weight, "0"), str(R.string.input_field_weight), "80")
        enterInSheet(str(R.string.workout_edit_reps, str(R.string.workout_reps_empty)), str(R.string.input_field_reps), "8")
        clickDescription(str(R.string.workout_mark_done, 1))
        clickDescription(str(R.string.workout_add_set_description, benchPress))
        // The second set copies the first: 80 × 8.
        clickDescription(str(R.string.workout_mark_done, 2))
        waitForDescription(str(R.string.workout_mark_undone, 2))
        finishWorkout()

        waitForText(str(R.string.summary_title))
        assertShown(hasText("2") and hasText(str(R.string.stat_sets)))
        screenshot("1-summary")

        clickText(str(R.string.routine_save_as))
        val field = hasSetTextAction() and hasAnyAncestor(isDialog())
        val startedAt = longQuery("SELECT startedAt FROM workout")
        val defaultName = str(R.string.routine_default_name, localWeekday(startedAt), benchPress)
        assertShown(field and hasText(defaultName))
        screenshot("2-name-dialog")
        compose.onNode(field).performTextReplacement("Push day")
        clickInDialog(str(R.string.routine_save))
        waitGone(isDialog())
        // The toast follows the write, which Room does on its own thread, and is shown
        // from the main looper, which only runs when the test idles it.
        waitUntil(5_000) {
            compose.waitForIdle()
            ShadowToast.getTextOfLatestToast() == str(R.string.routine_saved, "Push day")
        }

        clickText(str(R.string.summary_done))
        waitForText("Push day")
        assertShown(hasText(str(R.string.routines_title)))
        assertShown(hasText(routineDetails(1, str(R.string.routine_never))))
        assertShown(hasText(str(R.string.home_recent)))
        screenshot("3-home-with-routine")

        assertEquals(1, count("SELECT COUNT(*) FROM routine WHERE name = 'Push day' AND lastUsedAt IS NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise"))
        assertEquals(
            1,
            count(
                "SELECT COUNT(*) FROM routine_exercise WHERE exerciseId = '${bench()}' " +
                    "AND position = 0 AND targetSets = 2 AND targetReps = 8",
            ),
        )
    }

    /**
     * With "Push day" saved from a workout of 80 × 8 twice, tapping its Start
     * on Home opens a workout with Bench press and two unticked sets already
     * at 80 × 8. The workout remembers the routine, and back on Home the
     * routine was last done today while its Start waits for the workout to end.
     */
    @Test
    fun startARoutineFromHome() {
        val source = seedWorkout(System.currentTimeMillis() - 3 * DAY, bench(), listOf(80.0 to 8, 80.0 to 8))
        val routineId = runBlocking { RoutineRepository(db).saveFromWorkout(source, "Push day") }

        waitForText("Push day")
        assertShown(hasText(routineDetails(1, str(R.string.routine_never))))
        screenshot("1-home")

        val start = hasContentDescription(str(R.string.routine_start_description, "Push day")) and isEnabled()
        waitFor(start)
        compose.onNode(start).performClick()

        waitForText(str(R.string.workout_finish))
        waitForText(benchPress)
        waitForDescription(str(R.string.workout_mark_done, 2))
        assertEquals(2, compose.onAllNodes(hasContentDescription(str(R.string.workout_edit_weight, "80"))).fetchSemanticsNodes().size)
        assertEquals(2, compose.onAllNodes(hasContentDescription(str(R.string.workout_edit_reps, "8"))).fetchSemanticsNodes().size)
        compose.onNode(hasContentDescription(str(R.string.workout_mark_done, 3))).assertDoesNotExist()
        screenshot("2-active-workout")

        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL AND routineId = '$routineId'"))
        assertEquals(
            2,
            count(
                "SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
                    "JOIN workout w ON we.workoutId = w.id " +
                    "WHERE w.endedAt IS NULL AND s.weightKg = 80.0 AND s.reps = 8 AND s.completed = 0",
            ),
        )
        assertEquals(1, count("SELECT COUNT(*) FROM routine WHERE lastUsedAt IS NOT NULL"))

        clickDescription(str(R.string.action_back))
        waitForText(str(R.string.home_resume_workout))
        assertShown(hasText(routineDetails(1, str(R.string.routine_last, str(R.string.routine_today)))))
        assertShown(hasContentDescription(str(R.string.routine_start_description, "Push day")) and isNotEnabled())
        screenshot("3-home-in-progress")
    }

    /**
     * Someone opens all routines from Home, opens "Push day", adds a set to
     * Bench press and then deletes the routine. The workout it was saved from
     * and the workout done from it both stay in the history.
     */
    @Test
    fun deleteARoutineKeepsTheWorkouts() {
        val source = seedWorkout(System.currentTimeMillis() - 3 * DAY, bench(), listOf(80.0 to 8, 80.0 to 8))
        val routineId = checkNotNull(runBlocking { RoutineRepository(db).saveFromWorkout(source, "Push day") })
        val fromRoutine = finishRoutineWorkout(routineId, System.currentTimeMillis() - DAY)

        waitForText("Push day")
        clickText(str(R.string.routines_all))
        waitGone(hasText(str(R.string.home_recent))) // left Home, which lists the same routine
        waitForText("Push day")
        screenshot("1-routines")

        clickText("Push day")
        waitForText(benchPress)
        assertShown(hasText(app.resources.getQuantityString(R.plurals.routine_target_reps, 8, 8)))
        clickDescription(str(R.string.routine_more_sets, benchPress))
        waitUntil(5_000) { count("SELECT targetSets FROM routine_exercise") == 3 }
        assertShown(hasText("3"))
        screenshot("2-routine")

        clickDescription(str(R.string.action_more))
        clickText(str(R.string.routine_delete))
        assertShown(hasText(str(R.string.routine_delete_body)))
        screenshot("3-delete-dialog")
        clickInDialog(str(R.string.routine_delete_confirm))

        waitForText(str(R.string.routines_empty))
        screenshot("4-routines-empty")

        assertEquals(0, count("SELECT COUNT(*) FROM routine"))
        assertEquals(0, count("SELECT COUNT(*) FROM routine_exercise"))
        assertEquals(2, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NOT NULL"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE id = '$source'"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE id = '$fromRoutine' AND routineId = '$routineId'"))
        assertEquals(4, count("SELECT COUNT(*) FROM set_entry WHERE completed = 1"))
    }

    // --- seeding through the real repositories ---

    /** A finished workout with one exercise and the given ticked sets, as if logged at [startedAt]. */
    private fun seedWorkout(startedAt: Long, exerciseId: String, sets: List<Pair<Double, Int>>): String = runBlocking {
        var now = startedAt
        val repo = WorkoutRepository(db, clock = { now })
        val workoutId = repo.startOrResumeWorkout()
        val workoutExerciseId = repo.addExercise(workoutId, exerciseId)
        repeat(sets.size - 1) { repo.addSet(workoutExerciseId) }
        setIds(workoutId).zip(sets).forEach { (id, set) ->
            repo.updateWeight(id, set.first)
            repo.updateReps(id, set.second)
            repo.setCompleted(id, true)
        }
        now = startedAt + 45 * 60_000L
        check(repo.finishWorkout(workoutId)) { "seeded workout was discarded" }
        workoutId
    }

    /** Starts [routineId] at [startedAt], ticks every pre-filled set and finishes. */
    private fun finishRoutineWorkout(routineId: String, startedAt: Long): String = runBlocking {
        var now = startedAt
        val workouts = WorkoutRepository(db, clock = { now })
        val workoutId = checkNotNull(RoutineRepository(db, clock = { now }).start(routineId))
        setIds(workoutId).forEach { workouts.setCompleted(it, true) }
        now = startedAt + 45 * 60_000L
        check(workouts.finishWorkout(workoutId)) { "routine workout was discarded" }
        workoutId
    }

    private fun setIds(workoutId: String): List<String> =
        db.openHelper.readableDatabase.query(
            "SELECT s.id FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
                "WHERE we.workoutId = ? ORDER BY we.position, s.position",
            arrayOf(workoutId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun longQuery(sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
