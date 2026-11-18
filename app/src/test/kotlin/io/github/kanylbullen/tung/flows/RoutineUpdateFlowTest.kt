package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.shortLocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.shadows.ShadowToast

/**
 * Issue #217: what the summary and a workout's page offer to save after a
 * workout started from a routine. One that did what the routine asks offers
 * nothing; one that differs offers to update the routine or save a new one.
 * A workout not started from a routine keeps "Save as routine"
 * ([RoutineFlowTest], [SummaryFlowTest]).
 */
class RoutineUpdateFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)
    private val update get() = str(Res.string.routine_update, "Push day")

    /** "Push day" asks for 2 × 8 of Bench press; the workout does exactly that, only heavier: Done is all there is. */
    @Test
    fun aWorkoutThatDidItsRoutineOffersOnlyDone() {
        seedPushDay()
        startPushDayFromHome()
        clickDescription(str(Res.string.workout_mark_done, 1))
        clickDescription(str(Res.string.workout_mark_done, 2))
        waitForDescription(str(Res.string.workout_mark_undone, 2))
        finishWorkout()

        waitForText(str(Res.string.summary_title))
        waitForText(str(Res.string.summary_done))
        compose.onNode(hasText(str(Res.string.routine_save_as))).assertDoesNotExist()
        compose.onNode(hasText(str(Res.string.routine_save_as_new))).assertDoesNotExist()
        compose.onNode(hasText(update)).assertDoesNotExist()
        screenshot("1-summary-matches")
    }

    /**
     * The workout adds a third set: the summary offers "Update “Push day”"
     * and "Save as new routine". Updating asks first, then the routine asks
     * for three sets, keeps its row and name, and the summary offers only Done.
     */
    @Test
    fun aWorkoutThatDiffersUpdatesItsRoutine() {
        val routineId = seedPushDay()
        val rowBefore = string("SELECT id FROM routine_exercise")
        startPushDayFromHome()
        clickDescription(str(Res.string.workout_mark_done, 1))
        clickDescription(str(Res.string.workout_mark_done, 2))
        clickDescription(str(Res.string.workout_add_set_description, benchPress))
        clickDescription(str(Res.string.workout_mark_done, 3))
        waitForDescription(str(Res.string.workout_mark_undone, 3))
        finishWorkout()

        waitForText(str(Res.string.summary_title))
        waitForText(update)
        assertShown(hasText(str(Res.string.routine_save_as_new)))
        compose.onNode(hasText(str(Res.string.routine_save_as))).assertDoesNotExist()
        screenshot("1-summary-differs")

        clickText(update)
        assertShown(hasText(str(Res.string.routine_update_title, "Push day")))
        assertShown(hasText(str(Res.string.routine_update_body)))
        screenshot("2-update-dialog")
        clickInDialog(str(Res.string.routine_update_confirm))
        waitGone(isDialog())
        waitUntil(5_000) {
            compose.waitForIdle()
            ShadowToast.getTextOfLatestToast() == str(Res.string.routine_updated, "Push day")
        }
        waitGone(hasText(update))
        compose.onNode(hasText(str(Res.string.routine_save_as_new))).assertDoesNotExist()
        screenshot("3-summary-updated")

        assertEquals(1, count("SELECT COUNT(*) FROM routine WHERE id = '$routineId' AND name = 'Push day'"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE id = '$rowBefore' AND targetSets = 3 AND targetReps = 8"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise"))
    }

    /** On a finished workout's page the menu offers the same, and "Save as new routine" leaves the routine as it was. */
    @Test
    fun aWorkoutsPageOffersUpdateOrANewRoutine() {
        val routineId = seedPushDay()
        val startedAt = System.currentTimeMillis() - DAY
        finishPushDayWithAnExtraSet(routineId, startedAt)

        waitForText(str(Res.string.home_recent))
        clickText(str(Res.string.nav_history))
        waitGone(hasText(str(Res.string.home_recent)))
        clickText(shortLocalDate(startedAt))
        waitForText(benchPress)
        clickDescription(str(Res.string.action_more))
        waitForText(update)
        compose.onNode(hasText(str(Res.string.routine_save_as))).assertDoesNotExist()
        screenshot("1-detail-menu")

        clickText(str(Res.string.routine_save_as_new))
        val field = hasSetTextAction() and hasAnyAncestor(isDialog())
        waitFor(field)
        compose.onNode(field).performTextReplacement("Push day heavy")
        clickInDialog(str(Res.string.routine_save))
        waitGone(isDialog())
        waitUntil(5_000) { count("SELECT COUNT(*) FROM routine") == 2 }

        assertEquals(1, count("SELECT COUNT(*) FROM routine_exercise WHERE routineId = '$routineId' AND targetSets = 2"))
        assertEquals(1, count("SELECT COUNT(*) FROM routine r JOIN routine_exercise re ON re.routineId = r.id WHERE r.name = 'Push day heavy' AND re.targetSets = 3"))
    }

    // --- seeding through the real repositories ---

    /** "Push day": Bench press, 2 × 8, saved from a workout three days ago. */
    private fun seedPushDay(): String = runBlocking {
        var now = System.currentTimeMillis() - 3 * DAY
        val repo = WorkoutRepository(db, clock = { now })
        val workoutId = repo.startOrResumeWorkout()
        val workoutExerciseId = repo.addExercise(workoutId, bench(), sets = 1)
        repo.addSet(workoutExerciseId)
        setIds(workoutId).forEach { id ->
            repo.updateWeight(id, 80.0)
            repo.updateReps(id, 8)
            repo.setCompleted(id, true)
        }
        now += 45 * 60_000L
        check(repo.finishWorkout(workoutId))
        checkNotNull(RoutineRepository(db).saveFromWorkout(workoutId, "Push day"))
    }

    private fun startPushDayFromHome() {
        val start = hasContentDescription(str(Res.string.routine_start_description, "Push day")) and isEnabled()
        waitFor(start)
        compose.onNode(start).performClick()
        waitForText(str(Res.string.workout_finish))
        waitForDescription(str(Res.string.workout_mark_done, 2))
    }

    /** Starts [routineId] at [startedAt], adds a third set, ticks them all and finishes. */
    private fun finishPushDayWithAnExtraSet(routineId: String, startedAt: Long) = runBlocking {
        var now = startedAt
        val workouts = WorkoutRepository(db, clock = { now })
        val workoutId = checkNotNull(RoutineRepository(db, clock = { now }).start(routineId))
        workouts.addSet(string("SELECT id FROM workout_exercise WHERE workoutId = '$workoutId'"))
        setIds(workoutId).forEach { workouts.setCompleted(it, true) }
        now = startedAt + 45 * 60_000L
        check(workouts.finishWorkout(workoutId))
    }

    private fun setIds(workoutId: String): List<String> =
        db.openHelper.readableDatabase.query(
            "SELECT s.id FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id " +
                "WHERE we.workoutId = ? ORDER BY we.position, s.position",
            arrayOf(workoutId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun string(sql: String): String =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getString(0) }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
