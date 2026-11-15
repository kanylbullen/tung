package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * Issue #239: on a narrow phone (an iPhone SE is 375 pt wide) the rest
 * countdown in the top bar wrapped one character per line. At 320 dp, with a
 * rest running beside the elapsed time, the countdown is one line: no taller
 * than the clock's line and the bar's touch target.
 */
@Config(qualifiers = "en-w320dp-h640dp-xhdpi")
class NarrowRestCountdownFlowTest : FlowTest() {

    @Test
    fun theRestCountdownStaysOnOneLine() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * DAY, bench(), weightKg = 80.0, reps = 8)
        startWorkoutFromHome()
        addExercise("bench", str(R.string.ex_bench_press))
        val logSet = str(Res.string.workout_log_set, str(R.string.ex_bench_press), "80", "8")
        waitForText(logSet)
        compose.onNode(androidx.compose.ui.test.hasText(logSet)).performClick()

        val countdown = hasContentDescription(str(Res.string.rest_title))
        waitFor(countdown)
        screenshot("1-rest-at-320dp")
        val node = compose.onNode(countdown).fetchSemanticsNode()
        val heightDp = node.boundsInRoot.height / node.layoutInfo.density.density
        // One line of the countdown's 22 sp numbers is about 30 dp, inside the 48 dp touch target;
        // wrapped a character per line it was three lines, well over 48.
        assertTrue("the countdown is one line, not ${heightDp}dp tall", heightDp <= 56f)
        endRestFromButton()
    }

    private companion object {
        const val DAY = 24 * 60 * 60_000L
    }
}
