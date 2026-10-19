package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestTimerTest {

    private val t0 = 1_700_000_000_000L

    private fun started(seconds: Int): RestTimer = checkNotNull(RestTimer.start(t0, seconds))

    @Test
    fun startEndsAfterTheDuration() {
        val timer = RestTimer.start(t0, 90)
        assertEquals(RestTimer(endsAtMillis = t0 + 90_000, durationMillis = 90_000), timer)
    }

    @Test
    fun zeroSecondsIsOff() {
        assertNull(RestTimer.start(t0, 0))
        assertNull(RestTimer.start(t0, -30))
    }

    @Test
    fun remainingComesFromTheClock() {
        val timer = started(90)
        assertEquals(90_000, timer.remainingAt(t0))
        assertEquals(60_000, timer.remainingAt(t0 + 30_000))
        assertEquals(1, timer.remainingAt(t0 + 89_999))
    }

    @Test
    fun remainingIsNeverNegative() {
        val timer = started(90)
        assertEquals(0, timer.remainingAt(t0 + 90_000))
        assertEquals(0, timer.remainingAt(t0 + 10 * 60_000))
    }

    @Test
    fun runningUntilTheEndThenFinished() {
        val timer = started(30)
        assertTrue(timer.isRunningAt(t0 + 29_999))
        assertFalse(timer.isFinishedAt(t0 + 29_999))
        assertFalse(timer.isRunningAt(t0 + 30_000))
        assertTrue(timer.isFinishedAt(t0 + 30_000))
    }

    @Test
    fun plusExtendsARunningTimer() {
        val timer = started(90).plus(t0 + 10_000)
        assertEquals(t0 + 120_000, timer.endsAtMillis)
        assertEquals(120_000, timer.durationMillis)
        assertEquals(110_000, timer.remainingAt(t0 + 10_000))
    }

    @Test
    fun plusOnAFinishedTimerStartsOverFromNow() {
        val timer = started(90).plus(t0 + 100_000)
        assertEquals(RestTimer(t0 + 130_000, 30_000), timer)
    }

    @Test
    fun skipLeavesNoTimer() {
        assertNull(started(90).skip())
    }

    @Test
    fun exerciseSettingWinsOverTheDefault() {
        assertEquals(60, restSecondsFor(60, DEFAULT_REST_SECONDS))
        assertEquals(0, restSecondsFor(0, DEFAULT_REST_SECONDS))
        assertEquals(DEFAULT_REST_SECONDS, restSecondsFor(null, DEFAULT_REST_SECONDS))
        assertEquals(120, restSecondsFor(null, 120))
    }

    @Test
    fun restSettingIsKeptWithinRange() {
        assertEquals(MAX_REST_SECONDS, restSecondsFor(900, DEFAULT_REST_SECONDS))
        assertEquals(0, restSecondsFor(-5, DEFAULT_REST_SECONDS))
    }

    @Test
    fun stepsAreThirtySecondsBetweenOffAndFiveMinutes() {
        assertEquals(120, stepRestSeconds(90, 1))
        assertEquals(60, stepRestSeconds(90, -1))
        assertEquals(0, stepRestSeconds(30, -1))
        assertEquals(0, stepRestSeconds(0, -1))
        assertEquals(300, stepRestSeconds(300, 1))
    }
}
