package io.github.kanylbullen.tung.web.workout

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.core.catalog.passes
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
    /** The exercise's own rest between sets in seconds, 0 for none, null to follow the default. */
    val restSeconds: Int? = null,
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

    /** Each exercise's sets' ticked state, in screen order: what core's current-set rule takes. */
    val completed: List<List<Boolean>> get() = exercises.map { e -> e.sets.map { it.completed } }

    /** Where the set [setId] is, or null. */
    fun indexOf(setId: String): io.github.kanylbullen.tung.core.SetIndex? {
        exercises.forEachIndexed { e, exercise ->
            val s = exercise.sets.indexOfFirst { it.id == setId }
            if (s >= 0) return io.github.kanylbullen.tung.core.SetIndex(e, s)
        }
        return null
    }
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
                restSeconds = exercise?.restSeconds,
            )
        }
    return WorkoutView(workout.id, workout.startedAt ?: 0L, workout.routineId?.let { store.routines.get(it)?.name }, exercises)
}

/** One row of the exercise picker. */
data class PickerRow(val id: String, val name: String)

/**
 * Every exercise in the store that can be done with [available] ("My
 * equipment", core's [passes]) and whose displayed name matches [query] (core's
 * diacritic-insensitive search, so "bank" finds "Bänkpress"), by name in the
 * locale's order; an empty query lists them all.
 */
fun pickerRows(
    store: TungStore,
    query: String,
    displayName: (SyncExercise) -> String,
    order: Comparator<String>,
    available: Set<Equipment> = EquipmentFilter.ALL,
): List<PickerRow> =
    store.exercises.rows.values.map { it.row }
        .filter { passes(it.equipment, available) }
        .map { PickerRow(it.id, displayName(it)) }
        .filter { matchesSearch(it.name, query) }
        .sortedWith(compareBy(order) { it.name })
