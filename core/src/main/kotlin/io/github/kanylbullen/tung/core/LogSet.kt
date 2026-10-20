package io.github.kanylbullen.tung.core

/** Where the current set is: the exercise's index and the set's index within it. */
data class SetIndex(val exercise: Int, val set: Int)

/**
 * The set "Log set" acts on. An explicit [selected] set wins while it is still
 * there and un-ticked (the user tapped an exercise's header or a set's number).
 * Otherwise the first un-ticked set of the exercise the user last touched, or,
 * when that exercise has none left (or nothing was touched), the first
 * un-ticked set of the first exercise that has one. Null when every set is ticked.
 *
 * @param completed per exercise in screen order, each set's ticked state in set order.
 * @param selected the set the user chose, or null; ignored when out of range or ticked.
 * @param lastTouchedExercise index into [completed], or null.
 */
fun currentSet(
    completed: List<List<Boolean>>,
    selected: SetIndex? = null,
    lastTouchedExercise: Int? = null,
): SetIndex? {
    selected?.let { s ->
        if (completed.getOrNull(s.exercise)?.getOrNull(s.set) == false) return s
    }
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
 * Where the current set goes once the set at [logged] is ticked (by "Log set"
 * or the tick on the row): the first un-ticked set of the same exercise while
 * it has one, else the first un-ticked set of the next exercise that has one,
 * in list order, wrapping past the end to the start. Null when every set is ticked.
 *
 * @param completed the state after logging: [logged] ticked, plus the set
 *   "Log set" added after it, if it added one.
 */
fun nextCurrentAfterLogging(completed: List<List<Boolean>>, logged: SetIndex): SetIndex? {
    val count = completed.size
    if (count == 0) return null
    val start = logged.exercise.coerceIn(0, count - 1)
    for (offset in 0 until count) {
        val e = (start + offset) % count
        val set = completed[e].indexOfFirst { !it }
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
