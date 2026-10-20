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
    /** "Bench press · set 3 of 5": the current set, with the routine's target. */
    data class AtSet(val exercise: String, val set: Int, val of: Int) : OngoingStatus

    /** "4 sets logged": no routine target to count against, or nothing left to log. */
    data class Logged(val count: Int) : OngoingStatus
}

/**
 * The status line of a workout in progress. The current set is [current] when
 * the caller knows it (the watch's state carries one), else the default rule
 * of [currentSet] with no selection and nothing touched, which is also the set
 * a "Log set" from the notification ticks. With a routine target for that
 * exercise it is [OngoingStatus.AtSet], counting against the larger of the
 * target and the sets the exercise has, so a set added past the target never
 * reads "set 4 of 3"; otherwise the number of logged sets ([isLogged]).
 */
fun ongoingStatus(exercises: List<OngoingExercise>, current: SetIndex? = null): OngoingStatus {
    val index = current?.takeIf { exercises.getOrNull(it.exercise)?.sets?.getOrNull(it.set)?.completed == false }
        ?: currentSet(exercises.map { e -> e.sets.map { it.completed } })
    val exercise = index?.let { exercises[it.exercise] }
    val target = exercise?.targetSets
    if (index != null && target != null) {
        return OngoingStatus.AtSet(exercise.name, index.set + 1, maxOf(target, exercise.sets.size))
    }
    return OngoingStatus.Logged(exercises.sumOf { e -> e.sets.count { isLogged(it.completed, it.reps) } })
}
