package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseHistoryTest {

    private fun set(workout: String, row: String, at: Long, exPos: Int, pos: Int, kg: Double, reps: Int) =
        HistorySet(workout, row, at, exPos, pos, LoggedSet(kg, reps))

    @Test
    fun nothingLoggedIsEmpty() {
        val history = exerciseHistory(emptyList())
        assertTrue(history.isEmpty)
        assertNull(history.best)
        assertNull(history.lastSessionAt)
    }

    @Test
    fun sessionsLastSessionAndBestSet() {
        val sets = listOf(
            // Oldest workout: the best set.
            set("w1", "we1", 1_000, 0, 0, 100.0, 5),
            set("w1", "we1", 1_000, 0, 1, 90.0, 8),
            // Latest workout, sets given out of order.
            set("w2", "we2", 2_000, 1, 1, 80.0, 6),
            set("w2", "we2", 2_000, 1, 0, 80.0, 8),
        )
        val history = exerciseHistory(sets.shuffled(java.util.Random(4)))
        assertEquals(2, history.sessions)
        assertEquals(2_000L, history.lastSessionAt)
        assertEquals(listOf(LoggedSet(80.0, 8), LoggedSet(80.0, 6)), history.lastSession)
        // 90 × 8 = 720 beats 100 × 5 = 500: best set is weight × reps, as everywhere else.
        assertEquals(LoggedSet(90.0, 8), history.best)
    }

    /** Twice in one workout: last session is its first appearance, as previousSessionSets picks it. */
    @Test
    fun anExerciseTwiceInAWorkoutCountsOnceAndShowsItsFirstRow() {
        val sets = listOf(
            set("w1", "late", 1_000, 3, 0, 60.0, 10),
            set("w1", "early", 1_000, 0, 0, 70.0, 5),
        )
        val history = exerciseHistory(sets)
        assertEquals(1, history.sessions)
        assertEquals(listOf(LoggedSet(70.0, 5)), history.lastSession)
    }

    @Test
    fun setsWithoutRepsDoNotCount() {
        val history = exerciseHistory(listOf(set("w1", "we1", 1_000, 0, 0, 200.0, 0)))
        assertTrue(history.isEmpty)
    }
}
