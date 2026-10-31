package io.github.kanylbullen.tung.web.voice

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.nextCurrentAfterLogging
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.voice.VoiceIntent
import io.github.kanylbullen.tung.web.workout.ExerciseView
import io.github.kanylbullen.tung.web.workout.SetView
import io.github.kanylbullen.tung.web.workout.WorkoutView
import kotlin.math.roundToLong

/** Why an understood intent did nothing, the phone's `VoiceProblem`. */
enum class VoiceProblem { NO_CURRENT_SET, NO_PREVIOUS_SET, NO_NEXT_SET, REST_OFF }

/**
 * What a voice intent does on the workout, the phone's `voiceAction`: pure,
 * decided from the screen's state; the screen carries it out with the same
 * commands its buttons send.
 */
sealed interface VoiceAction {
    /** [weightKg] and [reps] (null: unchanged) on [set], then logged when [log], or the reps sheet when [askForReps]. */
    data class Apply(val exercise: ExerciseView, val set: SetView, val weightKg: Double?, val reps: Int?, val log: Boolean, val askForReps: Boolean) : VoiceAction {
        val result: LoggedSet get() = LoggedSet(weightKg ?: set.weightKg, reps ?: set.reps)
    }

    /** [set] becomes the current set, as a tick would move it; nothing is logged. */
    data class Select(val exercise: ExerciseView, val set: SetView) : VoiceAction
    data class AddSet(val exercise: ExerciseView) : VoiceAction
    data class Rest(val exercise: ExerciseView) : VoiceAction
    data object Finish : VoiceAction
    data class Problem(val problem: VoiceProblem, val exercise: ExerciseView? = null) : VoiceAction
    data class NotUnderstood(val text: String) : VoiceAction
}

/**
 * [intent] on [workout]'s [current] set (the one Log set acts on), with
 * [touched] the exercise last touched and [restSeconds] the rest an
 * exercise would start:
 *
 * - values go to the current set, an adjustment added and clamped to 0–999;
 * - Done logs it, or asks for reps when it has none, as the button;
 * - Same again copies the set before it in the same exercise and logs (with
 *   none before it, nothing: last session is not guessed);
 * - Next selects the set a tick would move to, without logging;
 * - Add set goes to the current set's exercise, else the touched one, else the last;
 * - Rest starts the current exercise's timer; Finish opens the finish dialog.
 */
fun voiceAction(intent: VoiceIntent, workout: WorkoutView, current: SetIndex?, touched: Int?, restSeconds: (ExerciseView) -> Int): VoiceAction {
    val exercise = current?.let { workout.exercises[it.exercise] }
    val set = current?.let { exercise?.sets?.get(it.set) }
    fun onCurrent(block: (ExerciseView, SetView) -> VoiceAction): VoiceAction =
        if (exercise != null && set != null) block(exercise, set) else VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET)

    return when (intent) {
        is VoiceIntent.SetWeightAndReps -> onCurrent { e, s -> VoiceAction.Apply(e, s, intent.weightKg, intent.reps, log = false, askForReps = false) }
        is VoiceIntent.AdjustWeight -> onCurrent { e, s ->
            val kg = ((s.weightKg + intent.deltaKg).coerceIn(0.0, MAX_WEIGHT_KG) * 100).roundToLong() / 100.0
            VoiceAction.Apply(e, s, kg, null, log = false, askForReps = false)
        }
        is VoiceIntent.AdjustReps -> onCurrent { e, s -> VoiceAction.Apply(e, s, null, stepReps(s.reps, intent.delta), log = false, askForReps = false) }
        VoiceIntent.Done -> onCurrent { e, s -> VoiceAction.Apply(e, s, null, null, log = s.reps > 0, askForReps = s.reps == 0) }
        VoiceIntent.SameAgain -> onCurrent { e, s ->
            val previous = e.sets.getOrNull(e.sets.indexOfFirst { it.id == s.id } - 1)
                ?: return@onCurrent VoiceAction.Problem(VoiceProblem.NO_PREVIOUS_SET, e)
            VoiceAction.Apply(e, s, previous.weightKg, previous.reps, log = previous.reps > 0, askForReps = previous.reps == 0)
        }
        VoiceIntent.Next -> onCurrent { _, _ ->
            val at = current ?: return@onCurrent VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET)
            val skipped = workout.completed.mapIndexed { e, sets -> sets.mapIndexed { s, done -> done || (e == at.exercise && s == at.set) } }
            val next = nextCurrentAfterLogging(skipped, at) ?: return@onCurrent VoiceAction.Problem(VoiceProblem.NO_NEXT_SET)
            val target = workout.exercises[next.exercise]
            VoiceAction.Select(target, target.sets[next.set])
        }
        VoiceIntent.AddSet -> (exercise ?: touched?.let { workout.exercises.getOrNull(it) } ?: workout.exercises.lastOrNull())
            ?.let { VoiceAction.AddSet(it) } ?: VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET)
        VoiceIntent.Rest -> onCurrent { e, _ -> if (restSeconds(e) > 0) VoiceAction.Rest(e) else VoiceAction.Problem(VoiceProblem.REST_OFF, e) }
        VoiceIntent.Finish -> VoiceAction.Finish
        is VoiceIntent.Unknown -> VoiceAction.NotUnderstood(intent.text)
    }
}
