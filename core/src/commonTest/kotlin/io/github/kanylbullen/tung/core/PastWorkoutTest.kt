package io.github.kanylbullen.tung.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PastWorkoutTest {

    private val now = 1_761_840_000_000L
    private val hour = 60L * 60 * 1000

    @Test
    fun aWorkoutStartedMoreThanFourHoursAgoIsLoggedAfterTheFact() {
        assertFalse(isPastWorkout(now, now), "just started")
        assertFalse(isPastWorkout(now - 3 * hour, now), "a long session")
        assertFalse(isPastWorkout(now - 4 * hour, now), "exactly four hours")
        assertTrue(isPastWorkout(now - 4 * hour - 1, now))
        assertTrue(isPastWorkout(now - 24 * hour, now), "yesterday")
        assertFalse(isPastWorkout(now + 30_000L, now), "a start ahead of the clock")
    }

    @Test
    fun theFinishDurationOfAWorkoutLoggedAfterTheFactIsAnHourNeverPastNow() {
        assertEquals(hour, finishDurationPreset(now - 24 * hour, now, loggedAfterTheFact = true))
        assertEquals(40 * 60_000L, finishDurationPreset(now - 40 * 60_000L, now, loggedAfterTheFact = true), "started 40 minutes ago: at most until now")
        assertEquals(0L, finishDurationPreset(now + 30_000L, now, loggedAfterTheFact = true), "a start ahead of the clock")
    }

    @Test
    fun aLiveWorkoutPastFourHoursIsPresetToItsElapsedTimeUpToSixHours() {
        assertEquals(4 * hour + 20 * 60_000L, finishDurationPreset(now - 4 * hour - 20 * 60_000L, now, loggedAfterTheFact = false), "a long session")
        assertEquals(6 * hour, finishDurationPreset(now - 6 * hour, now, loggedAfterTheFact = false))
        assertEquals(hour, finishDurationPreset(now - 6 * hour - 1, now, loggedAfterTheFact = false), "forgotten since yesterday: an hour")
    }
}
