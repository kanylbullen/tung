package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.core.wear.toWatch

/**
 * What the watch shows right after a tap, before the phone's next state
 * arrives (and kept if the phone cannot be reached, until it does). Only what
 * the watch can know for sure: a logged set is ticked, a new value is shown,
 * a skipped rest is gone. What only the phone decides (the next set's id, the
 * rest's length, the ids of a new workout) waits for the phone.
 */
fun WatchWorkoutState.applying(command: WatchCommand, nowMillis: Long): WatchWorkoutState = when (command) {
    is WatchCommand.LogSet -> {
        val exercise = exerciseOf(command.setId)
        val ticked = updateSet(command.setId) { it.copy(completed = true) }
        // The next set of the same exercise if it already exists; otherwise wait for the phone, which may add one.
        val next = exercise?.let { e -> ticked.exercises.first { it.workoutExerciseId == e.workoutExerciseId } }
            ?.sets?.firstOrNull { !it.completed }
        ticked.copy(currentSetId = next?.setId)
    }
    is WatchCommand.SetWeight -> updateSet(command.setId) { it.copy(weightKg = command.kg) }
    is WatchCommand.SetReps -> updateSet(command.setId) { it.copy(reps = command.reps) }
    is WatchCommand.SelectSet -> if (set(command.setId)?.completed == false) copy(currentSetId = command.setId) else this
    is WatchCommand.SkipRest -> copy(rest = null)
    is WatchCommand.AddRest30 -> copy(rest = rest?.toRestTimer()?.plus(nowMillis)?.toWatch())
    is WatchCommand.FinishWorkout -> WatchWorkoutState(routines = routines, updatedAt = updatedAt)
    is WatchCommand.StartWorkout, is WatchCommand.StartRoutine -> this
}

private fun WatchWorkoutState.updateSet(setId: String, change: (WatchSet) -> WatchSet): WatchWorkoutState =
    copy(
        exercises = exercises.map { e: WatchExercise ->
            if (e.sets.none { it.setId == setId }) e else e.copy(sets = e.sets.map { if (it.setId == setId) change(it) else it })
        },
    )

/**
 * The set an exercise's page acts on: the current set if it is in this
 * exercise, else the exercise's first un-ticked set, else none (all logged).
 */
fun WatchExercise.focusSet(currentSetId: String?): WatchSet? =
    sets.firstOrNull { it.setId == currentSetId && !it.completed } ?: sets.firstOrNull { !it.completed }

/** "Last: 80 × 8" for [set]: last session's set at the same index, none past its end (as on the phone). */
fun WatchExercise.lastTimeFor(set: WatchSet) = lastTime.getOrNull(sets.indexOfFirst { it.setId == set.setId })

/** Index of the exercise holding the current set, or null. */
fun WatchWorkoutState.currentExerciseIndex(): Int? =
    currentSetId?.let { id -> exercises.indexOfFirst { e -> e.sets.any { it.setId == id } }.takeIf { it >= 0 } }
