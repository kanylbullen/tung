package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.currentSet
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
    /** The rest after a set of this exercise, in seconds: its own setting or the default. 0 = no timer. */
    val restSeconds: Int,
)

/** The set "Log set" acts on, with its exercise. */
data class CurrentSet(val exercise: ActiveExerciseUi, val set: ActiveSetUi)

/**
 * A one-shot request to scroll the list, consumed by the screen once done.
 * [setId] or [workoutExerciseId] names the item: a set row, or an exercise's
 * header. [seq] tells two requests for the same item apart.
 */
data class ScrollRequest(
    val setId: String? = null,
    val workoutExerciseId: String? = null,
    val mode: ScrollMode,
    val seq: Long,
)

enum class ScrollMode {
    /** Only if the item is not wholly visible, and then just enough to show it. */
    IntoView,

    /** The item at the top of the list. */
    ToTop,
}

data class ActiveWorkoutUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val startedAt: Long = 0L,
    val exercises: List<ActiveExerciseUi> = emptyList(),
    /** The rest countdown, or null when none is running. */
    val rest: RestTimer? = null,
    /** The workout exercise the user last ticked, edited or added a set to. */
    val lastTouchedExerciseId: String? = null,
    /** The set the user made current (header or set-number tap); wins while it exists and is un-ticked. */
    val selectedSetId: String? = null,
    val scrollRequest: ScrollRequest? = null,
) {
    val loggedSetCount: Int
        get() = exercises.sumOf { e -> e.sets.count { isLogged(it.completed, it.reps) } }

    /** See [io.github.kanylbullen.tung.core.currentSet]. */
    val current: CurrentSet?
        get() {
            val touched = exercises.indexOfFirst { it.workoutExerciseId == lastTouchedExerciseId }.takeIf { it >= 0 }
            val index = currentSet(completedMatrix(), indexOfSet(selectedSetId), touched) ?: return null
            val exercise = exercises[index.exercise]
            return CurrentSet(exercise, exercise.sets[index.set])
        }

    /** Per exercise in screen order, each set's ticked state: the shape the core rules take. */
    fun completedMatrix(): List<List<Boolean>> = exercises.map { e -> e.sets.map { it.completed } }

    fun indexOfSet(setId: String?): SetIndex? {
        if (setId == null) return null
        exercises.forEachIndexed { e, exercise ->
            val s = exercise.sets.indexOfFirst { it.id == setId }
            if (s >= 0) return SetIndex(e, s)
        }
        return null
    }

    fun findSet(setId: String): ActiveSetUi? =
        exercises.firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.id == setId } }

    fun findExerciseOf(setId: String): ActiveExerciseUi? =
        exercises.firstOrNull { e -> e.sets.any { it.id == setId } }
}

sealed interface ActiveWorkoutEvent {
    data class Finished(val workoutId: String, val saved: Boolean) : ActiveWorkoutEvent

    /** The first rest timer started: ask for the notification permission (Android 13+), once. */
    data object AskForNotifications : ActiveWorkoutEvent
}
