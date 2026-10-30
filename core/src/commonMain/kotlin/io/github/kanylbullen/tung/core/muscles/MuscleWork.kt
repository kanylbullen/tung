package io.github.kanylbullen.tung.core.muscles

import io.github.kanylbullen.tung.core.ExerciseMuscles
import io.github.kanylbullen.tung.core.Muscle
import kotlin.math.floor

/** What one logged set gives each secondary muscle of its exercise; each primary muscle gets a whole set. */
const val SECONDARY_SET_CREDIT = 0.5

/**
 * One exercise of a workout as [muscleBreakdown] reads it: what it trains
 * ([muscles], null for a custom exercise, which the catalogue does not know)
 * and how many sets of it were logged.
 */
data class ExerciseWork(val muscles: ExerciseMuscles?, val loggedSets: Int)

/**
 * One muscle's part of a workout: [sets] is its set credit (a whole set per
 * logged set of an exercise it is primary in, [SECONDARY_SET_CREDIT] where it
 * is secondary), [percent] its whole share of every muscle's credit.
 */
data class MuscleWork(val muscle: Muscle, val sets: Double, val percent: Int)

/**
 * How a workout's logged sets fall on the muscles: [muscles] most worked
 * first, their percentages summing to 100, and [uncountedSets], the sets of
 * exercises with no muscles in the catalogue (custom ones), which are in
 * none of them.
 */
data class MuscleBreakdown(val muscles: List<MuscleWork>, val uncountedSets: Int) {
    val isEmpty: Boolean get() = muscles.isEmpty()

    /** [muscle]'s credit against the most worked muscle's, 0 to 1; 0 for one not worked. */
    fun intensity(muscle: Muscle): Double {
        val top = muscles.firstOrNull()?.sets ?: return 0.0
        return (muscles.firstOrNull { it.muscle == muscle }?.sets ?: 0.0) / top
    }

    companion object {
        val EMPTY = MuscleBreakdown(emptyList(), 0)
    }
}

/**
 * Sets per muscle, the usual measure of how much a muscle was trained:
 * weight does not enter, so bodyweight work counts like loaded work and a
 * heavy squat does not drown out the curls. Each logged set credits every
 * primary muscle of its exercise with one set and every other (secondary)
 * one with [SECONDARY_SET_CREDIT]. Muscles are ordered by credit, then in
 * [Muscle] order; the percentages are rounded by largest remainder, so they
 * add up to exactly 100 (a muscle with a sliver of the work can show 0).
 */
fun muscleBreakdown(exercises: List<ExerciseWork>): MuscleBreakdown {
    val credit = LinkedHashMap<Muscle, Double>()
    var uncounted = 0
    for (exercise in exercises) {
        if (exercise.loggedSets <= 0) continue
        val muscles = exercise.muscles
        if (muscles == null) {
            uncounted += exercise.loggedSets
            continue
        }
        val primary = muscles.primary.toSet()
        for (m in primary) credit[m] = (credit[m] ?: 0.0) + exercise.loggedSets
        for (m in muscles.secondary.toSet() - primary) credit[m] = (credit[m] ?: 0.0) + exercise.loggedSets * SECONDARY_SET_CREDIT
    }
    val ordered = credit.entries.sortedWith(compareByDescending<Map.Entry<Muscle, Double>> { it.value }.thenBy { it.key.ordinal })
    val percents = wholePercents(ordered.map { it.value })
    return MuscleBreakdown(ordered.mapIndexed { i, (muscle, sets) -> MuscleWork(muscle, sets, percents[i]) }, uncounted)
}

/**
 * [values] as whole percentages of their sum that add up to 100: each gets
 * its share rounded down, and what is left goes one by one to the largest
 * remainders (on a tie, the earlier value). All zeros when the sum is zero.
 */
fun wholePercents(values: List<Double>): List<Int> {
    val total = values.sum()
    if (values.isEmpty() || total <= 0.0) return values.map { 0 }
    val exact = values.map { it / total * 100 }
    val floors = exact.map { floor(it).toInt() }.toMutableList()
    val left = 100 - floors.sum()
    exact.indices.sortedWith(compareByDescending<Int> { exact[it] - floors[it] }.thenBy { it })
        .take(left)
        .forEach { floors[it] += 1 }
    return floors
}
