package io.github.kanylbullen.tung.ui.common

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.bestSet
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.isNewRecord
import io.github.kanylbullen.tung.core.progress.estimatedOneRepMax
import io.github.kanylbullen.tung.core.totalVolume
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.data.toLogged

data class ExerciseReport(
    val workoutExerciseId: String,
    val exerciseId: String,
    /** Fallback name; shown through `exerciseDisplayName(nameKey, name)`. */
    val name: String,
    val nameKey: String?,
    /** The logged sets only. */
    val sets: List<LoggedSet>,
    val best: LoggedSet?,
    val isRecord: Boolean,
) {
    val volumeKg: Double get() = totalVolume(sets)

    /** The best estimated 1RM of the sets (core's Epley, 1–12 reps), null when none has one. */
    val e1rmKg: Double? get() = sets.mapNotNull { estimatedOneRepMax(it) }.maxOrNull()
}

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
 * so they are only computed when [withRecords] is set, and never for an
 * imported workout (`Workout.importSource`): old sets are history, not news.
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
            exerciseId = e.exercise.id,
            name = e.exercise.name,
            nameKey = e.exercise.nameKey,
            sets = sets,
            best = bestSet(sets),
            isRecord = withRecords && workout.importSource == null &&
                isNewRecord(sets, repo.previousMaxWeight(e.exercise.id, before = workout.startedAt)),
        )
    },
)
