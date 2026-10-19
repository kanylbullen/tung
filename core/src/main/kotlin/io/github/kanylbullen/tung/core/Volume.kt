package io.github.kanylbullen.tung.core

/** Sum of weight × reps. Bodyweight sets at 0 kg contribute nothing, by design. */
fun totalVolume(sets: List<LoggedSet>): Double = sets.sumOf { it.volume }

/**
 * Highest weight × reps. Ties go to the heavier set, then to more reps, so a
 * bodyweight exercise logged at 0 kg still yields its biggest set.
 */
fun bestSet(sets: List<LoggedSet>): LoggedSet? =
    sets.maxWithOrNull(compareBy<LoggedSet> { it.volume }.thenBy { it.weightKg }.thenBy { it.reps })

/**
 * True when some set in [sets] is heavier than anything lifted for the exercise
 * before. With no history there is nothing to beat, so the first session never
 * counts as a record — otherwise every new exercise would shout.
 */
fun isNewRecord(sets: List<LoggedSet>, previousMaxWeightKg: Double?): Boolean {
    if (previousMaxWeightKg == null) return false
    val heaviest = sets.filter { it.reps > 0 }.maxOfOrNull { it.weightKg } ?: return false
    return heaviest > previousMaxWeightKg
}
