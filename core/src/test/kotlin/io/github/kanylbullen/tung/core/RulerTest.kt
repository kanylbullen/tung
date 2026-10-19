package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerTest {

    @Test
    fun weightRuler_spansZeroToFiveHundredInTwoAndAHalf() {
        assertEquals(200, WEIGHT_RULER.lastTick)
        assertEquals(0.0, WEIGHT_RULER.valueAt(0.0), 0.0)
        assertEquals(500.0, WEIGHT_RULER.valueAt(200.0), 0.0)
        assertEquals(82.5, WEIGHT_RULER.valueAt(33.0), 0.0)
    }

    @Test
    fun repsRuler_spansZeroToAHundred() {
        assertEquals(100, REPS_RULER.lastTick)
        assertEquals(8.0, REPS_RULER.valueAt(8.0), 0.0)
    }

    @Test
    fun positionOf_isExactBetweenTicks() {
        // A typed value off the grid sits between ticks; the ruler does not round it.
        assertEquals(32.92, WEIGHT_RULER.positionOf(82.3), 1e-9)
        assertEquals(82.3, WEIGHT_RULER.valueAt(WEIGHT_RULER.positionOf(82.3)), 0.0)
    }

    @Test
    fun positionOf_clampsToTheEnds() {
        assertEquals(200.0, WEIGHT_RULER.positionOf(600.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.positionOf(-5.0), 0.0)
        assertEquals(100.0, REPS_RULER.positionOf(250.0), 0.0)
    }

    @Test
    fun valueAt_clampsAndRoundsToHundredths() {
        assertEquals(500.0, WEIGHT_RULER.valueAt(250.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.valueAt(-3.0), 0.0)
        // Float noise from the animation does not leak into the value.
        assertEquals(82.3, WEIGHT_RULER.valueAt(32.919998), 0.0)
    }

    @Test
    fun snap_goesToTheNearestTick() {
        assertEquals(33, WEIGHT_RULER.snap(32.6))
        assertEquals(32, WEIGHT_RULER.snap(32.4))
        assertEquals(0, WEIGHT_RULER.snap(-0.7))
        assertEquals(200, WEIGHT_RULER.snap(203.0))
    }

    @Test
    fun snapValue_movesToTheGridAndClamps() {
        assertEquals(82.5, WEIGHT_RULER.snapValue(82.3), 0.0)
        assertEquals(80.0, WEIGHT_RULER.snapValue(81.2), 0.0)
        assertEquals(500.0, WEIGHT_RULER.snapValue(720.0), 0.0)
        assertEquals(12.0, REPS_RULER.snapValue(12.4), 0.0)
    }

    @Test
    fun majorTicks_everyTenKilosAndFiveReps() {
        assertTrue(WEIGHT_RULER.isMajor(0))
        assertTrue(WEIGHT_RULER.isMajor(4))
        assertFalse(WEIGHT_RULER.isMajor(5))
        assertEquals(40.0, WEIGHT_RULER.valueAt(16.0), 0.0)
        assertTrue(WEIGHT_RULER.isMajor(16))
        assertTrue(REPS_RULER.isMajor(10))
        assertFalse(REPS_RULER.isMajor(7))
    }
}
