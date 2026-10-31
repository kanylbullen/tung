package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.voice.VoiceIntent
import io.github.kanylbullen.tung.web.voice.VoiceAction
import io.github.kanylbullen.tung.web.voice.VoiceProblem
import io.github.kanylbullen.tung.web.voice.voiceAction
import io.github.kanylbullen.tung.web.workout.ExerciseView
import io.github.kanylbullen.tung.web.workout.SetView
import io.github.kanylbullen.tung.web.workout.WorkoutView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class VoiceModelTest {

    private val bench = ExerciseView("we1", "bench", "Bench press", listOf(SetView("s1", 1, 80.0, 8, true), SetView("s2", 2, 80.0, 0, false), SetView("s3", 3, 80.0, 6, false)), emptyList(), restSeconds = 0)
    private val curl = ExerciseView("we2", "curl", "Curl", listOf(SetView("s4", 1, 12.0, 10, false)), emptyList())
    private val workout = WorkoutView("w", 0, null, listOf(bench, curl))
    private val current = SetIndex(0, 1)

    private fun act(intent: VoiceIntent, at: SetIndex? = current) = voiceAction(intent, workout, at, null) { it.restSeconds ?: 90 }

    @Test
    fun valuesGoToTheCurrentSetAndAdjustmentsAreClamped() {
        val apply = assertIs<VoiceAction.Apply>(act(VoiceIntent.SetWeightAndReps(82.5, 5)))
        assertEquals("s2", apply.set.id)
        assertEquals(false, apply.log)
        assertEquals(0.0, assertIs<VoiceAction.Apply>(act(VoiceIntent.AdjustWeight(-100.0))).weightKg)
    }

    @Test
    fun doneWithoutRepsAsksForThemAndSameAgainCopiesAndLogs() {
        assertEquals(true, assertIs<VoiceAction.Apply>(act(VoiceIntent.Done)).askForReps)
        val same = assertIs<VoiceAction.Apply>(act(VoiceIntent.SameAgain))
        assertEquals(80.0 to 8, same.weightKg to same.reps)
        assertEquals(true, same.log)
        assertEquals(VoiceProblem.NO_PREVIOUS_SET, assertIs<VoiceAction.Problem>(act(VoiceIntent.SameAgain, SetIndex(1, 0))).problem)
    }

    @Test
    fun nextAddSetRestAndNoCurrentSet() {
        assertEquals("s3", assertIs<VoiceAction.Select>(act(VoiceIntent.Next)).set.id)
        assertEquals("we1", assertIs<VoiceAction.AddSet>(act(VoiceIntent.AddSet)).exercise.workoutExerciseId)
        assertEquals(VoiceProblem.REST_OFF, assertIs<VoiceAction.Problem>(act(VoiceIntent.Rest)).problem)
        assertEquals(VoiceProblem.NO_CURRENT_SET, assertIs<VoiceAction.Problem>(act(VoiceIntent.Done, null)).problem)
        assertIs<VoiceAction.NotUnderstood>(act(VoiceIntent.Unknown("bench then curls")))
    }
}
