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
 * Whether logging a set with `log_set.add_next` adds the next one (a plain
 * Log set never does, issue #64; the button offers [StickyAction.AddSet]
 * instead). Only when the logged set was the last of its exercise, and never past
 * a routine's target: an exercise started from a routine with [targetSets]
 * sets is not padded beyond them. [setCount] is the exercise's sets including
 * the one just logged; [targetSets] is null outside a routine.
 */
fun addsNextSet(wasLast: Boolean, setCount: Int, targetSets: Int?): Boolean =
    wasLast && (targetSets == null || setCount < targetSets)

/**
 * What the sticky button under the thumb does (issue #64): "Log set" while
 * there is a current set, else "Add set" for one exercise, else nothing.
 * Logging never adds a set by itself, so when the last un-ticked set of the
 * workout is logged the button turns into Add set rather than disappearing.
 */
sealed interface StickyAction {
    /** "Log set · Bench press · 80 × 8": tick the set at [at]. */
    data class LogSet(val at: SetIndex) : StickyAction

    /**
     * "Add set · Bench press · 80 × 8": add a set to exercise [exercise], a
     * copy of its set [copyOf] (its last), as + Set does; [copyOf] is null when
     * the exercise has no sets, and the new set is then pre-filled from last
     * session as + Set would.
     */
    data class AddSet(val exercise: Int, val copyOf: Int?) : StickyAction
}

/**
 * The sticky action for a workout. A current set ([currentSet] with the same
 * [selected] and [lastTouchedExercise]) is [StickyAction.LogSet]. With every
 * set ticked it is [StickyAction.AddSet] for the exercise last touched (which
 * after a Log set is the one just logged), else for the last exercise in the
 * list. Null only for a workout with no exercises.
 *
 * @param completed per exercise in screen order, each set's ticked state in set order.
 */
fun stickyAction(
    completed: List<List<Boolean>>,
    selected: SetIndex? = null,
    lastTouchedExercise: Int? = null,
): StickyAction? {
    currentSet(completed, selected, lastTouchedExercise)?.let { return StickyAction.LogSet(it) }
    if (completed.isEmpty()) return null
    val exercise = lastTouchedExercise?.takeIf { it in completed.indices } ?: completed.lastIndex
    return StickyAction.AddSet(exercise, completed[exercise].lastIndex.takeIf { it >= 0 })
}
