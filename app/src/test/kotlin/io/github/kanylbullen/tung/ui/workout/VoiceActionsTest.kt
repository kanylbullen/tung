package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.voice.VoiceIntent
import io.github.kanylbullen.tung.voice.SpeechErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The intent → action mapping on plain UI state; no Android, no Robolectric. */
class VoiceActionsTest {

    private fun set(id: String, number: Int, kg: Double, reps: Int, done: Boolean = false) =
        ActiveSetUi(id, number, kg, reps, done, previous = null)

    private fun exercise(id: String, vararg sets: ActiveSetUi, rest: Int = 90) =
        ActiveExerciseUi(id, "ex-$id", "Exercise $id", nameKey = null, sets = sets.toList(), restSeconds = rest)

    private val bench = exercise("a", set("a1", 1, 80.0, 8, done = true), set("a2", 2, 80.0, 0), set("a3", 3, 0.0, 0))
    private val squat = exercise("b", set("b1", 1, 100.0, 5))
    private val state = ActiveWorkoutUiState(loading = false, exercises = listOf(bench, squat))

    private fun act(intent: VoiceIntent, on: ActiveWorkoutUiState = state) = voiceAction(intent, on)

    @Test
    fun valuesGoOnTheCurrentSet() {
        val a = act(VoiceIntent.SetWeightAndReps(82.5, 6)) as VoiceAction.Apply
        assertEquals("a2", a.set.id)
        assertEquals(82.5, a.weightKg)
        assertEquals(6, a.reps)
        assertEquals(AfterVoice.NOTHING, a.after)
        assertEquals(VoiceEcho.Values(LoggedSet(82.5, 6)), a.echo)

        val repsOnly = act(VoiceIntent.SetWeightAndReps(null, 8)) as VoiceAction.Apply
        assertEquals(null, repsOnly.weightKg)
        assertEquals(LoggedSet(80.0, 8), repsOnly.result)
    }

    @Test
    fun adjustmentsAreAddedAndClamped() {
        assertEquals(85.0, (act(VoiceIntent.AdjustWeight(5.0)) as VoiceAction.Apply).weightKg)
        assertEquals(82.5, (act(VoiceIntent.AdjustWeight(2.5)) as VoiceAction.Apply).weightKg)
        assertEquals(0.0, (act(VoiceIntent.AdjustWeight(-100.0)) as VoiceAction.Apply).weightKg)
        val heavy = state.copy(exercises = listOf(exercise("c", set("c1", 1, 997.5, 1))))
        assertEquals(999.0, (act(VoiceIntent.AdjustWeight(5.0), heavy) as VoiceAction.Apply).weightKg)
        assertEquals(2, (act(VoiceIntent.AdjustReps(2)) as VoiceAction.Apply).reps)
        assertEquals(0, (act(VoiceIntent.AdjustReps(-5)) as VoiceAction.Apply).reps)
    }

    @Test
    fun doneLogsLikeTheButtonAndAsksForRepsWithoutThem() {
        val noReps = act(VoiceIntent.Done) as VoiceAction.Apply
        assertEquals(AfterVoice.ASK_FOR_REPS, noReps.after)
        val withReps = state.copy(exercises = listOf(squat))
        val logged = act(VoiceIntent.Done, withReps) as VoiceAction.Apply
        assertEquals(AfterVoice.LOG, logged.after)
        assertEquals(null, logged.weightKg)
        assertEquals(VoiceEcho.Logged(LoggedSet(100.0, 5)), logged.echo)
        assertTrue(logged.echo.understood)
    }

    @Test
    fun sameAgainCopiesTheSetBeforeAndLogs() {
        val a = act(VoiceIntent.SameAgain) as VoiceAction.Apply
        assertEquals("a2", a.set.id)
        assertEquals(80.0, a.weightKg)
        assertEquals(8, a.reps)
        assertEquals(AfterVoice.LOG, a.after)

        // The first set of an exercise has nothing before it: nothing is guessed from last time.
        val first = act(VoiceIntent.SameAgain, state.copy(exercises = listOf(squat)))
        assertEquals(VoiceAction.Problem(VoiceProblem.NO_PREVIOUS_SET, squat), first)
        assertFalse(first.echo.understood)
    }

    @Test
    fun nextMovesLikeATickWithoutLogging() {
        assertEquals(VoiceAction.Select(bench, bench.sets[2]), act(VoiceIntent.Next))
        // A set chosen by hand: the next is the first open one of its exercise, as after a tick.
        assertEquals(VoiceAction.Select(bench, bench.sets[1]), act(VoiceIntent.Next, state.copy(selectedSetId = "a3")))
        // From the last open set of an exercise to the next exercise's first.
        val oneLeft = exercise("a", set("a1", 1, 80.0, 8, done = true), set("a2", 2, 80.0, 8))
        assertEquals(VoiceAction.Select(squat, squat.sets[0]), act(VoiceIntent.Next, state.copy(exercises = listOf(oneLeft, squat))))
        // Wrapping round to the first exercise with an open set.
        val onSquat = state.copy(selectedSetId = "b1")
        assertEquals(VoiceAction.Select(bench, bench.sets[1]), act(VoiceIntent.Next, onSquat))
        // One open set: nowhere to go.
        assertEquals(VoiceAction.Problem(VoiceProblem.NO_NEXT_SET), act(VoiceIntent.Next, state.copy(exercises = listOf(squat))))
    }

    @Test
    fun addSetRestFinishAndUnknown() {
        assertEquals(VoiceAction.AddSet(bench), act(VoiceIntent.AddSet))
        val allDone = state.copy(exercises = listOf(exercise("d", set("d1", 1, 50.0, 5, done = true)), exercise("e", set("e1", 1, 20.0, 10, done = true))))
        assertEquals("e", (act(VoiceIntent.AddSet, allDone) as VoiceAction.AddSet).exercise.workoutExerciseId)
        assertEquals("d", (act(VoiceIntent.AddSet, allDone.copy(lastTouchedExerciseId = "d")) as VoiceAction.AddSet).exercise.workoutExerciseId)
        assertEquals(VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET), act(VoiceIntent.AddSet, ActiveWorkoutUiState(loading = false)))

        assertEquals(VoiceAction.Rest(bench), act(VoiceIntent.Rest))
        val noRest = state.copy(exercises = listOf(exercise("f", set("f1", 1, 0.0, 0), rest = 0)))
        assertEquals(VoiceProblem.REST_OFF, (act(VoiceIntent.Rest, noRest) as VoiceAction.Problem).problem)

        assertEquals(VoiceAction.Finish, act(VoiceIntent.Finish, allDone))
        assertEquals(VoiceEcho.FinishAsked, VoiceAction.Finish.echo)
        assertEquals(VoiceAction.NotUnderstood("eighty something"), act(VoiceIntent.Unknown("eighty something")))
    }

    @Test
    fun withoutACurrentSetNothingIsChanged() {
        val allDone = state.copy(exercises = listOf(exercise("d", set("d1", 1, 50.0, 5, done = true))))
        for (intent in listOf(VoiceIntent.SetWeightAndReps(80.0, 8), VoiceIntent.AdjustWeight(5.0), VoiceIntent.Done, VoiceIntent.SameAgain, VoiceIntent.Next, VoiceIntent.Rest)) {
            assertEquals("$intent", VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET), act(intent, allDone))
        }
    }

    @Test
    fun onlyChangesCountAsUnderstood() {
        assertTrue(VoiceEcho.Values(LoggedSet(1.0, 1)).understood)
        assertTrue(VoiceEcho.RestStarted.understood)
        assertFalse(VoiceEcho.NotUnderstood("x").understood)
        assertFalse(VoiceEcho.Failed(SpeechErrorKind.NO_SPEECH).understood)
        assertFalse(VoiceEcho.Problem(VoiceProblem.NO_NEXT_SET, null, null).understood)
    }
}
