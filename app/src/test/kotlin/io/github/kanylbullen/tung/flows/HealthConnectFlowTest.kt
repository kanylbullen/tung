package io.github.kanylbullen.tung.flows

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.health.CaloriesRecord
import io.github.kanylbullen.tung.core.health.HealthSamples
import io.github.kanylbullen.tung.core.health.HealthSegmentType
import io.github.kanylbullen.tung.core.health.HeartRateSample
import io.github.kanylbullen.tung.health.HealthAvailability
import io.github.kanylbullen.tung.ui.common.localDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Health Connect (issue #45) from Settings to the history, with [FlowTestApp.healthConnect] as the provider. */
class HealthConnectFlowTest : FlowTest() {

    private val fake get() = app.healthConnect
    private val writeLabel get() = str(R.string.health_write)
    private val readLabel get() = str(R.string.health_read)

    /** Settings scrolls; Health Connect is the last section. */
    private fun scrollTo(text: String) {
        waitForText(text)
        compose.onNode(hasText(text)).performScrollTo()
    }

    private fun longQuery(sql: String): Long =
        db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getLong(0) }

    /** Bench press with last time's 80 × 8 pre-filled: start, tick, finish. */
    private fun logAndFinishOneSet() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * DAY, bench(), weightKg = 80.0, reps = 8)
        waitForText(str(R.string.home_recent))
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        waitForDescription(str(R.string.workout_edit_reps, "8"))
        clickDescription(str(R.string.workout_mark_done, 1))
        waitForDescription(str(R.string.workout_mark_undone, 1))
        finishWorkout()
        waitForText(str(R.string.summary_title))
    }

    /**
     * The user turns on both switches (Health Connect grants at once), logs a
     * workout and finishes it: one strength-training session is written with
     * a bench press segment, and the workout's detail in History shows the
     * heart rate and calories Health Connect had for it.
     */
    @Test
    fun optedInAWorkoutIsWrittenOnceAndShowsItsHeartRateAndCalories() {
        fake.samples = { start, end ->
            HealthSamples(
                heartRate = listOf(HeartRateSample(start, 110), HeartRateSample(end, 150)),
                calories = listOf(CaloriesRecord(start, end, 87.0, "com.example.watch")),
            )
        }

        clickDescription(str(R.string.settings_title))
        scrollTo(writeLabel)
        assertShown(hasText(writeLabel) and isOff())
        screenshot("1-settings-off")
        compose.onNode(hasText(writeLabel)).performScrollTo().performClick()
        assertShown(hasText(writeLabel) and isOn())
        compose.onNode(hasText(readLabel)).performScrollTo().performClick()
        assertShown(hasText(readLabel) and isOn())
        screenshot("2-settings-on")
        assertEquals(2, fake.permissionRequests.get())
        clickDescription(str(R.string.action_back))

        logAndFinishOneSet()
        waitUntil(10_000) { count("SELECT COUNT(*) FROM workout WHERE healthConnectId IS NOT NULL AND avgHeartRate IS NOT NULL") == 1 }

        val session = fake.written.single()
        // No routine, so the title is data's "Workout".
        assertEquals(app.getString(io.github.kanylbullen.tung.data.R.string.health_session_title), session.title)
        assertEquals(listOf(HealthSegmentType.BENCH_PRESS), session.segments.map { it.type })
        assertEquals(8, session.segments.single().repetitions)

        clickText(str(R.string.summary_done))
        clickText(str(R.string.nav_history))
        val startedAt = longQuery("SELECT startedAt FROM workout WHERE healthConnectId IS NOT NULL")
        clickText(localDate(startedAt))
        waitForText(str(R.string.ex_bench_press))
        assertShown(hasText(str(R.string.detail_heart_rate, 130, 150) + " · " + str(R.string.detail_calories, 87)))
        screenshot("3-detail-with-heart-rate")

        assertEquals(1, fake.written.size)
        assertEquals(1, fake.reads.get())
    }

    /** Without the opt-ins the switches stay off, finishing writes nothing and reads nothing, and the detail has no such line. */
    @Test
    fun notOptedInNothingIsWrittenOrRead() {
        fake.samples = { start, _ -> HealthSamples(heartRate = listOf(HeartRateSample(start, 120))) }

        clickDescription(str(R.string.settings_title))
        scrollTo(readLabel)
        assertShown(hasText(writeLabel) and isOff())
        assertShown(hasText(readLabel) and isOff())
        clickDescription(str(R.string.action_back))

        val checksBefore = fake.availabilityChecks.get()
        logAndFinishOneSet()
        // The recorder asks for availability first; give it the moment it needs to (not) do the rest.
        waitUntil(10_000) { fake.availabilityChecks.get() > checksBefore }
        Thread.sleep(300)
        compose.waitForIdle()

        assertTrue(fake.written.isEmpty())
        assertEquals(0, fake.reads.get())
        assertEquals(0, fake.permissionRequests.get())
        assertEquals(0, count("SELECT COUNT(*) FROM workout WHERE healthConnectId IS NOT NULL OR avgHeartRate IS NOT NULL OR activeCalories IS NOT NULL"))

        clickText(str(R.string.summary_done))
        clickText(str(R.string.nav_history))
        val startedAt = longQuery("SELECT MAX(startedAt) FROM workout")
        clickText(localDate(startedAt))
        waitForText(str(R.string.ex_bench_press))
        compose.onNode(hasText(str(R.string.detail_calories, 0).removePrefix("0"), substring = true)).assertDoesNotExist()
        compose.onNode(hasText(str(R.string.detail_heart_rate_one, 0).removeSuffix("0"), substring = true)).assertDoesNotExist()
    }

    /** Where Health Connect is missing, the section says so and has no switches. */
    @Test
    fun unavailableShowsAHintAndNoSwitches() {
        fake.availability = HealthAvailability.UNAVAILABLE
        clickDescription(str(R.string.settings_title))
        scrollTo(str(R.string.health_unavailable))
        compose.onNode(hasText(writeLabel)).assertDoesNotExist()
        compose.onNode(hasText(readLabel)).assertDoesNotExist()
        screenshot("1-unavailable")
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
