package io.github.kanylbullen.tung.core

/**
 * One exercise of a workout in progress, as the ongoing notification (phone)
 * and the Ongoing Activity (watch) see it.
 *
 * @param sets each set's ticked state and reps, in set order.
 * @param targetSets the routine's target sets for this exercise, or null when
 *   the workout has no routine or the routine does not have the exercise.
 */
data class OngoingExercise(
    val name: String,
    val sets: List<OngoingSet>,
    val targetSets: Int? = null,
)

data class OngoingSet(val completed: Boolean, val reps: Int)

/** What the ongoing notification says about a workout in progress. */
sealed interface OngoingStatus {
    /**
     * "Bench press · set 3 of 5": the current set, counted against the
     * routine's target or, without one, the sets the exercise has.
     */
    data class AtSet(val exercise: String, val set: Int, val of: Int) : OngoingStatus

    /** "0 sets logged": no exercise with a set at all. */
    data class Logged(val count: Int) : OngoingStatus

    /**
     * "Bench press · all sets logged": nothing left to log, so the action is
     * Add set for [exercise] ([stickyAction]).
     */
    data class AllLogged(val exercise: String) : OngoingStatus
}

/**
 * The status line of a workout in progress. The current set is [current] when
 * the caller knows it (the watch's state carries one), else the default rule
 * of [currentSet] with no selection and nothing touched, which is also the
 * set a "Log set" from the notification ticks. It is [OngoingStatus.AtSet],
 * counting against the larger of the routine's target and the sets the
 * exercise has, so a set added past the target never reads "set 4 of 3",
 * and a free workout counts against the sets there are ("set 1 of 5": the
 * Live Update's chip shows that set's numbers, so the body names it too).
 * With no un-ticked set left it is [OngoingStatus.AllLogged] for the exercise
 * an Add set would go to ([stickyAction] with [lastTouchedExercise]: the
 * caller's best idea of the exercise just logged, else the last one). Only a
 * workout with no set at all is [OngoingStatus.Logged], with its count of 0.
 */
fun ongoingStatus(
    exercises: List<OngoingExercise>,
    current: SetIndex? = null,
    lastTouchedExercise: Int? = null,
): OngoingStatus {
    val completed = exercises.map { e -> e.sets.map { it.completed } }
    val index = current?.takeIf { exercises.getOrNull(it.exercise)?.sets?.getOrNull(it.set)?.completed == false }
        ?: currentSet(completed)
    if (index == null) {
        val add = stickyAction(completed, lastTouchedExercise = lastTouchedExercise) as? StickyAction.AddSet
        if (add != null) return OngoingStatus.AllLogged(exercises[add.exercise].name)
    }
    if (index != null) {
        val exercise = exercises[index.exercise]
        return OngoingStatus.AtSet(exercise.name, index.set + 1, maxOf(exercise.targetSets ?: 0, exercise.sets.size))
    }
    return OngoingStatus.Logged(exercises.sumOf { e -> e.sets.count { isLogged(it.completed, it.reps) } })
}
