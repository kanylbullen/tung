package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PastWorkoutTest {

    private val now = 1_761_840_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun aWorkoutStartedMoreThanFourHoursAgoIsLoggedAfterTheFact() {
        assertFalse("just started", isPastWorkout(now, now))
        assertFalse("a long session", isPastWorkout(now - 3 * hour, now))
        assertFalse("exactly four hours", isPastWorkout(now - 4 * hour, now))
        assertTrue(isPastWorkout(now - 4 * hour - 1, now))
        assertTrue("yesterday", isPastWorkout(now - 24 * hour, now))
        assertFalse("a start ahead of the clock", isPastWorkout(now + 30_000L, now))
    }

    @Test
    fun theFinishDurationOfAWorkoutLoggedAfterTheFactIsAnHourNeverPastNow() {
        assertEquals(hour, finishDurationPreset(now - 24 * hour, now, loggedAfterTheFact = true))
        assertEquals("started 40 minutes ago: at most until now", 40 * 60_000L, finishDurationPreset(now - 40 * 60_000L, now, loggedAfterTheFact = true))
        assertEquals("a start ahead of the clock", 0L, finishDurationPreset(now + 30_000L, now, loggedAfterTheFact = true))
    }

    @Test
    fun aLiveWorkoutPastFourHoursIsPresetToItsElapsedTimeUpToSixHours() {
        assertEquals("a long session", 4 * hour + 20 * 60_000L, finishDurationPreset(now - 4 * hour - 20 * 60_000L, now, loggedAfterTheFact = false))
        assertEquals(6 * hour, finishDurationPreset(now - 6 * hour, now, loggedAfterTheFact = false))
        assertEquals("forgotten since yesterday: an hour", hour, finishDurationPreset(now - 6 * hour - 1, now, loggedAfterTheFact = false))
    }
}
