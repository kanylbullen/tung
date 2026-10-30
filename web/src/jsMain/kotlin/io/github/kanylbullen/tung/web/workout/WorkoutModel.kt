package io.github.kanylbullen.tung.web.workout

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.matchesSearch
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.store.TungStore

/** One set of the workout in progress, as its row shows it. */
data class SetView(val id: String, val number: Int, val weightKg: Double, val reps: Int, val completed: Boolean)

/** One exercise of the workout in progress: its sets in order, and last session's sets for "Last: 80 × 8". */
data class ExerciseView(
    val workoutExerciseId: String,
    val exerciseId: String,
    val name: String,
    val sets: List<SetView>,
    val last: List<LoggedSet>,
)

/** The workout in progress, wherever it was started. */
data class WorkoutView(
    val id: String,
    val startedAt: Long,
    val routineName: String?,
    val exercises: List<ExerciseView>,
) {
    /** Sets that are ticked and have reps: what finishing keeps. */
    val loggedSets: Int get() = exercises.sumOf { e -> e.sets.count { it.completed && it.reps > 0 } }

    /** Sets finishing removes: not ticked, or ticked without reps. */
    val openSets: Int get() = exercises.sumOf { e -> e.sets.count { !(it.completed && it.reps > 0) } }
}

/** The workout in progress (no end; the latest started when a sync brought several), or null. */
fun activeWorkout(store: TungStore, displayName: (SyncExercise) -> String): WorkoutView? {
    val workout = store.workouts.rows.values.map { it.row }
        .filter { it.endedAt == null && it.startedAt != null }
        .maxByOrNull { it.startedAt ?: 0L } ?: return null
    val setsByExercise = store.sets.rows.values.map { it.row }.groupBy { it.workoutExerciseId }
    val exercises = store.workoutExercises.rows.values.map { it.row }
        .filter { it.workoutId == workout.id }
        .sortedBy { it.position ?: 0 }
        .mapNotNull { we ->
            val exerciseId = we.exerciseId ?: return@mapNotNull null
            val exercise = store.exercises.get(exerciseId)
            ExerciseView(
                workoutExerciseId = we.id,
                exerciseId = exerciseId,
                name = exercise?.let(displayName) ?: exerciseId,
                sets = setsByExercise[we.id].orEmpty().sortedBy { it.position ?: 0 }.mapIndexed { index, set ->
                    SetView(set.id, index + 1, set.weightKg ?: 0.0, set.reps ?: 0, set.completed ?: false)
                },
                last = store.previousSession(exerciseId, excludeWorkoutId = workout.id),
            )
        }
    return WorkoutView(workout.id, workout.startedAt ?: 0L, workout.routineId?.let { store.routines.get(it)?.name }, exercises)
}

/** One row of the exercise picker. */
data class PickerRow(val id: String, val name: String)

/**
 * Every exercise in the store whose displayed name matches [query] (core's
 * diacritic-insensitive search, so "bank" finds "Bänkpress"), by name in the
 * locale's order; an empty query lists them all.
 */
fun pickerRows(store: TungStore, query: String, displayName: (SyncExercise) -> String, order: Comparator<String>): List<PickerRow> =
    store.exercises.rows.values.map { it.row }
        .map { PickerRow(it.id, displayName(it)) }
        .filter { matchesSearch(it.name, query) }
        .sortedWith(compareBy(order) { it.name })
