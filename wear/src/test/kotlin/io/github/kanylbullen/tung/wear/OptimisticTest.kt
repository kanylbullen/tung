package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchLastSet
import io.github.kanylbullen.tung.core.wear.WatchRest
import io.github.kanylbullen.tung.core.wear.WatchRoutine
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticTest {

    private val state = WatchWorkoutState(
        workoutId = "w",
        startedAt = 0L,
        exercises = listOf(
            WatchExercise(
                "bench", "Bench press",
                listOf(WatchSet("b1", 0, 80.0, 8, false), WatchSet("b2", 1, 80.0, 8, false)),
                lastTime = listOf(WatchLastSet(77.5, 8)),
            ),
            WatchExercise("squat", "Back squat", listOf(WatchSet("s1", 0, 100.0, 5, false))),
        ),
        currentSetId = "b1",
        rest = WatchRest(endsAtMillis = 10_000L, durationMillis = 90_000L),
        routines = listOf(WatchRoutine("r", "Push", 2)),
        updatedAt = 1L,
    )

    @Test
    fun logSetTicksAndMovesWithinTheExercise() {
        val after = state.applying(WatchCommand.LogSet("b1", "c", 0L), 0L)
        assertTrue(after.set("b1")?.completed == true)
        assertEquals("b2", after.currentSetId)
    }

    @Test
    fun loggingTheLastSetLeavesTheNextToThePhone() {
        val after = state.applying(WatchCommand.LogSet("s1", "c", 0L), 0L)
        assertNull(after.currentSetId)
        assertNull(after.exercises[1].focusSet(after.currentSetId))
    }

    @Test
    fun valuesShowAtOnce() {
        val after = state
            .applying(WatchCommand.SetWeight("b2", 82.5, "c1", 0L), 0L)
            .applying(WatchCommand.SetReps("b2", 6, "c2", 0L), 0L)
        assertEquals(82.5, after.set("b2")?.weightKg ?: 0.0, 0.0)
        assertEquals(6, after.set("b2")?.reps)
        assertEquals(80.0, after.set("b1")?.weightKg ?: 0.0, 0.0)
    }

    @Test
    fun selectAndRest() {
        assertEquals("s1", state.applying(WatchCommand.SelectSet("s1", "c", 0L), 0L).currentSetId)
        assertNull(state.applying(WatchCommand.SkipRest("c", 0L), 0L).rest)
        assertEquals(40_000L, state.applying(WatchCommand.AddRest30("c", 0L), 5_000L).rest?.endsAtMillis)
    }

    @Test
    fun finishLeavesTheRoutines() {
        val after = state.applying(WatchCommand.FinishWorkout("c", 0L), 0L)
        assertFalse(after.isActive)
        assertEquals(state.routines, after.routines)
    }

    @Test
    fun startingWaitsForThePhone() {
        assertSame(state, state.applying(WatchCommand.StartWorkout("c", 0L), 0L))
    }

    @Test
    fun pageHelpers() {
        val bench = state.exercises[0]
        assertEquals("b1", bench.focusSet("b1")?.setId)
        // The current set is elsewhere: this page acts on its own first open set.
        assertEquals("s1", state.exercises[1].focusSet("b1")?.setId)
        assertEquals(77.5, bench.lastTimeFor(bench.sets[0])?.weightKg ?: 0.0, 0.0)
        assertNull(bench.lastTimeFor(bench.sets[1]))
        assertEquals(0, state.currentExerciseIndex())
        assertEquals(1, state.copy(currentSetId = "s1").currentExerciseIndex())
    }
}
