package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.domain.LoggedSet
import io.github.kanylbullen.tung.domain.isLogged

data class ActiveSetUi(
    val id: Long,
    /** 1-based, as shown. */
    val number: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
    /** The set at the same position last time this exercise was done, if any. */
    val previous: LoggedSet?,
)

data class ActiveExerciseUi(
    val workoutExerciseId: Long,
    val exerciseId: Long,
    val name: String,
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

    fun findSet(setId: Long): ActiveSetUi? =
        exercises.firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.id == setId } }
}

sealed interface ActiveWorkoutEvent {
    data class Finished(val workoutId: Long, val saved: Boolean) : ActiveWorkoutEvent
}
