package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.StickyAction
import io.github.kanylbullen.tung.core.stickyAction
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

/** What the sticky button does ([io.github.kanylbullen.tung.core.stickyAction]). */
sealed interface StickyButton {
    /** "Log set · Bench press · 80 × 8". */
    data class LogSet(val current: CurrentSet) : StickyButton

    /** "Add set · Bench press · 80 × 8": every set is ticked; a copy of [copyOf] (the exercise's last set, if any) is added. */
    data class AddSet(val exercise: ActiveExerciseUi, val copyOf: ActiveSetUi?) : StickyButton
}

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

/**
 * A one-shot request to open the first-time sheets for an exercise just added
 * with no history: the weight sheet for its only set ([setId]), then the reps
 * sheet with the set-count chips. Consumed by the screen once opened; [seq]
 * tells two requests apart.
 */
data class FirstTimeRequest(val workoutExerciseId: String, val setId: String, val seq: Long)

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
    /** An exercise was just added with no history: open its weight sheet, then reps and the set count. */
    val firstTimeRequest: FirstTimeRequest? = null,
) {
    val loggedSetCount: Int
        get() = exercises.sumOf { e -> e.sets.count { isLogged(it.completed, it.reps) } }

    /** See [io.github.kanylbullen.tung.core.currentSet]. */
    val current: CurrentSet?
        get() = (sticky as? StickyButton.LogSet)?.current

    /** See [io.github.kanylbullen.tung.core.stickyAction]: Log set, Add set once every set is ticked, or null with no exercises. */
    val sticky: StickyButton?
        get() {
            val touched = exercises.indexOfFirst { it.workoutExerciseId == lastTouchedExerciseId }.takeIf { it >= 0 }
            return when (val action = stickyAction(completedMatrix(), indexOfSet(selectedSetId), touched)) {
                is StickyAction.LogSet -> exercises[action.at.exercise].let { e -> StickyButton.LogSet(CurrentSet(e, e.sets[action.at.set])) }
                is StickyAction.AddSet -> exercises[action.exercise].let { e -> StickyButton.AddSet(e, action.copyOf?.let(e.sets::get)) }
                null -> null
            }
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
