package io.github.kanylbullen.tung.wear

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.OngoingStatus
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.ongoing.text
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Ongoing Activity's status, from the state the watch shows ([watchOngoing]). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class WatchOngoingTest {

    private fun set(id: String, done: Boolean, reps: Int = 8) = WatchSet(id, 0, 60.0, reps, done)

    private fun state(currentSetId: String?, targetSets: Int? = 4, startedBy: String? = null) = WatchWorkoutState(
        workoutId = "w",
        startedAt = 1_000,
        exercises = listOf(
            WatchExercise("we1", "Squat", listOf(set("s1", true), set("s2", true)), targetSets = 2),
            WatchExercise("we2", "Bench press", listOf(set("b1", true), set("b2", false), set("b3", false), set("b4", false)), targetSets = targetSets),
        ),
        currentSetId = currentSetId,
        updatedAt = 5_000,
        startedBy = startedBy,
    )

    @Test
    fun `no workout, no Ongoing Activity`() {
        assertNull(watchOngoing(null))
        assertNull(watchOngoing(WatchWorkoutState(updatedAt = 1)))
    }

    @Test
    fun `the state's current set counts against the routine target`() {
        val ongoing = watchOngoing(state(currentSetId = "b3"))
        assertEquals(OngoingStatus.AtSet("Bench press", 3, 4), ongoing?.status)
        assertEquals("w", ongoing?.workoutId)
        assertEquals(1_000L, ongoing?.startedAt)
    }

    @Test
    fun `without a current set in the state the default rule picks one`() {
        assertEquals(OngoingStatus.AtSet("Bench press", 2, 4), watchOngoing(state(currentSetId = null))?.status)
    }

    @Test
    fun `outside a routine it counts logged sets`() {
        val exercises = state(currentSetId = "b2").exercises.map { it.copy(targetSets = null) }
        assertEquals(OngoingStatus.Logged(3), watchOngoing(state(currentSetId = "b2").copy(exercises = exercises))?.status)
    }

    @Test
    fun `who started it is carried`() {
        assertEquals(StartedBy.AGENT, watchOngoing(state(currentSetId = null, startedBy = StartedBy.AGENT))?.startedBy)
    }

    @Test
    fun `the status text reads as the chip shows it`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("Bench press · set 3 of 4", watchOngoing(state(currentSetId = "b3"))?.status?.text(context))
        assertEquals("1 set logged", OngoingStatus.Logged(1).text(context))
        assertEquals("3 sets logged", OngoingStatus.Logged(3).text(context))
    }
}
