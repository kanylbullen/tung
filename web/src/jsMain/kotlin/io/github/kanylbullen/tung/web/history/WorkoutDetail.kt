package io.github.kanylbullen.tung.web.history

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.bestSet
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.progress.estimatedOneRepMax
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.store.TungStore

/** One exercise of a finished workout: its logged sets, the best one and the best estimated 1RM. */
data class DetailExercise(
    /** The exercise's id; null for a row whose exercise is missing. */
    val exerciseId: String?,
    val name: String,
    val sets: List<LoggedSet>,
    val best: LoggedSet?,
    val e1rmKg: Double?,
    val volumeKg: Double,
)

/** A finished workout as its page shows it: the phone's workout detail. */
data class WorkoutDetail(
    val id: String,
    val title: String?,
    val startedAt: Long,
    val durationMillis: Long,
    val sets: Int,
    val volumeKg: Double,
    val exercises: List<DetailExercise>,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val activeCalories: Int?,
)

/**
 * The finished workout [id] with only its logged sets (ticked, reps > 0) per
 * exercise, in order; null when there is no such finished workout. [title]
 * is the routine's name when it was started from one.
 */
fun workoutDetail(store: TungStore, id: String, displayName: (SyncExercise) -> String): WorkoutDetail? {
    val workout = store.workouts.get(id) ?: return null
    val startedAt = workout.startedAt ?: return null
    val endedAt = workout.endedAt ?: return null
    val setsByExercise = store.sets.rows.values.map { it.row }.groupBy { it.workoutExerciseId }
    val exercises = store.workoutExercises.rows.values.map { it.row }
        .filter { it.workoutId == id }
        .sortedBy { it.position ?: 0 }
        .mapNotNull { we ->
            val sets = setsByExercise[we.id].orEmpty()
                .sortedBy { it.position ?: 0 }
                .filter { isLogged(it.completed ?: false, it.reps ?: 0) }
                .map { LoggedSet(it.weightKg ?: 0.0, it.reps ?: 0) }
            if (sets.isEmpty()) return@mapNotNull null
            val name = we.exerciseId?.let { store.exercises.get(it) }?.let(displayName) ?: we.exerciseId ?: we.id
            DetailExercise(
                exerciseId = we.exerciseId,
                name = name,
                sets = sets,
                best = bestSet(sets),
                e1rmKg = sets.mapNotNull { estimatedOneRepMax(it) }.maxOrNull(),
                volumeKg = sets.sumOf { it.volume },
            )
        }
    return WorkoutDetail(
        id = id,
        title = workout.routineId?.let { store.routines.get(it)?.name },
        startedAt = startedAt,
        durationMillis = endedAt - startedAt,
        sets = exercises.sumOf { it.sets.size },
        volumeKg = exercises.sumOf { it.volumeKg },
        exercises = exercises,
        avgHeartRate = workout.avgHeartRate,
        maxHeartRate = workout.maxHeartRate,
        activeCalories = workout.activeCalories,
    )
}
