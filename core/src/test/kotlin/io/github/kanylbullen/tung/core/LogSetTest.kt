package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSetTest {

    @Test
    fun firstUntickedSetOfTheLastTouchedExercise() {
        val sets = listOf(listOf(false, false), listOf(true, false, false))
        assertEquals(SetIndex(1, 1), currentSet(sets, lastTouchedExercise = 1))
    }

    @Test
    fun withNothingTouchedTheFirstExerciseWithAnUntickedSet() {
        val sets = listOf(listOf(true, true), listOf(true, false))
        assertEquals(SetIndex(1, 1), currentSet(sets, lastTouchedExercise = null))
    }

    @Test
    fun aFinishedTouchedExerciseMovesOnToTheFirstOpenOne() {
        val sets = listOf(listOf(true, true), listOf(true, true, true), listOf(false))
        assertEquals(SetIndex(2, 0), currentSet(sets, lastTouchedExercise = 1))
    }

    @Test
    fun aSkippedEarlierSetIsTheCurrentOneWithinItsExercise() {
        val sets = listOf(listOf(false, true, false))
        assertEquals(SetIndex(0, 0), currentSet(sets, lastTouchedExercise = 0))
    }

    @Test
    fun everythingTickedHasNoCurrentSet() {
        assertNull(currentSet(listOf(listOf(true), listOf(true, true)), lastTouchedExercise = 0))
        assertNull(currentSet(emptyList(), lastTouchedExercise = null))
        assertNull(currentSet(listOf(emptyList()), lastTouchedExercise = 0))
    }

    @Test
    fun anOutOfRangeTouchedExerciseIsIgnored() {
        assertEquals(SetIndex(0, 0), currentSet(listOf(listOf(false)), lastTouchedExercise = 5))
    }

    @Test
    fun theLastSetOfAFreeExerciseAddsTheNext() {
        assertTrue(addsNextSet(wasLast = true, setCount = 1, targetSets = null))
        assertTrue(addsNextSet(wasLast = true, setCount = 7, targetSets = null))
    }

    @Test
    fun aSetBeforeTheLastAddsNothing() {
        assertFalse(addsNextSet(wasLast = false, setCount = 3, targetSets = null))
        assertFalse(addsNextSet(wasLast = false, setCount = 3, targetSets = 3))
    }

    @Test
    fun aRoutineExerciseIsNotPaddedPastItsTarget() {
        assertFalse(addsNextSet(wasLast = true, setCount = 3, targetSets = 3))
        assertFalse(addsNextSet(wasLast = true, setCount = 4, targetSets = 3))
    }

    @Test
    fun aRoutineExerciseWithASetDeletedIsFilledBackUpToItsTarget() {
        assertTrue(addsNextSet(wasLast = true, setCount = 2, targetSets = 3))
    }
}
