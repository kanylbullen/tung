package io.github.kanylbullen.tung.web.history

import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.web.store.TungStore

/**
 * One finished workout as History lists it: the phone's `WorkoutSummaryRow`
 * computed from the store. The title is the routine's name when the workout
 * was started from one, else the first two exercises' names, else nothing
 * (the screen says "Workout").
 */
data class HistoryRow(
    val id: String,
    val startedAt: Long,
    val durationMillis: Long,
    val exerciseCount: Int,
    /** Weight × reps over the logged sets (ticked, reps > 0), as everywhere. */
    val volumeKg: Double,
    val routineName: String?,
    val exerciseNames: List<String>,
)

/**
 * Every finished workout, newest first. [displayName] names an exercise as
 * the screen shows it (the catalogue's name in the app's language, else
 * the stored one). A workout in progress is not history.
 */
fun historyRows(store: TungStore, displayName: (SyncExercise) -> String): List<HistoryRow> {
    val exercisesByWorkout = store.workoutExercises.rows.values
        .map { it.row }
        .groupBy { it.workoutId }
        .mapValues { (_, rows) -> rows.sortedBy { it.position ?: 0 } }
    val setsByExercise = store.sets.rows.values.map { it.row }.groupBy { it.workoutExerciseId }
    return store.workouts.rows.values
        .mapNotNull { stored ->
            val workout = stored.row
            val startedAt = workout.startedAt ?: return@mapNotNull null
            val endedAt = workout.endedAt ?: return@mapNotNull null
            val exercises = exercisesByWorkout[workout.id].orEmpty()
            val volume = exercises.sumOf { exercise ->
                setsByExercise[exercise.id].orEmpty().sumOf { set ->
                    val reps = set.reps ?: 0
                    if (isLogged(set.completed ?: false, reps)) (set.weightKg ?: 0.0) * reps else 0.0
                }
            }
            HistoryRow(
                id = workout.id,
                startedAt = startedAt,
                durationMillis = endedAt - startedAt,
                exerciseCount = exercises.size,
                volumeKg = volume,
                routineName = workout.routineId?.let { store.routines.get(it)?.name },
                exerciseNames = exercises.take(2).mapNotNull { exercise ->
                    exercise.exerciseId?.let { store.exercises.get(it) }?.let(displayName)
                },
            )
        }
        .sortedByDescending { it.startedAt }
}

/**
 * An exercise's name as the screen shows it: a catalogue exercise by its key
 * in [language] from [catalogueNames] (key → language → name), English when
 * the catalogue lacks the language, else what is stored (a custom exercise,
 * or an extended-catalogue entry's English name).
 */
fun exerciseDisplayName(exercise: SyncExercise, catalogueNames: Map<String, Map<String, String>>, language: String): String {
    val key = exercise.nameKey
    if (key != null) {
        catalogueNames[key]?.let { names -> (names[language] ?: names["en"])?.let { return it } }
    }
    return exercise.name ?: key ?: exercise.id
}
