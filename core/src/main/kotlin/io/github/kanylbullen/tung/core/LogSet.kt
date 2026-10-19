package io.github.kanylbullen.tung.core

/** Where the current set is: the exercise's index and the set's index within it. */
data class SetIndex(val exercise: Int, val set: Int)

/**
 * The set "Log set" acts on: the first un-ticked set of the exercise the user
 * last touched, or, when that exercise has none left (or nothing was touched),
 * the first un-ticked set of the first exercise that has one. Null when every
 * set is ticked.
 *
 * @param completed per exercise in screen order, each set's ticked state in set order.
 * @param lastTouchedExercise index into [completed], or null.
 */
fun currentSet(completed: List<List<Boolean>>, lastTouchedExercise: Int?): SetIndex? {
    lastTouchedExercise?.let { e ->
        val set = completed.getOrNull(e)?.indexOfFirst { !it } ?: -1
        if (set >= 0) return SetIndex(e, set)
    }
    completed.forEachIndexed { e, sets ->
        val set = sets.indexOfFirst { !it }
        if (set >= 0) return SetIndex(e, set)
    }
    return null
}

/**
 * Whether logging a set adds the next one. Only when the logged set was the
 * last of its exercise, so the user always has one set waiting, and never past
 * a routine's target: an exercise started from a routine with [targetSets]
 * sets is not padded beyond them. [setCount] is the exercise's sets including
 * the one just logged; [targetSets] is null outside a routine.
 */
fun addsNextSet(wasLast: Boolean, setCount: Int, targetSets: Int?): Boolean =
    wasLast && (targetSets == null || setCount < targetSets)
