package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.localDate
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Home's "This week" (the critical review of 2026-10-29): with no history
 * there is no week block, only the empty line; after a finished workout the
 * hero number is the week's volume, the counts say one workout and one set,
 * the day strip names the day, the small body names the muscles, and the
 * workout's row is titled with its exercise since it had no routine.
 */
class HomeWeekFlowTest : FlowTest() {

    private val benchPress get() = str(R.string.ex_bench_press)

    @Test
    fun thisWeekAppearsWithTheFirstFinishedWorkout() {
        waitForText(str(R.string.home_empty))
        assertTrue(compose.onAllNodes(hasText(str(R.string.home_this_week))).fetchSemanticsNodes().isEmpty())

        val startedAt = System.currentTimeMillis() - 30 * 60_000L
        seedFinishedWorkout(startedAt, bench(), 80.0, 8)

        waitForText(str(R.string.home_this_week))
        screenshot("1-home-this-week")
        assertShown(hasText("640"))
        val sep = str(R.string.row_separator)
        assertShown(hasText(plural(R.plurals.home_week_workouts, 1, 1) + sep + plural(R.plurals.home_week_sets, 1, 1)))

        val weekday = LocalDate.now(app.container.clock).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        assertShown(hasContentDescription(str(R.string.home_week_days_description, str(R.string.home_week_day_volume, weekday, "640"))))
        assertShown(hasContentDescription(str(R.string.home_muscles_description, ""), substring = true))

        // The row of a workout without a routine is titled with its exercise; its date is still its own text.
        assertShown(hasText(benchPress))
        assertShown(hasText(localDate(startedAt)))
    }
}
