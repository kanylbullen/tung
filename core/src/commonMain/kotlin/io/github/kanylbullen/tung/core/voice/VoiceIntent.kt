package io.github.kanylbullen.tung.core.voice

/** The languages the grammar understands: the app's two. */
enum class VoiceLanguage(val tag: String) {
    SWEDISH("sv"),
    ENGLISH("en");

    companion object {
        /** A BCP 47 tag ("sv", "sv-SE", "en-GB"): Swedish for any `sv`, else English, the app's default. */
        fun of(tag: String): VoiceLanguage {
            val language = tag.lowercase().substringBefore('-').substringBefore('_')
            return if (language == "sv") SWEDISH else ENGLISH
        }
    }
}

/**
 * What one utterance asks of the current set, before anything is written.
 * The app maps it onto the existing commands (`SetWeight`, `SetReps`,
 * `LogSet`, `AddSet`, `FinishWorkout`) and the screen's own view state
 * (selection, rest timer); it is never a write path of its own.
 */
sealed interface VoiceIntent {
    /** "80 kilo 8 reps", "8 reps", "82,5 kg": the values for the current set; at least one is set. */
    data class SetWeightAndReps(val weightKg: Double?, val reps: Int?) : VoiceIntent

    /** "plus fem", "down two and a half": kilos added to (or, negative, taken from) the current set's weight. */
    data class AdjustWeight(val deltaKg: Double) : VoiceIntent

    /** "plus två reps", "one more rep": reps added to (or taken from) the current set's reps. */
    data class AdjustReps(val delta: Int) : VoiceIntent

    /** "samma igen", "same again": the previous set's numbers on the current set, then log it. */
    data object SameAgain : VoiceIntent

    /** "nästa", "next": the current set moves on as after a tick, without logging. */
    data object Next : VoiceIntent

    /** "lägg till set", "add a set". */
    data object AddSet : VoiceIntent

    /** "klar", "done", "log it": the same as the Log set button. */
    data object Done : VoiceIntent

    /** "avsluta passet", "finish workout": asks, like the Finish button; never finishes silently. */
    data object Finish : VoiceIntent

    /** "vila", "rest": starts the rest timer of the current set's exercise. */
    data object Rest : VoiceIntent

    /** Not understood, or understood two ways: nothing is guessed. [text] is what was heard. */
    data class Unknown(val text: String) : VoiceIntent
}
