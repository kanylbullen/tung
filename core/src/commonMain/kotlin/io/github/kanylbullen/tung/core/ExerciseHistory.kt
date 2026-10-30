package io.github.kanylbullen.tung.core

/**
 * One counted set of an exercise in a finished workout, as the exercise page
 * reads them: which workout and which of its exercise rows (an exercise can
 * be in a workout twice), when the workout started, and the set's position.
 */
data class HistorySet(
    val workoutId: String,
    val workoutExerciseId: String,
    val workoutStartedAt: Long,
    val exercisePosition: Int,
    val setPosition: Int,
    val set: LoggedSet,
)

/**
 * The user's history of one exercise: how many finished workouts have it,
 * last session's sets (the first time it appears in the latest of them, as
 * `WorkoutDao.previousSessionSets` picks it), and the best set ever ([bestSet]).
 */
data class ExerciseHistory(
    val sessions: Int,
    val lastSessionAt: Long?,
    val lastSession: List<LoggedSet>,
    val best: LoggedSet?,
) {
    val isEmpty: Boolean get() = sessions == 0

    companion object {
        val EMPTY = ExerciseHistory(0, null, emptyList(), null)
    }
}

/**
 * [sets] summarised, in any order. Only counted sets belong in it
 * ([isLogged]); a set with no reps is skipped here as well, so a stray
 * placeholder cannot become the best set.
 */
fun exerciseHistory(sets: List<HistorySet>): ExerciseHistory {
    val counted = sets.filter { it.set.reps > 0 }
    if (counted.isEmpty()) return ExerciseHistory.EMPTY
    val latest = counted.maxWith(compareBy<HistorySet> { it.workoutStartedAt }.thenBy { it.workoutId })
    val firstRow = counted.filter { it.workoutId == latest.workoutId }
        .minWith(compareBy<HistorySet> { it.exercisePosition }.thenBy { it.workoutExerciseId })
        .workoutExerciseId
    return ExerciseHistory(
        sessions = counted.mapTo(HashSet()) { it.workoutId }.size,
        lastSessionAt = latest.workoutStartedAt,
        lastSession = counted.filter { it.workoutExerciseId == firstRow }.sortedBy { it.setPosition }.map { it.set },
        best = bestSet(counted.map { it.set }),
    )
}
