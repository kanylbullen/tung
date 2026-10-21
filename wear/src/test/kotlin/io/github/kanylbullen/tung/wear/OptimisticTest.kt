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
        assertNull("bench still has open sets: no Add set", after.addSetWorkoutExerciseId)
        assertNull(after.exercises[1].focusSet(after.currentSetId))
    }

    @Test
    fun loggingTheWorkoutsLastOpenSetOffersAddSetAtOnce() {
        val oneLeft = state.copy(
            exercises = state.exercises.map { e -> e.copy(sets = e.sets.map { if (it.setId == "s1") it else it.copy(completed = true) }) },
            currentSetId = "s1",
        )
        val after = oneLeft.applying(WatchCommand.LogSet("s1", "c", 0L), 0L)
        assertNull(after.currentSetId)
        assertEquals("squat", after.addSetWorkoutExerciseId)
        assertEquals(WatchLogButton.AddSet("squat"), after.logButton(after.exercises[1]))
        assertNull("Add set is offered on the logged exercise's page only", after.logButton(after.exercises[0]))
        assertEquals(1, after.focusExerciseIndex())
        // The new set's id is the phone's: Add set waits for its state.
        assertSame(after, after.applying(WatchCommand.AddSet("squat", "c2", 0L), 0L))
    }

    @Test
    fun logButtonStates() {
        assertEquals(WatchLogButton.LogSet("b1", needsReps = false), state.logButton(state.exercises[0]))
        assertEquals(WatchLogButton.LogSet("s1", needsReps = false), state.logButton(state.exercises[1]))
        val noReps = state.copy(exercises = state.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(reps = 0) }) })
        assertEquals(WatchLogButton.LogSet("b1", needsReps = true), noReps.logButton(noReps.exercises[0]))
        // An exercise with nothing open while another has a set: no button, as before.
        val benchDone = state.copy(
            exercises = state.exercises.map { e -> if (e.workoutExerciseId == "bench") e.copy(sets = e.sets.map { it.copy(completed = true) }) else e },
            currentSetId = "s1",
            addSetWorkoutExerciseId = null,
        )
        assertNull(benchDone.logButton(benchDone.exercises[0]))
        // An older phone never offers Add set: all logged, no button.
        val allDone = benchDone.copy(exercises = benchDone.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(completed = true) }) }, currentSetId = null)
        assertNull(allDone.logButton(allDone.exercises[1]))
        assertEquals(WatchLogButton.AddSet("bench"), allDone.copy(addSetWorkoutExerciseId = "bench").logButton(allDone.exercises[0]))
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
