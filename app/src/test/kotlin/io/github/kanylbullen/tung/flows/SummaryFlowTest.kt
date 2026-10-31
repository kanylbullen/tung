package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.ui.progress.EXERCISE_PROGRESS_TAG
import io.github.kanylbullen.tung.ui.summary.SUMMARY_LIST_TAG
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The summary after Finish: each exercise's sets, volume and estimated 1RM,
 * the muscles worked with percentages, and progress charts.
 *
 * Bench press was 84 × 5 on 5 August (estimated 1RM 98, 420 kg) and 90 × 5 on
 * 30 September (105, 450 kg). Today's workout logs one set of 90 × 6 (108,
 * 540 kg). One set of bench press is a whole set for the chest and half a
 * set each for the front delts and the triceps: 50 %, 25 %, 25 %.
 */
class SummaryFlowTest : FlowTest() {

    private val zone = ZoneOffset.UTC
    private val benchPress get() = str(R.string.ex_bench_press)

    private fun at(date: String): Long = LocalDate.parse(date).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()

    /** A muscle as its row writes it: the label with a capital, "Chest". */
    private fun muscleRow(id: StringResource) = str(id).replaceFirstChar { it.uppercase() }

    private fun chart(metric: Int, from: String, to: String) =
        str(R.string.progress_chart_summary, str(metric), str(R.string.progress_range_3m_long), from, to)

    /** Seeds the history, then logs 90 × 6 of bench press and finishes. */
    private fun finishBenchWorkout() {
        app.today = Clock.fixed(LocalDate.parse("2026-10-21").atTime(12, 0).atZone(zone).toInstant(), zone)
        seedFinishedWorkout(at("2026-08-05"), bench(), 84.0, 5)
        seedFinishedWorkout(at("2026-09-30"), bench(), 90.0, 5)
        startWorkoutFromHome()
        addExercise("bench", benchPress)
        enterInSheet(str(R.string.workout_edit_reps, "5"), str(Res.string.input_field_reps), "6", str(R.string.workout_edit_reps, "6"))
        clickDescription(str(R.string.workout_mark_done, 1))
        waitForDescription(str(R.string.workout_mark_undone, 1))
        finishWorkout()
        // The top bar shows at once; the list and Done come with the report.
        waitForText(str(R.string.summary_done))
    }

    @Test
    fun theSummaryShowsExercisesMusclesAndProgress() {
        finishBenchWorkout()
        val sep = str(R.string.row_separator)
        val details = listOf(
            plural(R.plurals.summary_sets, 1, 1),
            str(R.string.volume_kg, "540"),
            str(R.string.progress_e1rm_value, "108"),
        ).joinToString(sep)
        assertShown(hasText(details))
        assertShown(hasText(str(R.string.set_value, "90", 6)))
        // Done and Save as routine stay at the bottom, whatever is scrolled.
        assertShown(hasText(str(R.string.summary_done)))
        assertShown(hasText(str(R.string.routine_save_as)))
        screenshot("1-exercises")

        val list = compose.onNode(hasTestTag(SUMMARY_LIST_TAG))
        val map = str(
            R.string.summary_muscles_description,
            listOf(
                str(R.string.summary_muscle_item, str(Res.string.muscle_name_chest), "50%"),
                str(R.string.summary_muscle_item, str(Res.string.muscle_name_front_delts), "25%"),
                str(R.string.summary_muscle_item, str(Res.string.muscle_name_triceps), "25%"),
            ).joinToString(", "),
        )
        list.performScrollToNode(hasContentDescription(map))
        assertShown(hasContentDescription(map))
        list.performScrollToNode(hasText(muscleRow(Res.string.muscle_name_triceps)) and hasText("25%"))
        assertShown(hasText(muscleRow(Res.string.muscle_name_chest)) and hasText("50%"))
        assertShown(hasText(muscleRow(Res.string.muscle_name_front_delts)) and hasText("25%"))
        screenshot("2-muscles")

        // The chart has the two earlier sessions and this one, the last point.
        val e1rm = chart(R.string.progress_metric_e1rm_long, "98", "108")
        list.performScrollToNode(hasContentDescription(e1rm))
        assertShown(hasContentDescription(e1rm))
        screenshot("3-progress")

        list.performScrollToNode(hasText(str(R.string.progress_metric_weekly_volume)))
        clickText(str(R.string.progress_metric_weekly_volume))
        val weekly = chart(R.string.progress_metric_weekly_volume_long, "420", "540")
        // The choice goes through DataStore and the ViewModel before the chart changes.
        waitFor(hasContentDescription(weekly))
        list.performScrollToNode(hasContentDescription(weekly))
        assertShown(hasContentDescription(weekly))
        // The Progress tab's choice: the summary shares it.
        waitUntilStored(ProgressMetric.WEEKLY_VOLUME) { app.container.progressSettings.trendMetric.first() }
        screenshot("4-progress-weekly")

        // The chart's exercise opens its progress; back returns to the summary.
        compose.onNode(hasText(benchPress) and hasClickAction()).performClick()
        waitFor(hasTestTag(EXERCISE_PROGRESS_TAG))
        clickDescription(str(R.string.action_back))
        waitFor(hasTestTag(SUMMARY_LIST_TAG))

        clickText(str(R.string.summary_done))
        waitForText(str(R.string.home_start_workout))
    }

    /** The same summary in the dark theme, for the screenshots. */
    @Test
    @Config(qualifiers = "en-w411dp-h891dp-night-xhdpi")
    fun theSummaryInTheDarkTheme() {
        finishBenchWorkout()
        screenshot("1-exercises")
        val list = compose.onNode(hasTestTag(SUMMARY_LIST_TAG))
        list.performScrollToNode(hasText(muscleRow(Res.string.muscle_name_chest)) and hasText("50%"))
        screenshot("2-muscles")
        list.performScrollToNode(hasContentDescription(chart(R.string.progress_metric_e1rm_long, "98", "108")))
        screenshot("3-progress")
    }
}
