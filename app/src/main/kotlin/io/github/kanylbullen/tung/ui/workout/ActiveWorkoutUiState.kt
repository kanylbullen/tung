package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.isLogged

data class ActiveSetUi(
    val id: String,
    /** 1-based, as shown. */
    val number: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
    /** The set at the same position last time this exercise was done, if any. */
    val previous: LoggedSet?,
)

data class ActiveExerciseUi(
    val workoutExerciseId: String,
    val exerciseId: String,
    /** Fallback name; shown through `exerciseDisplayName(nameKey, name)`. */
    val name: String,
    val nameKey: String?,
    val sets: List<ActiveSetUi>,
)

data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val startedAt: Long = 0L,
    val exercises: List<ActiveExerciseUi> = emptyList(),
) {
    val loggedSetCount: Int
        get() = exercises.sumOf { e -> e.sets.count { isLogged(it.completed, it.reps) } }

    fun findSet(setId: String): ActiveSetUi? =
        exercises.firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.id == setId } }

    fun findExerciseOf(setId: String): ActiveExerciseUi? =
        exercises.firstOrNull { e -> e.sets.any { it.id == setId } }
}

sealed interface ActiveWorkoutEvent {
    data class Finished(val workoutId: String, val saved: Boolean) : ActiveWorkoutEvent
}
