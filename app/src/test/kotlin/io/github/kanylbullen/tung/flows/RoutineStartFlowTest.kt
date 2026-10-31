package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.performClick
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.RoutineRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A routine's page has Start (issue #81): it starts the routine like the
 * row's Start and opens the workout. Disabled while a workout is in
 * progress, and for a routine with no exercises.
 */
class RoutineStartFlowTest : FlowTest() {

    @Test
    fun startFromTheRoutinesPageThenDisabledWhileThatWorkoutIsInProgress() {
        val source = seedFinishedWorkout(System.currentTimeMillis() - 3 * DAY, bench(), 60.0, 8)
        val routineId = checkNotNull(runBlocking { RoutineRepository(db).saveFromWorkout(source, "Push day") })
        val start = hasContentDescription(str(Res.string.routine_start_description, "Push day"))

        waitForText("Push day")
        clickText("Push day")
        waitFor(start and isEnabled())
        screenshot("1-routine")
        compose.onNode(start).performClick()
        waitForText(str(R.string.workout_finish))
        waitForText(str(R.string.ex_bench_press))
        screenshot("2-workout")
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL AND routineId = '$routineId'"))

        // Back lands on Home, since the page was popped under the workout; opened again, Start is disabled.
        clickDescription(str(Res.string.action_back))
        waitForText(str(R.string.home_resume_workout))
        clickText("Push day")
        waitFor(start and isNotEnabled())
        screenshot("3-disabled")
    }

    @Test
    fun startIsDisabledForAnEmptyRoutine() {
        runBlocking { RoutineRepository(db).create("Empty") }
        waitForText("Empty")
        clickText("Empty")
        waitFor(hasContentDescription(str(Res.string.routine_start_description, "Empty")) and isNotEnabled())
        assertShown(hasText(str(Res.string.routine_empty_hint)))
        screenshot("1-empty")
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
