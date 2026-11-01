package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.common.shortLocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A workout logged after the fact: "Log past workout" on History, the
 * workout screen showing when it started rather than a timer, and finishing
 * it with how long it lasted. And a finished workout moved in time from its
 * page.
 */
class PastWorkoutFlowTest : FlowTest() {

    private val zone = ZoneId.systemDefault()
    private val benchPress get() = str(R.string.ex_bench_press)

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long = date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /**
     * Someone who forgot to log yesterday's session opens History at 19:10,
     * taps "Log past workout", which offers today 18:00, steps the date back
     * one day and logs it. The workout screen says when it started; they add
     * Bench press and log 80 × 8, then finish it with a duration of 1 h 30 min
     * (60 min and two steps of 15). History lists it on yesterday's date.
     */
    @Test
    fun logAWorkoutFromYesterday() {
        val today = LocalDate.now(zone)
        app.today = Clock.fixed(Instant.ofEpochMilli(millis(today, 19, 10)), zone)
        val start = millis(today.minusDays(1), 18, 0)
        seedFinishedWorkout(SEEDED, bench(), weightKg = 80.0, reps = 8)

        clickText(str(R.string.nav_history))
        clickDescription(str(Res.string.past_log))
        waitForText(str(Res.string.past_log_confirm))
        assertShown(hasText("18:00") and hasAnyAncestor(isDialog()))
        clickDescription(str(Res.string.time_day_earlier))
        waitFor(hasText(shortLocalDate(start)) and hasAnyAncestor(isDialog()))
        screenshot("1-log-past-dialog")
        clickInDialog(str(Res.string.past_log_confirm))

        waitForText(str(Res.string.workout_empty))
        waitForDescription(str(Res.string.workout_started_description, shortLocalDate(start) + " · 18:00"))
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL AND startedAt = $start"))
        addExercise("bench", benchPress)
        clickText(str(Res.string.workout_log_set, benchPress, "80", "8"))
        waitForDescription(str(Res.string.workout_mark_undone, 1))
        screenshot("2-past-workout")

        clickText(str(Res.string.workout_finish))
        waitFor(isDialog())
        waitFor(hasText(str(Res.string.duration_hours_minutes, 1, 0)) and hasAnyAncestor(isDialog()))
        clickDescription(str(Res.string.time_duration_longer))
        clickDescription(str(Res.string.time_duration_longer))
        waitFor(hasText(str(Res.string.duration_hours_minutes, 1, 30)) and hasAnyAncestor(isDialog()))
        screenshot("3-finish-with-duration")
        clickInDialog(str(Res.string.workout_finish_confirm))

        waitForText(str(R.string.summary_done))
        val end = start + 90 * 60_000L
        waitUntil { count("SELECT COUNT(*) FROM workout WHERE startedAt = $start AND endedAt = $end") == 1 }
        clickText(str(R.string.summary_done))
        clickText(str(R.string.nav_history))
        waitForText(shortLocalDate(start))
        assertShown(hasText(str(Res.string.duration_hours_minutes, 1, 30), substring = true))
        screenshot("4-history")
    }

    /**
     * A finished workout's page, "Change date and time" in its menu: one day
     * earlier and 15 minutes longer, saved. The workout moves in the database
     * and the page's title follows.
     */
    @Test
    fun changeTheDateAndTimeOfAFinishedWorkout() {
        val workoutId = seedFinishedWorkout(SEEDED, bench(), weightKg = 80.0, reps = 8)
        val earlier = Instant.ofEpochMilli(SEEDED).atZone(zone).minusDays(1).toInstant().toEpochMilli()

        clickText(str(R.string.nav_history))
        clickText(shortLocalDate(SEEDED))
        waitForText(benchPress)
        clickDescription(str(Res.string.action_more))
        clickText(str(Res.string.detail_change_time))
        waitFor(hasText(str(Res.string.duration_minutes, 45)) and hasAnyAncestor(isDialog()))
        clickDescription(str(Res.string.time_day_earlier))
        clickDescription(str(Res.string.time_duration_longer))
        waitFor(hasText(str(Res.string.duration_hours_minutes, 1, 0)) and hasAnyAncestor(isDialog()))
        screenshot("1-change-time")
        clickInDialog(str(Res.string.detail_change_time_confirm))

        waitUntil {
            count("SELECT COUNT(*) FROM workout WHERE id = '$workoutId' AND startedAt = $earlier AND endedAt = ${earlier + 60 * 60_000L} AND dirty = 1") == 1
        }
        waitForText(localDate(earlier))
        screenshot("2-moved")
    }

    private companion object {
        /** A finished workout long before the test's days, so its date is distinct. */
        const val SEEDED = 1_760_090_400_000L // 2025-10-10, 10:00 UTC
    }
}
