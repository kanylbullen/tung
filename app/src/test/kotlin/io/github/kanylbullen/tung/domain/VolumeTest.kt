package io.github.kanylbullen.tung.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeTest {

    @Test
    fun totalVolume_sumsWeightTimesReps() {
        val sets = listOf(LoggedSet(80.0, 8), LoggedSet(82.5, 6), LoggedSet(60.0, 10))
        assertEquals(80.0 * 8 + 82.5 * 6 + 60.0 * 10, totalVolume(sets), 0.0001)
    }

    @Test
    fun totalVolume_emptyIsZero() {
        assertEquals(0.0, totalVolume(emptyList()), 0.0)
    }

    @Test
    fun totalVolume_bodyweightAtZeroKgAddsNothing() {
        assertEquals(0.0, totalVolume(listOf(LoggedSet(0.0, 12))), 0.0)
    }

    @Test
    fun bestSet_picksHighestWeightTimesReps() {
        val sets = listOf(LoggedSet(100.0, 3), LoggedSet(80.0, 8), LoggedSet(90.0, 5))
        assertEquals(LoggedSet(80.0, 8), bestSet(sets))
    }

    @Test
    fun bestSet_tieGoesToHeavierSet() {
        val sets = listOf(LoggedSet(50.0, 10), LoggedSet(100.0, 5))
        assertEquals(LoggedSet(100.0, 5), bestSet(sets))
    }

    @Test
    fun bestSet_bodyweightFallsBackToMostReps() {
        val sets = listOf(LoggedSet(0.0, 8), LoggedSet(0.0, 12), LoggedSet(0.0, 10))
        assertEquals(LoggedSet(0.0, 12), bestSet(sets))
    }

    @Test
    fun bestSet_emptyIsNull() {
        assertNull(bestSet(emptyList()))
    }

    @Test
    fun isNewRecord_heavierThanAnythingBefore() {
        assertTrue(isNewRecord(listOf(LoggedSet(80.0, 5), LoggedSet(102.5, 1)), previousMaxWeightKg = 100.0))
    }

    @Test
    fun isNewRecord_equalIsNotARecord() {
        assertFalse(isNewRecord(listOf(LoggedSet(100.0, 5)), previousMaxWeightKg = 100.0))
    }

    @Test
    fun isNewRecord_lighterIsNotARecord() {
        assertFalse(isNewRecord(listOf(LoggedSet(90.0, 12)), previousMaxWeightKg = 100.0))
    }

    @Test
    fun isNewRecord_noHistoryIsNotARecord() {
        assertFalse(isNewRecord(listOf(LoggedSet(140.0, 1)), previousMaxWeightKg = null))
    }

    @Test
    fun isNewRecord_setWithoutRepsDoesNotCount() {
        assertFalse(isNewRecord(listOf(LoggedSet(200.0, 0)), previousMaxWeightKg = 100.0))
    }

    @Test
    fun isLogged_requiresTickAndReps() {
        assertTrue(isLogged(completed = true, reps = 5))
        assertFalse(isLogged(completed = false, reps = 5))
        assertFalse(isLogged(completed = true, reps = 0))
    }
}
