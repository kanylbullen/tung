package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RoutinesTest {

    @Test
    fun mostCommonRepsPicksTheMostFrequentCount() {
        assertEquals(8, mostCommonReps(listOf(10, 8, 8, 6)))
    }

    @Test
    fun mostCommonRepsTieGoesToTheFirstCount() {
        assertEquals(8, mostCommonReps(listOf(8, 6, 6, 8)))
        assertEquals(5, mostCommonReps(listOf(5, 3, 1)))
    }

    @Test
    fun mostCommonRepsIgnoresEmptySetsAndIsNullWithNothing() {
        assertEquals(12, mostCommonReps(listOf(0, 0, 12)))
        assertNull(mostCommonReps(emptyList()))
        assertNull(mostCommonReps(listOf(0)))
    }

    @Test
    fun targetIsOneSetPerLoggedSetAimingAtTheCommonReps() {
        val target = routineTarget(listOf(LoggedSet(80.0, 8), LoggedSet(80.0, 8), LoggedSet(85.0, 6)))
        assertEquals(RoutineTarget(sets = 3, reps = 8), target)
    }

    @Test
    fun targetDoesNotCountPlaceholders() {
        assertEquals(RoutineTarget(sets = 1, reps = 5), routineTarget(listOf(LoggedSet(100.0, 5), LoggedSet(100.0, 0))))
        assertEquals(RoutineTarget(sets = 0, reps = null), routineTarget(emptyList()))
    }

    @Test
    fun targetSetsAreClamped() {
        assertEquals(MIN_TARGET_SETS, clampTargetSets(0))
        assertEquals(4, clampTargetSets(4))
        assertEquals(MAX_TARGET_SETS, clampTargetSets(MAX_TARGET_SETS + 1))
    }

    @Test
    fun startSetsFollowLastSessionPositionByPosition() {
        val last = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(90.0, 4))
        assertEquals(last, routineStartSets(targetSets = 3, targetReps = 8, previousSession = last))
    }

    @Test
    fun startSetsBeyondLastSessionRepeatTheSetBefore() {
        val last = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6))
        assertEquals(
            listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(85.0, 6)),
            routineStartSets(targetSets = 3, targetReps = 8, previousSession = last),
        )
    }

    @Test
    fun startSetsFewerThanLastSessionTakeTheFirstOnes() {
        val last = listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 6), LoggedSet(90.0, 4))
        assertEquals(last.take(2), routineStartSets(targetSets = 2, targetReps = 8, previousSession = last))
    }

    @Test
    fun startSetsWithoutHistoryUseTargetRepsAtZeroKilos() {
        assertEquals(
            listOf(LoggedSet(0.0, 10), LoggedSet(0.0, 10)),
            routineStartSets(targetSets = 2, targetReps = 10, previousSession = emptyList()),
        )
        assertEquals(listOf(LoggedSet(0.0, 0)), routineStartSets(targetSets = 1, targetReps = null, previousSession = emptyList()))
        assertEquals(emptyList<LoggedSet>(), routineStartSets(targetSets = 0, targetReps = 5, previousSession = emptyList()))
    }

    @Test
    fun daysAgoCountsCalendarDaysNotHours() {
        val zone = ZoneId.of("Europe/Stockholm")
        fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(0, daysAgo(at(19, 7), at(19, 22), zone))
        assertEquals(1, daysAgo(at(18, 23), at(19, 1), zone))
        assertEquals(3, daysAgo(at(16, 12), at(19, 9), zone))
        assertEquals(0, daysAgo(at(20, 9), at(19, 9), zone))
    }
}
