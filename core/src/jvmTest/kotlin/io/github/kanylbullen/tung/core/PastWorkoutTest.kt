package io.github.kanylbullen.tung.core

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
}
