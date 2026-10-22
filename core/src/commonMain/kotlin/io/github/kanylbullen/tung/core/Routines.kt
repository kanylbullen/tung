package io.github.kanylbullen.tung.core

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.toLocalDateTime

/** Fewest and most sets a routine exercise can ask for. */
const val MIN_TARGET_SETS = 1
const val MAX_TARGET_SETS = 20

/** What a routine asks for of one exercise: a number of sets, and the reps it aims at if there is one. */
data class RoutineTarget(val sets: Int, val reps: Int?)

/**
 * The rep count that occurs most often, or null when there is none (no sets,
 * or only sets with no reps). A tie goes to the count that came first, so
 * 8, 8, 6, 6 aims at 8: the number you started the exercise with.
 */
fun mostCommonReps(reps: List<Int>): Int? {
    val counted = reps.filter { it > 0 }
    if (counted.isEmpty()) return null
    val frequency = counted.groupingBy { it }.eachCount()
    val top = frequency.values.max()
    return counted.first { frequency.getValue(it) == top }
}

/**
 * The target an exercise gets when a workout is saved as a routine: one set per
 * logged set, aiming at the most common rep count. [sets] are the logged sets
 * of that exercise, in order; placeholders (no reps) are not counted.
 */
fun routineTarget(sets: List<LoggedSet>): RoutineTarget {
    val logged = sets.filter { it.reps > 0 }
    return RoutineTarget(sets = logged.size, reps = mostCommonReps(logged.map { it.reps }))
}

/** A target set count kept inside [MIN_TARGET_SETS]..[MAX_TARGET_SETS]. */
fun clampTargetSets(sets: Int): Int = sets.coerceIn(MIN_TARGET_SETS, MAX_TARGET_SETS)

/**
 * The [targetSets] sets an exercise starts with when a routine is started.
 *
 * Each set uses the existing pre-fill rule at its own position ([prefillSet]
 * with nothing yet in this workout), so set 1 shows last time's set 1, set 2
 * last time's set 2, and so on. Past the end of last session, a set repeats the
 * one before it, the same as tapping "+ Set" would. With no history at all the
 * sets start at 0 kg and the routine's [targetReps] (no reps when it has none).
 */
fun routineStartSets(targetSets: Int, targetReps: Int?, previousSession: List<LoggedSet>): List<LoggedSet> {
    val result = mutableListOf<LoggedSet>()
    repeat(targetSets.coerceAtLeast(0)) { position ->
        result += prefillSet(lastInThisWorkout = null, previousSession = previousSession, position = position)
            ?: result.lastOrNull()
            ?: LoggedSet(weightKg = 0.0, reps = targetReps ?: 0)
    }
    return result
}

/**
 * The sets an exercise starts with when it is added to a workout
 * (`add_exercise`): [requested] sets, or as many as [previousSession] had, at
 * least one. Each is pre-filled like a routine's start sets
 * ([routineStartSets], with no target reps): last session's set at the same
 * position, past its end a copy of the set before, and 0 kg × 0 with no
 * history at all.
 */
fun addedExerciseSets(requested: Int?, previousSession: List<LoggedSet>): List<LoggedSet> =
    routineStartSets(requested ?: previousSession.size.coerceAtLeast(1), targetReps = null, previousSession = previousSession)

/**
 * Whole calendar days from [thenMillis] to [nowMillis] in [zone]: 0 for earlier
 * today, 1 for yesterday, regardless of the hour. Never negative.
 */
fun daysAgo(thenMillis: Long, nowMillis: Long, zone: TimeZone): Long {
    val then = Instant.fromEpochMilliseconds(thenMillis).toLocalDateTime(zone).date
    val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone).date
    return then.daysUntil(now).coerceAtLeast(0).toLong()
}
