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
    fun anExplicitSelectionWins() {
        val sets = listOf(listOf(false, false), listOf(false, false))
        assertEquals(SetIndex(0, 1), currentSet(sets, selected = SetIndex(0, 1), lastTouchedExercise = 1))
        assertEquals(SetIndex(1, 1), currentSet(sets, selected = SetIndex(1, 1), lastTouchedExercise = null))
    }

    @Test
    fun aTickedOrMissingSelectionFallsBackToTheTouchedExercise() {
        val sets = listOf(listOf(true, false), listOf(false))
        assertEquals(SetIndex(1, 0), currentSet(sets, selected = SetIndex(0, 0), lastTouchedExercise = 1))
        assertEquals(SetIndex(1, 0), currentSet(sets, selected = SetIndex(0, 5), lastTouchedExercise = 1))
        assertEquals(SetIndex(1, 0), currentSet(sets, selected = SetIndex(4, 0), lastTouchedExercise = 1))
        assertEquals(SetIndex(0, 1), currentSet(sets, selected = SetIndex(0, 0), lastTouchedExercise = null))
    }

    @Test
    fun loggingStaysInTheExerciseWhileItHasOpenSets() {
        // Set 1 of three just logged; set 2 is next.
        assertEquals(SetIndex(1, 1), nextCurrentAfterLogging(listOf(listOf(false), listOf(true, false, false)), SetIndex(1, 0)))
        // The last set logged and the next one added after it.
        assertEquals(SetIndex(0, 2), nextCurrentAfterLogging(listOf(listOf(true, true, false), listOf(false)), SetIndex(0, 1)))
        // A skipped earlier set of the same exercise comes before the next exercise.
        assertEquals(SetIndex(0, 0), nextCurrentAfterLogging(listOf(listOf(false, true), listOf(false)), SetIndex(0, 1)))
    }

    @Test
    fun loggingTheLastOpenSetMovesToTheNextExerciseInListOrder() {
        val sets = listOf(listOf(false), listOf(true, true), listOf(true, false))
        // Not the first exercise with an open set (0), but the next one after it (2).
        assertEquals(SetIndex(2, 1), nextCurrentAfterLogging(sets, SetIndex(1, 1)))
    }

    @Test
    fun loggingTheLastOpenSetWrapsToTheStart() {
        val sets = listOf(listOf(true, false), listOf(true), listOf(true, true))
        assertEquals(SetIndex(0, 1), nextCurrentAfterLogging(sets, SetIndex(2, 1)))
    }

    @Test
    fun loggingTheVeryLastOpenSetLeavesNoCurrentSet() {
        assertNull(nextCurrentAfterLogging(listOf(listOf(true), listOf(true, true)), SetIndex(1, 1)))
        assertNull(nextCurrentAfterLogging(emptyList(), SetIndex(0, 0)))
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

    // --- stickyAction (issue #64) ---

    @Test
    fun stickyActionIsLogSetWhileACurrentSetExists() {
        val sets = listOf(listOf(true, false), listOf(false))
        assertEquals(StickyAction.LogSet(SetIndex(1, 0)), stickyAction(sets, lastTouchedExercise = 1))
        assertEquals(StickyAction.LogSet(SetIndex(0, 1)), stickyAction(sets, selected = SetIndex(0, 1), lastTouchedExercise = 1))
    }

    @Test
    fun withEverySetTickedItAddsACopyOfTheTouchedExercisesLastSet() {
        val sets = listOf(listOf(true, true, true), listOf(true, true))
        assertEquals(StickyAction.AddSet(exercise = 0, copyOf = 2), stickyAction(sets, lastTouchedExercise = 0))
    }

    @Test
    fun withNothingTouchedItAddsToTheLastExercise() {
        val sets = listOf(listOf(true), listOf(true, true))
        assertEquals(StickyAction.AddSet(exercise = 1, copyOf = 1), stickyAction(sets))
        // A touched index out of range counts as nothing touched.
        assertEquals(StickyAction.AddSet(exercise = 1, copyOf = 1), stickyAction(sets, lastTouchedExercise = 5))
    }

    @Test
    fun anExerciseWithNoSetsHasNothingToCopy() {
        assertEquals(StickyAction.AddSet(exercise = 0, copyOf = null), stickyAction(listOf(emptyList())))
        assertEquals(StickyAction.AddSet(exercise = 1, copyOf = null), stickyAction(listOf(listOf(true), emptyList())))
    }

    @Test
    fun anEmptyWorkoutHasNoStickyAction() {
        assertNull(stickyAction(emptyList()))
    }

    @Test
    fun loggingTheLastSetTurnsLogSetIntoAddSetForThatExercise() {
        // Bench press (0) and squat (1); the squat's last set was just logged, so it is the touched exercise.
        val before = listOf(listOf(true, true), listOf(true, false))
        assertEquals(StickyAction.LogSet(SetIndex(1, 1)), stickyAction(before, lastTouchedExercise = 1))
        val after = listOf(listOf(true, true), listOf(true, true))
        assertNull(nextCurrentAfterLogging(after, SetIndex(1, 1)))
        assertEquals(StickyAction.AddSet(exercise = 1, copyOf = 1), stickyAction(after, lastTouchedExercise = 1))
        // The added set is the exercise's only open one, so it is current and the button is Log set again.
        val added = listOf(listOf(true, true), listOf(true, true, false))
        assertEquals(StickyAction.LogSet(SetIndex(1, 2)), stickyAction(added, lastTouchedExercise = 1))
    }
}
