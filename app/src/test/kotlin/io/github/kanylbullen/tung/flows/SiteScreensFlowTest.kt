package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.resources.Res
import io.github.kanylbullen.tung.ui.resources.routine_start_description
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.summary.SUMMARY_LIST_TAG
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The three screens on the landing page and in the README, in the light and
 * the dark theme: Home with a week of training and three routines, a Push
 * day in progress with two sets logged and the rest running, and the
 * muscles of the finished workout. A fortnight of history is seeded through
 * the real repositories, dated back from today, so "This week", "Last" and
 * the pre-fill are what a lifter sees. The screens are also flow checks:
 * each waits for what it shows.
 */
class SiteScreensFlowTest : FlowTest() {

    private val zone = ZoneId.systemDefault()

    private fun at(daysAgo: Long): Long =
        LocalDate.now(zone).minusDays(daysAgo).atTime(17, 30).atZone(zone).toInstant().toEpochMilli()

    private fun ex(key: String) = catalogExerciseId(key)

    /** Logs [routineId] at [startedAt]: every set of each exercise at its weight × reps, then finished. */
    private fun logged(routineId: String, startedAt: Long, values: List<Pair<Double, Int>>) = runBlocking {
        var now = startedAt
        val workoutId = checkNotNull(RoutineRepository(db, clock = { now }).start(routineId))
        val repo = WorkoutRepository(db, clock = { now })
        val sets = db.openHelper.readableDatabase.query(
            "SELECT s.id, we.position FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id WHERE we.workoutId = ?",
            arrayOf(workoutId),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getInt(1)) } }
        sets.forEach { (setId, position) ->
            val (kg, reps) = values[position]
            repo.updateWeight(setId, kg)
            repo.updateReps(setId, reps)
            repo.setCompleted(setId, true)
        }
        now = startedAt + 52 * 60_000L
        check(repo.finishWorkout(workoutId))
    }

    /** Three routines and two weeks of them, the last Push day 82.5 × 8 on the bench. */
    private fun seed() = runBlocking {
        val routines = RoutineRepository(db)
        val push = routines.createFromPlan(
            "Push day",
            listOf(Triple(ex("bench_press"), 3, 8), Triple(ex("overhead_press"), 3, 8), Triple(ex("triceps_pushdown"), 3, 12)),
        )
        val pull = routines.createFromPlan(
            "Pull day",
            listOf(Triple(ex("deadlift"), 3, 5), Triple(ex("barbell_row"), 3, 8), Triple(ex("barbell_curl"), 3, 10)),
        )
        val legs = routines.createFromPlan(
            "Leg day",
            listOf(Triple(ex("back_squat"), 4, 5), Triple(ex("romanian_deadlift"), 3, 8), Triple(ex("leg_press"), 3, 10)),
        )
        logged(push, at(13), listOf(77.5 to 8, 42.5 to 8, 25.0 to 12))
        logged(pull, at(11), listOf(130.0 to 5, 65.0 to 8, 32.5 to 10))
        logged(legs, at(9), listOf(105.0 to 5, 90.0 to 8, 160.0 to 10))
        logged(push, at(6), listOf(80.0 to 8, 42.5 to 8, 27.5 to 12))
        logged(pull, at(4), listOf(135.0 to 5, 67.5 to 8, 32.5 to 10))
        logged(legs, at(2), listOf(110.0 to 5, 92.5 to 8, 170.0 to 10))
        logged(push, at(1), listOf(82.5 to 8, 45.0 to 8, 27.5 to 12))
        push
    }

    private fun ticked() = count("SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL AND s.completed = 1")

    /** The number, within its exercise, of the first open set: the one Log set ticks next. */
    private fun nextSetNumber() = count(
        "SELECT (SELECT COUNT(*) FROM set_entry o WHERE o.workoutExerciseId = s.workoutExerciseId AND o.position <= s.position) " +
            "FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id JOIN workout w ON we.workoutId = w.id " +
            "WHERE w.endedAt IS NULL AND s.completed = 0 ORDER BY we.position, s.position LIMIT 1",
    )

    /**
     * Taps Log set and waits until the set is ticked, so the next tap logs the
     * next set. First it waits for the screen to mark that set as current: the
     * button carries the set it showed, and a tap before the screen caught up
     * with the last tick would log the ticked set again, which does nothing
     * (CI's slower runner, where no screenshot gives the screen time).
     */
    private fun logNextSet() {
        waitFor(hasStateDescription(str(R.string.workout_current_set)) and hasContentDescription(str(R.string.workout_set_number_description, nextSetNumber())))
        val before = ticked()
        val logSet = hasText("Log set ·", substring = true)
        waitFor(logSet)
        compose.onNode(logSet).performClick()
        waitUntil { ticked() > before }
    }

    private fun screens() {
        seed()
        waitForText("Leg day")
        waitForText(str(R.string.home_this_week))
        screenshot("1-home")

        clickDescription(str(Res.string.routine_start_description, "Push day"))
        waitForText(str(R.string.workout_finish))
        waitForText(str(R.string.ex_bench_press))
        // Started 38 minutes ago, so the clock and the summary's duration read like a real workout.
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET startedAt = startedAt - ${38 * 60_000L} WHERE endedAt IS NULL")
        logNextSet()
        logNextSet()
        screenshot("2-workout")

        repeat(4) { logNextSet() }
        finishWorkout()
        waitForText(str(R.string.summary_done))
        val list = compose.onNode(hasTestTag(SUMMARY_LIST_TAG))
        list.performScrollToNode(hasContentDescription(str(R.string.summary_muscles) + ":", substring = true))
        screenshot("3-summary")
    }

    @Test
    fun theSiteScreensInTheLightTheme() = screens()

    @Test
    @Config(qualifiers = "en-w411dp-h891dp-night-xhdpi")
    fun theSiteScreensInTheDarkTheme() = screens()
}
