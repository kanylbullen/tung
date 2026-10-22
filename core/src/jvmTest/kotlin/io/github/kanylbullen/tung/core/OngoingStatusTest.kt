package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Test

class OngoingStatusTest {
    private fun sets(vararg ticked: Boolean) = ticked.map { OngoingSet(it, if (it) 8 else 0) }

    @Test
    fun `the first un-ticked set counts against the routine target`() {
        val status = ongoingStatus(
            listOf(
                OngoingExercise("Squat", sets(true, true), targetSets = 2),
                OngoingExercise("Bench press", sets(true, true, false, false, false), targetSets = 5),
            ),
        )
        assertEquals(OngoingStatus.AtSet("Bench press", 3, 5), status)
    }

    @Test
    fun `without a routine target it counts logged sets`() {
        val status = ongoingStatus(listOf(OngoingExercise("Bench press", sets(true, true, false))))
        assertEquals(OngoingStatus.Logged(2), status)
    }

    @Test
    fun `a ticked set with no reps is not logged`() {
        val status = ongoingStatus(listOf(OngoingExercise("Plank", listOf(OngoingSet(true, 0), OngoingSet(true, 5), OngoingSet(false, 5)))))
        assertEquals(OngoingStatus.Logged(1), status)
    }

    @Test
    fun `everything ticked says all sets logged for the exercise Add set goes to`() {
        // Issue #64: logging adds no set, so with nothing left the notification offers Add set.
        val status = ongoingStatus(listOf(OngoingExercise("Deadlift", sets(true, true, true), targetSets = 3)))
        assertEquals(OngoingStatus.AllLogged("Deadlift"), status)
    }

    @Test
    fun `all sets logged names the last touched exercise, else the last one`() {
        val exercises = listOf(
            OngoingExercise("Squat", sets(true, true)),
            OngoingExercise("Bench press", sets(true)),
        )
        assertEquals(OngoingStatus.AllLogged("Squat"), ongoingStatus(exercises, lastTouchedExercise = 0))
        assertEquals(OngoingStatus.AllLogged("Bench press"), ongoingStatus(exercises))
        // With a set open the touched exercise changes nothing: that set is the notification's.
        val open = exercises + OngoingExercise("Row", sets(false))
        assertEquals(OngoingStatus.Logged(3), ongoingStatus(open, lastTouchedExercise = 0))
    }

    @Test
    fun `sets past the target count against the sets there are`() {
        val status = ongoingStatus(listOf(OngoingExercise("Row", sets(true, true, true, false), targetSets = 3)))
        assertEquals(OngoingStatus.AtSet("Row", 4, 4), status)
    }

    @Test
    fun `a known current set wins while it is un-ticked`() {
        val exercises = listOf(
            OngoingExercise("Squat", sets(false, false), targetSets = 2),
            OngoingExercise("Bench press", sets(false, false, false), targetSets = 3),
        )
        assertEquals(OngoingStatus.AtSet("Bench press", 2, 3), ongoingStatus(exercises, SetIndex(1, 1)))
        // A ticked or missing one falls back to the default rule.
        assertEquals(OngoingStatus.AtSet("Squat", 1, 2), ongoingStatus(exercises, SetIndex(4, 0)))
    }

    @Test
    fun `an empty workout has nothing logged`() {
        assertEquals(OngoingStatus.Logged(0), ongoingStatus(emptyList()))
    }
}
