package io.github.kanylbullen.tung.ui.common

import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.toLogged
import io.github.kanylbullen.tung.domain.LoggedSet
import io.github.kanylbullen.tung.domain.bestSet
import io.github.kanylbullen.tung.domain.isLogged
import io.github.kanylbullen.tung.domain.isNewRecord
import io.github.kanylbullen.tung.domain.totalVolume

data class ExerciseReport(
    val workoutExerciseId: Long,
    /** Fallback name; shown through `exerciseDisplayName(nameKey, name)`. */
    val name: String,
    val nameKey: String?,
    val sets: List<LoggedSet>,
    val best: LoggedSet?,
    val isRecord: Boolean,
)

/** A finished workout as the summary and detail screens show it. */
data class WorkoutReport(
    val workout: Workout,
    val exercises: List<ExerciseReport>,
) {
    val durationMillis: Long get() = (workout.endedAt ?: workout.startedAt) - workout.startedAt
    val setCount: Int get() = exercises.sumOf { it.sets.size }
    val totalVolumeKg: Double get() = totalVolume(exercises.flatMap { it.sets })
}

/**
 * Builds a report from what Room returned. Records need one query per exercise,
 * so they are only computed when [withRecords] is set.
 */
suspend fun buildWorkoutReport(
    workout: Workout,
    exercises: List<WorkoutExerciseWithSets>,
    repo: WorkoutRepository,
    withRecords: Boolean,
): WorkoutReport = WorkoutReport(
    workout = workout,
    exercises = exercises.map { e ->
        val sets = e.sortedSets.filter { isLogged(it.completed, it.reps) }.map { it.toLogged() }
        ExerciseReport(
            workoutExerciseId = e.workoutExercise.id,
            name = e.exercise.name,
            nameKey = e.exercise.nameKey,
            sets = sets,
            best = bestSet(sets),
            isRecord = withRecords &&
                isNewRecord(sets, repo.previousMaxWeight(e.exercise.id, before = workout.startedAt)),
        )
    },
)
