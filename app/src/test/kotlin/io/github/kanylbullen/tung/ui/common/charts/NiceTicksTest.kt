package io.github.kanylbullen.tung.ui.common.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The y-axis ticks of the progress charts: round values, at most four, covering the data. */
class NiceTicksTest {

    private fun check(min: Double, max: Double) {
        val ticks = niceTicks(min, max)
        assertTrue("$ticks", ticks.size in 2..MAX_Y_LABELS)
        assertTrue("$ticks covers $min", ticks.first() <= min)
        assertTrue("$ticks covers $max", ticks.last() >= max)
        val step = ticks[1] - ticks[0]
        ticks.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, 1e-9) }
    }

    @Test
    fun roundStepsAtMostFour() {
        assertEquals(listOf(90.0, 100.0, 110.0, 120.0), niceTicks(98.0, 112.0))
        assertEquals(listOf(0.0, 1.0, 2.0), niceTicks(0.0, 2.0))
        assertEquals(listOf(0.0, 500.0, 1000.0, 1500.0), niceTicks(0.0, 1030.0))
        check(0.0, 1.0)
        check(80.0, 112.0)
        check(60.0, 96.0)
        check(0.0, 12_345.0)
        check(92.5, 92.75)
        check(0.0, 7.0)
    }

    @Test
    fun aFlatSeriesHasOneTick() {
        assertEquals(listOf(100.0), niceTicks(100.0, 100.0))
        assertTrue(niceTicks(Double.NaN, 1.0).isEmpty())
    }
}
