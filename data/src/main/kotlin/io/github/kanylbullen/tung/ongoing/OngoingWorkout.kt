package io.github.kanylbullen.tung.ongoing

import android.content.Context
import io.github.kanylbullen.tung.core.OngoingExercise
import io.github.kanylbullen.tung.core.OngoingSet
import io.github.kanylbullen.tung.core.OngoingStatus
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.ongoingStatus
import io.github.kanylbullen.tung.data.R
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.displayName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * A workout in progress as the ongoing notification shows it: everything it
 * needs, already resolved, so the notifier only draws.
 *
 * @param logSetId the set the notification's "Log set" ticks (the current set
 *   by the default rule); null when every set is ticked.
 * @param logSetHasReps false when that set has 0 reps: logging it would tick a
 *   set that does not count, so the action opens the workout instead, as the
 *   tick on the row opens the reps sheet.
 */
data class OngoingWorkout(
    val workoutId: String,
    val startedAt: Long,
    val status: OngoingStatus,
    val startedBy: String? = null,
    val logSetId: String? = null,
    val logSetHasReps: Boolean = false,
)

/** The notification's body: "Bench press · set 3 of 5", or "4 sets logged". */
fun OngoingStatus.text(context: Context): String = when (this) {
    is OngoingStatus.AtSet -> context.getString(R.string.ongoing_at_set, exercise, set, of)
    is OngoingStatus.Logged -> context.resources.getQuantityString(R.plurals.ongoing_sets_logged, count, count)
}

/** "Started by your assistant" for a workout an assistant started through the server; null otherwise. */
fun OngoingWorkout.startedByText(context: Context): String? =
    if (startedBy == StartedBy.AGENT) context.getString(R.string.ongoing_started_by_agent) else null

/**
 * The workout in progress in this device's database, as an [OngoingWorkout],
 * or null when there is none: the active workout, its exercises and sets
 * (names in this device's language) and, for a workout started from a
 * routine, the routine's target sets read live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun ongoingWorkouts(context: Context, workouts: WorkoutRepository, routines: RoutineRepository): Flow<OngoingWorkout?> {
    val appContext = context.applicationContext ?: context
    return workouts.observeActiveWorkout().flatMapLatest { workout ->
        if (workout == null) {
            flowOf(null)
        } else {
            combine(workouts.observeExercises(workout.id), routineTargets(routines, workout.routineId)) { exercises, targets ->
                ongoingWorkout(workout, exercises.sortedBy { it.workoutExercise.position }, targets) { it.exercise.displayName(appContext) }
            }
        }
    }.distinctUntilChanged()
}

/** A routine's target sets per exercise id, read live; empty outside a routine (also the watch state's `targetSets`). */
internal fun routineTargets(routines: RoutineRepository, routineId: String?): Flow<Map<String, Int>> =
    if (routineId == null) {
        flowOf(emptyMap())
    } else {
        routines.observeRoutineExercises(routineId).map { rows ->
            rows.groupBy { it.routineExercise.exerciseId }.mapValues { (_, same) -> same.maxOf { it.routineExercise.targetSets } }
        }
    }

/** One [OngoingWorkout] from rows; [name] gives an exercise's displayed name. */
internal fun ongoingWorkout(
    workout: Workout,
    exercises: List<WorkoutExerciseWithSets>,
    targets: Map<String, Int>,
    name: (WorkoutExerciseWithSets) -> String,
): OngoingWorkout {
    val ongoing = exercises.map { e ->
        OngoingExercise(name(e), e.sortedSets.map { OngoingSet(it.completed, it.reps) }, targets[e.exercise.id])
    }
    val index = currentSet(ongoing.map { e -> e.sets.map { it.completed } })
    val set = index?.let { exercises[it.exercise].sortedSets[it.set] }
    return OngoingWorkout(
        workoutId = workout.id,
        startedAt = workout.startedAt,
        status = ongoingStatus(ongoing, index),
        startedBy = workout.startedBy,
        logSetId = set?.id,
        logSetHasReps = (set?.reps ?: 0) > 0,
    )
}
