package io.github.kanylbullen.tung.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PauseTest {

    private val now = 1_761_840_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    @Test
    fun aWorkoutPausesAfterAnHourWithoutInput() {
        val start = now - 3 * hour
        assertNull(pausedSince(start, now - 10 * minute, now), "input ten minutes ago")
        assertNull(pausedSince(start, now - hour, now), "exactly an hour: still running")
        assertEquals(now - hour - 1, pausedSince(start, now - hour - 1, now))
        assertEquals(now - 2 * hour, pausedSince(start, now - 2 * hour, now))
    }

    @Test
    fun anEmptyWorkoutsInputIsItsStart() {
        val start = now - 90 * minute
        assertEquals(start, pausedSince(start, start - 5 * hour, now), "rows older than the start count as the start")
        assertNull(pausedSince(now - 30 * minute, 0L, now))
    }

    @Test
    fun anInputAheadOfTheClockCountsAsNow() {
        assertNull(pausedSince(now - 3 * hour, now + 10 * minute, now), "a device whose clock runs fast")
    }

    @Test
    fun aPausedWorkoutEndsAFewMinutesAfterItsLastInput() {
        assertEquals(now - 2 * hour + PAUSE_FINISH_MARGIN_MILLIS, pausedEnd(now - 2 * hour, now))
        assertEquals(now, pausedEnd(now - 2 * minute, now), "never after now")
    }

    @Test
    fun theLastInputIsTheLatestTime() {
        assertEquals(30L, lastInputAt(listOf(10L, 30L, 20L)))
        assertNull(lastInputAt(emptyList()))
    }

    @Test
    fun theFinishDurationOfAPausedWorkoutRunsToItsLastInput() {
        val start = now - 3 * hour
        val paused = now - 2 * hour
        assertEquals(hour + PAUSE_FINISH_MARGIN_MILLIS, finishDurationPreset(start, now, loggedAfterTheFact = false, pausedSinceMillis = paused))
        val forgotten = now - 30 * hour
        assertEquals(
            90 * minute + PAUSE_FINISH_MARGIN_MILLIS,
            finishDurationPreset(forgotten, now, loggedAfterTheFact = false, pausedSinceMillis = forgotten + 90 * minute),
            "forgotten overnight: the time it really lasted, not the hour's guess",
        )
        assertEquals(hour, finishDurationPreset(start, now, loggedAfterTheFact = true, pausedSinceMillis = paused), "logged after the fact keeps its hour")
    }
}
