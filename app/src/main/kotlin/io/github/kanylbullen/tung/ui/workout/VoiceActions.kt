package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.nextCurrentAfterLogging
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.voice.VoiceIntent
import io.github.kanylbullen.tung.voice.SpeechErrorKind
import kotlin.math.roundToLong

/*
 * What a voice intent does on the active workout, decided from the screen's
 * state alone, without Android: the screen carries the action out through
 * the ViewModel's own functions (which run the usual commands), and shows the
 * echo. Nothing here writes.
 */

/** What [VoiceAction.Apply] does after writing the values. */
enum class AfterVoice {
    NOTHING,

    /** Log the set, as the Log set button does. */
    LOG,

    /** Logging was asked for but the set has no reps: open the reps sheet, as the button does. */
    ASK_FOR_REPS,
}

enum class VoiceProblem { NO_CURRENT_SET, NO_PREVIOUS_SET, NO_NEXT_SET, REST_OFF }

sealed interface VoiceAction {
    /** What the screen shows for a moment (see [voiceEchoText]). */
    val echo: VoiceEcho

    /**
     * [weightKg] and [reps] (null: unchanged) on the current [set]
     * (`SetWeight`, `SetReps`), then [after]. [result] is the set as it will be.
     */
    data class Apply(
        val exercise: ActiveExerciseUi,
        val set: ActiveSetUi,
        val weightKg: Double?,
        val reps: Int?,
        val after: AfterVoice,
    ) : VoiceAction {
        val result: LoggedSet get() = LoggedSet(weightKg ?: set.weightKg, reps ?: set.reps)
        override val echo: VoiceEcho get() = if (after == AfterVoice.LOG) VoiceEcho.Logged(result) else VoiceEcho.Values(result)
    }

    /** [set] becomes the current set, as a tick would move it, but nothing is logged. */
    data class Select(val exercise: ActiveExerciseUi, val set: ActiveSetUi) : VoiceAction {
        override val echo: VoiceEcho get() = VoiceEcho.Next(exercise.name, exercise.nameKey, set.number)
    }

    data class AddSet(val exercise: ActiveExerciseUi) : VoiceAction {
        override val echo: VoiceEcho get() = VoiceEcho.SetAdded(exercise.name, exercise.nameKey)
    }

    /** Opens the finish dialog, as the Finish button does. */
    data object Finish : VoiceAction {
        override val echo: VoiceEcho get() = VoiceEcho.FinishAsked
    }

    /** Starts [exercise]'s rest timer. */
    data class Rest(val exercise: ActiveExerciseUi) : VoiceAction {
        override val echo: VoiceEcho get() = VoiceEcho.RestStarted
    }

    /** Understood, but there is nothing to do it on. */
    data class Problem(val problem: VoiceProblem, val exercise: ActiveExerciseUi? = null) : VoiceAction {
        override val echo: VoiceEcho get() = VoiceEcho.Problem(problem, exercise?.name, exercise?.nameKey)
    }

    data class NotUnderstood(val text: String) : VoiceAction {
        override val echo: VoiceEcho get() = VoiceEcho.NotUnderstood(text)
    }
}

/** The line above the buttons after an utterance (or a failure to hear one). */
sealed interface VoiceEcho {
    /** Something changed: hands-free mode confirms it with a haptic. */
    val understood: Boolean get() = false

    data class Values(val set: LoggedSet) : VoiceEcho {
        override val understood get() = true
    }

    data class Logged(val set: LoggedSet) : VoiceEcho {
        override val understood get() = true
    }

    data class Next(val name: String, val nameKey: String?, val setNumber: Int) : VoiceEcho {
        override val understood get() = true
    }

    data class SetAdded(val name: String, val nameKey: String?) : VoiceEcho {
        override val understood get() = true
    }

    data object RestStarted : VoiceEcho {
        override val understood get() = true
    }

    /** The finish dialog is open; nothing is shown, and hands-free mode stops. */
    data object FinishAsked : VoiceEcho {
        override val understood get() = true
    }

    data class Problem(val problem: VoiceProblem, val name: String?, val nameKey: String?) : VoiceEcho

    data class NotUnderstood(val text: String) : VoiceEcho

    data class Failed(val kind: SpeechErrorKind) : VoiceEcho
}

/**
 * What [intent] does on [state]'s current set (the one Log set acts on).
 *
 * - Values: written to the current set; an adjustment is added to its value
 *   and clamped to 0–999.
 * - [VoiceIntent.Done]: logs it, or asks for reps when it has none, exactly
 *   as the button.
 * - [VoiceIntent.SameAgain]: the numbers of the set before it in the same
 *   exercise, then logged; with no set before it, nothing (it is not guessed
 *   from last session).
 * - [VoiceIntent.Next]: the set a tick would move to, without logging.
 * - [VoiceIntent.AddSet]: a set on the current set's exercise, else on the
 *   exercise last touched, else on the last one.
 * - [VoiceIntent.Rest]: the current exercise's rest timer; [VoiceIntent.Finish]: the finish dialog.
 */
fun voiceAction(intent: VoiceIntent, state: ActiveWorkoutUiState): VoiceAction {
    val current = state.current
    fun onCurrent(block: (CurrentSet) -> VoiceAction): VoiceAction =
        current?.let(block) ?: VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET)

    return when (intent) {
        is VoiceIntent.SetWeightAndReps -> onCurrent { c ->
            VoiceAction.Apply(c.exercise, c.set, intent.weightKg, intent.reps, AfterVoice.NOTHING)
        }
        is VoiceIntent.AdjustWeight -> onCurrent { c ->
            val kg = ((c.set.weightKg + intent.deltaKg).coerceIn(0.0, MAX_WEIGHT_KG) * 100).roundToLong() / 100.0
            VoiceAction.Apply(c.exercise, c.set, kg, null, AfterVoice.NOTHING)
        }
        is VoiceIntent.AdjustReps -> onCurrent { c ->
            VoiceAction.Apply(c.exercise, c.set, null, stepReps(c.set.reps, intent.delta), AfterVoice.NOTHING)
        }
        VoiceIntent.Done -> onCurrent { c ->
            VoiceAction.Apply(c.exercise, c.set, null, null, if (c.set.reps > 0) AfterVoice.LOG else AfterVoice.ASK_FOR_REPS)
        }
        VoiceIntent.SameAgain -> onCurrent { c ->
            val index = c.exercise.sets.indexOfFirst { it.id == c.set.id }
            val previous = c.exercise.sets.getOrNull(index - 1)
                ?: return@onCurrent VoiceAction.Problem(VoiceProblem.NO_PREVIOUS_SET, c.exercise)
            VoiceAction.Apply(c.exercise, c.set, previous.weightKg, previous.reps, if (previous.reps > 0) AfterVoice.LOG else AfterVoice.ASK_FOR_REPS)
        }
        VoiceIntent.Next -> onCurrent { c ->
            val at = state.indexOfSet(c.set.id) ?: return@onCurrent VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET)
            val skipped = state.completedMatrix().mapIndexed { e, sets ->
                sets.mapIndexed { s, done -> done || (e == at.exercise && s == at.set) }
            }
            val next = nextCurrentAfterLogging(skipped, at) ?: return@onCurrent VoiceAction.Problem(VoiceProblem.NO_NEXT_SET)
            val exercise = state.exercises[next.exercise]
            VoiceAction.Select(exercise, exercise.sets[next.set])
        }
        VoiceIntent.AddSet -> {
            val exercise = current?.exercise
                ?: state.exercises.firstOrNull { it.workoutExerciseId == state.lastTouchedExerciseId }
                ?: state.exercises.lastOrNull()
            exercise?.let { VoiceAction.AddSet(it) } ?: VoiceAction.Problem(VoiceProblem.NO_CURRENT_SET)
        }
        VoiceIntent.Rest -> onCurrent { c ->
            if (c.exercise.restSeconds > 0) VoiceAction.Rest(c.exercise) else VoiceAction.Problem(VoiceProblem.REST_OFF, c.exercise)
        }
        VoiceIntent.Finish -> VoiceAction.Finish
        is VoiceIntent.Unknown -> VoiceAction.NotUnderstood(intent.text)
    }
}
