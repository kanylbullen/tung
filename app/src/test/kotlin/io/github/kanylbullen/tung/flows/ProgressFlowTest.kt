package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.ui.exercises.EXERCISE_PAGE_TAG
import io.github.kanylbullen.tung.ui.progress.EXERCISE_PROGRESS_TAG
import io.github.kanylbullen.tung.ui.progress.PROGRESS_LIST_TAG
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The Progress tab, an exercise's progress and the progress sheet inside a
 * workout (issue #60), over finished workouts seeded across weeks.
 *
 * Bench press: 60 × 10 in June 2025 (estimated 1RM 80), then 84 × 5 (98),
 * 90 × 5 (105) and 96 × 5 (112) in the last 3 months; squat 100 × 5 and
 * 110 × 5 in October, the last one today. Today is Wednesday 21 October 2026.
 */
class ProgressFlowTest : FlowTest() {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-21")
    private val benchPress get() = str(R.string.ex_bench_press)
    private val squat get() = str(R.string.ex_back_squat)

    private fun at(date: String, hour: Int = 10): Long =
        LocalDate.parse(date).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun seed() {
        app.today = Clock.fixed(today.atTime(12, 0).atZone(zone).toInstant(), zone)
        seedFinishedWorkout(at("2025-06-01"), bench(), 60.0, 10)
        seedFinishedWorkout(at("2026-08-05"), bench(), 84.0, 5)
        seedFinishedWorkout(at("2026-09-30"), bench(), 90.0, 5)
        seedFinishedWorkout(at("2026-10-07"), catalogExerciseId("back_squat"), 100.0, 5)
        seedFinishedWorkout(at("2026-10-20"), bench(), 96.0, 5)
        seedFinishedWorkout(at("2026-10-21", hour = 8), catalogExerciseId("back_squat"), 110.0, 5)
    }

    private fun summary(metric: Int, range: Int, from: String, to: String) =
        str(R.string.progress_chart_summary, str(metric), str(range), from, to)

    private fun openTab() {
        clickText(str(R.string.nav_progress))
        waitFor(hasTestTag(PROGRESS_LIST_TAG))
    }

    private val benchRow get() =
        "${str(R.string.progress_e1rm_value, "112")}${str(R.string.row_separator)}${str(R.string.progress_change_3m, str(R.string.progress_change_up, "14"))}"

    /**
     * The tab's overview says 5 workouts in the last 12 weeks, 2 this week;
     * squat (trained today) comes before bench press, whose row has its
     * estimate and "+14 kg in 3 months"; both latest sessions are records.
     */
    @Test
    fun theTabShowsTheOverviewTheExercisesAndTheRecords() {
        seed()
        openTab()
        assertShown(hasContentDescription(str(R.string.progress_workouts_summary, 5, 2)))
        assertShown(hasContentDescription(str(R.string.progress_volume_summary, "2,400", "1,030")))
        assertShown(hasText(str(R.string.progress_workouts_total)))
        assertShown(hasText(plural(R.plurals.progress_streak_label, 1)))
        screenshot("1-overview")

        val list = compose.onNode(hasTestTag(PROGRESS_LIST_TAG))
        list.performScrollToNode(hasText(benchRow))
        assertShown(hasText(benchPress) and hasText(benchRow))
        assertShown(hasContentDescription(str(R.string.progress_sparkline_summary, "98", "112")))
        // Squat (trained today) is above bench press (yesterday).
        val squatTop = compose.onNode(hasText(squat) and hasText(str(R.string.progress_e1rm_value, "128"), substring = true)).fetchSemanticsNode().boundsInRoot.top
        val benchTop = compose.onNode(hasText(benchRow)).fetchSemanticsNode().boundsInRoot.top
        check(squatTop < benchTop) { "squat $squatTop, bench $benchTop" }

        list.performScrollToNode(hasText(str(R.string.progress_records_recent)))
        list.performScrollToNode(hasText(str(R.string.detail_set_line, "96", 5)))
        assertShown(hasText(str(R.string.detail_set_line, "110", 5)))
        // Both latest sessions are the heaviest yet.
        waitUntil { compose.onAllNodes(hasText(str(R.string.progress_record_heaviest), substring = true)).fetchSemanticsNodes().size == 2 }
        screenshot("2-exercises-and-records")

        compose.onNode(hasText(benchRow)).performClick()
        waitFor(hasTestTag(EXERCISE_PROGRESS_TAG))
        assertShown(hasContentDescription(summary(R.string.progress_metric_e1rm_long, R.string.progress_range_3m_long, "98", "112")))
    }

    /**
     * From the exercise page, "Progress" opens the chart: estimated 1RM over 3
     * months, then the top set, then the top set over all time; the records
     * and the last sessions are listed under it.
     */
    @Test
    fun exerciseProgressSwitchesMetricAndRange() {
        seed()
        clickText(str(R.string.nav_exercises))
        waitFor(hasSetTextAction())
        compose.onNode(hasSetTextAction()).performTextReplacement("bench")
        clickText(benchPress)
        waitFor(hasTestTag(EXERCISE_PAGE_TAG))
        waitForText(str(R.string.exercise_add_to_routine))
        val page = compose.onNode(hasTestTag(EXERCISE_PAGE_TAG))
        page.performScrollToNode(hasText(str(R.string.exercise_progress)))
        clickText(str(R.string.exercise_progress))
        waitFor(hasTestTag(EXERCISE_PROGRESS_TAG))

        assertShown(hasContentDescription(summary(R.string.progress_metric_e1rm_long, R.string.progress_range_3m_long, "98", "112")))
        screenshot("1-e1rm-3m")

        clickText(str(R.string.progress_metric_top_set))
        assertShown(hasContentDescription(summary(R.string.progress_metric_top_set_long, R.string.progress_range_3m_long, "84", "96")))

        clickText(str(R.string.progress_range_all))
        assertShown(hasContentDescription(summary(R.string.progress_metric_top_set_long, R.string.progress_range_all_long, "60", "96")))
        screenshot("2-top-set-all")

        clickText(str(R.string.progress_metric_volume))
        clickText(str(R.string.progress_range_1y))
        assertShown(hasContentDescription(summary(R.string.progress_metric_volume_long, R.string.progress_range_1y_long, "420", "480")))

        val list = compose.onNode(hasTestTag(EXERCISE_PROGRESS_TAG))
        list.performScrollToNode(hasText(str(R.string.progress_record_e1rm)))
        assertShown(hasText(str(R.string.progress_e1rm_from, "112", "96", 5)))
        list.performScrollToNode(hasText(str(R.string.progress_last_sessions)))
        list.performScrollToNode(hasText(str(R.string.exercise_set_value, "60", 10)))
        screenshot("3-records-and-sessions")
    }

    /**
     * In the active workout, bench press's trend button opens the sheet: the
     * 3-month estimate, the last sessions, and what the current set (pre-filled
     * 96 × 5) would take: 6 reps for a new best estimate. With reps 7 it says
     * that set would be one. Dismissing leaves the workout as it was.
     */
    @Test
    fun theSheetInsideAWorkoutShowsWhatBeatsTheRecord() {
        seed()
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        waitForDescription(str(R.string.workout_edit_weight, "96"))

        val progressFor = str(R.string.workout_progress_for, benchPress)
        val chart = summary(R.string.progress_metric_e1rm_long, R.string.progress_range_3m_long, "98", "112")
        clickDescription(progressFor)
        assertShown(hasContentDescription(chart))
        assertShown(hasText(str(R.string.progress_beats_e1rm, "96", 6)))
        assertShown(hasText(str(R.string.exercise_set_value, "90", 5)))
        screenshot("1-sheet")
        dismissSheet(chart)
        assertShown(hasContentDescription(str(R.string.workout_edit_weight, "96")))

        enterInSheet(str(R.string.workout_edit_reps, "5"), str(R.string.input_field_reps), "7", str(R.string.workout_edit_reps, "7"))
        clickDescription(progressFor)
        assertShown(hasText(str(R.string.progress_beats_e1rm, "96", 7)))
        screenshot("2-sheet-record")
        dismissSheet(chart)
        assertShown(hasContentDescription(str(R.string.workout_edit_reps, "7")))
        check(count("SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id JOIN workout w ON we.workoutId = w.id WHERE w.endedAt IS NULL AND s.completed = 1") == 0)
    }

    /** The same screens in the dark theme, for the screenshots. */
    @Test
    @Config(qualifiers = "en-w411dp-h891dp-night-xhdpi")
    fun darkTheme() {
        seed()
        openTab()
        assertShown(hasContentDescription(str(R.string.progress_workouts_summary, 5, 2)))
        screenshot("1-overview")
        val list = compose.onNode(hasTestTag(PROGRESS_LIST_TAG))
        list.performScrollToNode(hasText(benchRow))
        compose.onNode(hasText(benchRow)).performClick()
        waitFor(hasTestTag(EXERCISE_PROGRESS_TAG))
        assertShown(hasContentDescription(summary(R.string.progress_metric_e1rm_long, R.string.progress_range_3m_long, "98", "112")))
        clickText(str(R.string.progress_range_all))
        assertShown(hasContentDescription(summary(R.string.progress_metric_e1rm_long, R.string.progress_range_all_long, "80", "112")))
        screenshot("2-exercise")
    }
}
