package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.progress.ProgressSet

/**
 * One counted set of a finished workout with its exercise, for progress
 * (`WorkoutDao.observeProgressSets`); core's `core.progress` does the sums.
 * A query result, not an entity: no table, no schema change.
 */
data class ProgressSetRow(
    val exerciseId: String,
    val workoutId: String,
    val workoutExerciseId: String,
    val startedAt: Long,
    val exercisePosition: Int,
    val setPosition: Int,
    val weightKg: Double,
    val reps: Int,
) {
    fun toProgressSet(): ProgressSet =
        ProgressSet(exerciseId, workoutId, workoutExerciseId, startedAt, exercisePosition, setPosition, LoggedSet(weightKg, reps))
}
