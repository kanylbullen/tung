package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RulerTest {

    @Test
    fun weightRuler_oneKiloTicksToThirtyThenTwoAndAHalfToFiveHundred() {
        // 30 ticks of 1 kg, then 188 of 2.5 kg.
        assertEquals(218, WEIGHT_RULER.lastTick)
        assertEquals(0.0, WEIGHT_RULER.valueAt(0.0), 0.0)
        assertEquals(17.0, WEIGHT_RULER.valueAt(17.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.valueAt(30.0), 0.0)
        assertEquals(32.5, WEIGHT_RULER.valueAt(31.0), 0.0)
        assertEquals(82.5, WEIGHT_RULER.valueAt(51.0), 0.0)
        assertEquals(500.0, WEIGHT_RULER.valueAt(218.0), 0.0)
    }

    @Test
    fun repsRuler_spansZeroToAHundred() {
        assertEquals(100, REPS_RULER.lastTick)
        assertEquals(8.0, REPS_RULER.valueAt(8.0), 0.0)
        assertEquals(1.0, REPS_RULER.stepAt(50.0), 0.0)
    }

    @Test
    fun stepAt_changesAtThirty() {
        assertEquals(1.0, WEIGHT_RULER.stepAt(0.0), 0.0)
        assertEquals(1.0, WEIGHT_RULER.stepAt(29.0), 0.0)
        assertEquals(1.0, WEIGHT_RULER.stepAt(29.99), 0.0)
        assertEquals(2.5, WEIGHT_RULER.stepAt(30.0), 0.0)
        assertEquals(2.5, WEIGHT_RULER.stepAt(200.0), 0.0)
        // Below a boundary the finer step applies.
        assertEquals(1.0, WEIGHT_RULER.stepBelow(30.0), 0.0)
        assertEquals(2.5, WEIGHT_RULER.stepBelow(30.01), 0.0)
        assertEquals(1.0, WEIGHT_RULER.stepBelow(0.0), 0.0)
    }

    @Test
    fun positionOf_isContinuousAcrossThirty() {
        assertEquals(29.0, WEIGHT_RULER.positionOf(29.0), 1e-9)
        assertEquals(29.5, WEIGHT_RULER.positionOf(29.5), 1e-9)
        assertEquals(30.0, WEIGHT_RULER.positionOf(30.0), 1e-9)
        assertEquals(30.4, WEIGHT_RULER.positionOf(31.0), 1e-9)
        assertEquals(31.0, WEIGHT_RULER.positionOf(32.5), 1e-9)
    }

    @Test
    fun positionOf_isExactBetweenTicks() {
        // A typed value off the grid sits between ticks; the ruler does not round it.
        assertEquals(50.92, WEIGHT_RULER.positionOf(82.3), 1e-9)
        assertEquals(12.4, WEIGHT_RULER.positionOf(12.4), 1e-9)
    }

    @Test
    fun positionOfAndValueAt_roundTripOnBothSides() {
        for (kg in listOf(0.0, 7.0, 12.4, 29.0, 29.99, 30.0, 30.01, 31.0, 32.5, 82.3, 499.0, 500.0)) {
            assertEquals("$kg kg", kg, WEIGHT_RULER.valueAt(WEIGHT_RULER.positionOf(kg)), 0.0)
        }
        for (tick in 0..WEIGHT_RULER.lastTick) {
            assertEquals("tick $tick", tick.toDouble(), WEIGHT_RULER.positionOf(WEIGHT_RULER.valueAt(tick.toDouble())), 1e-9)
        }
    }

    @Test
    fun positionOf_clampsToTheEnds() {
        assertEquals(218.0, WEIGHT_RULER.positionOf(600.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.positionOf(-5.0), 0.0)
        assertEquals(100.0, REPS_RULER.positionOf(250.0), 0.0)
    }

    @Test
    fun valueAt_clampsAndRoundsToHundredths() {
        assertEquals(500.0, WEIGHT_RULER.valueAt(250.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.valueAt(-3.0), 0.0)
        // Float noise from the animation does not leak into the value.
        assertEquals(82.3, WEIGHT_RULER.valueAt(50.919998), 0.0)
    }

    @Test
    fun snapTick_goesToTheNearestTick() {
        assertEquals(33, WEIGHT_RULER.snapTick(32.6))
        assertEquals(32, WEIGHT_RULER.snapTick(32.4))
        assertEquals(0, WEIGHT_RULER.snapTick(-0.7))
        assertEquals(218, WEIGHT_RULER.snapTick(223.0))
    }

    @Test
    fun snap_followsTheLocalStepAroundThirty() {
        assertEquals(29.0, WEIGHT_RULER.snap(29.4), 0.0)
        assertEquals(30.0, WEIGHT_RULER.snap(29.6), 0.0)
        // Above 30 the neighbours are 30 and 32.5.
        assertEquals(30.0, WEIGHT_RULER.snap(31.2), 0.0)
        assertEquals(32.5, WEIGHT_RULER.snap(31.3), 0.0)
        assertEquals(12.0, WEIGHT_RULER.snap(12.4), 0.0)
    }

    @Test
    fun snap_movesToTheGridAndClamps() {
        assertEquals(82.5, WEIGHT_RULER.snap(82.3), 0.0)
        assertEquals(80.0, WEIGHT_RULER.snap(81.2), 0.0)
        assertEquals(500.0, WEIGHT_RULER.snap(720.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.snap(-2.0), 0.0)
        assertEquals(12.0, REPS_RULER.snap(12.4), 0.0)
    }

    @Test
    fun stepUpAndDown_crossThirtyWithTheLocalStep() {
        assertEquals(21.0, WEIGHT_RULER.stepUp(20.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.stepUp(29.0), 0.0)
        assertEquals(32.5, WEIGHT_RULER.stepUp(30.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.stepDown(32.5), 0.0)
        assertEquals(29.0, WEIGHT_RULER.stepDown(30.0), 0.0)
        assertEquals(28.0, WEIGHT_RULER.stepDown(29.0), 0.0)
        assertEquals(82.5, WEIGHT_RULER.stepUp(80.0), 0.0)
        assertEquals(77.5, WEIGHT_RULER.stepDown(80.0), 0.0)
    }

    @Test
    fun stepUpAndDown_takeAnOffGridValueToTheNextPointOnTheGrid() {
        assertEquals(32.5, WEIGHT_RULER.stepUp(31.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.stepDown(31.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.stepUp(29.5), 0.0)
        assertEquals(29.0, WEIGHT_RULER.stepDown(29.5), 0.0)
        assertEquals(82.5, WEIGHT_RULER.stepUp(82.3), 0.0)
        assertEquals(80.0, WEIGHT_RULER.stepDown(82.3), 0.0)
    }

    @Test
    fun stepUpAndDown_stopAtZeroAndCarryOnPastTheEnd() {
        assertEquals(0.0, WEIGHT_RULER.stepDown(0.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.stepDown(0.4), 0.0)
        assertEquals(1.0, WEIGHT_RULER.stepUp(0.0), 0.0)
        // A typed weight past the ruler keeps stepping by 2.5.
        assertEquals(502.5, WEIGHT_RULER.stepUp(500.0), 0.0)
        assertEquals(600.0, WEIGHT_RULER.stepDown(602.5), 0.0)
    }

    @Test
    fun majorTicks_everyFiveKilosBelowThirtyThenEveryTen() {
        val labelled = (0..WEIGHT_RULER.lastTick).filter { WEIGHT_RULER.isMajor(it) }.map { WEIGHT_RULER.valueAt(it.toDouble()) }
        assertEquals(listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0), labelled.take(9))
        assertEquals(500.0, labelled.last(), 0.0)
        // 35 is a multiple of 5 but above 30 only tens are labelled.
        assertFalse(WEIGHT_RULER.isMajor(WEIGHT_RULER.positionOf(35.0).toInt()))
        assertFalse(WEIGHT_RULER.isMajor(7))
        assertTrue(REPS_RULER.isMajor(10))
        assertFalse(REPS_RULER.isMajor(7))
    }

    @Test
    fun jumpTarget_roundsTheTappedValueToTheNearestTen() {
        assertEquals(10.0, WEIGHT_RULER.jumpTarget(8.8, toward = 0.0), 0.0)
        assertEquals(10.0, WEIGHT_RULER.jumpTarget(14.9, toward = 0.0), 0.0)
        assertEquals(20.0, WEIGHT_RULER.jumpTarget(15.1, toward = 40.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.jumpTarget(34.0, toward = 100.0), 0.0)
        assertEquals(90.0, WEIGHT_RULER.jumpTarget(87.5, toward = 0.0), 0.0)
        assertEquals(0.0, WEIGHT_RULER.jumpTarget(3.0, toward = 50.0), 0.0)
        assertEquals(500.0, WEIGHT_RULER.jumpTarget(498.0, toward = 0.0), 0.0)
        assertEquals(500.0, WEIGHT_RULER.jumpTarget(700.0, toward = 0.0), 0.0)
    }

    @Test
    fun jumpTarget_exactlyBetweenTwoPicksTheOneNearerTheCurrentValue() {
        assertEquals(20.0, WEIGHT_RULER.jumpTarget(25.0, toward = 12.0), 0.0)
        assertEquals(30.0, WEIGHT_RULER.jumpTarget(25.0, toward = 27.0), 0.0)
        assertEquals(80.0, WEIGHT_RULER.jumpTarget(85.0, toward = 60.0), 0.0)
        assertEquals(90.0, WEIGHT_RULER.jumpTarget(85.0, toward = 100.0), 0.0)
        // A tie on both counts goes up.
        assertEquals(30.0, WEIGHT_RULER.jumpTarget(25.0, toward = 25.0), 0.0)
    }

    @Test
    fun jumpTarget_repsGoToTheNearestFive() {
        assertEquals(10.0, REPS_RULER.jumpTarget(8.0, toward = 0.0), 0.0)
        assertEquals(5.0, REPS_RULER.jumpTarget(7.0, toward = 0.0), 0.0)
        assertEquals(5.0, REPS_RULER.jumpTarget(7.5, toward = 3.0), 0.0)
        assertEquals(10.0, REPS_RULER.jumpTarget(7.5, toward = 12.0), 0.0)
        assertEquals(100.0, REPS_RULER.jumpTarget(99.0, toward = 0.0), 0.0)
    }
}
