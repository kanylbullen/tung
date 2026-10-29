package io.github.kanylbullen.tung.ongoing

import android.content.Context
import io.github.kanylbullen.tung.core.OngoingExercise
import io.github.kanylbullen.tung.core.OngoingSet
import io.github.kanylbullen.tung.core.OngoingStatus
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.StickyAction
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.formatWeight
import io.github.kanylbullen.tung.core.ongoingStatus
import io.github.kanylbullen.tung.core.stickyAction
import io.github.kanylbullen.tung.data.R
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ui.common.displayName
import io.github.kanylbullen.tung.wear.lastTouchedByTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.text.DecimalFormatSymbols

/**
 * A workout in progress as the ongoing notification shows it: everything it
 * needs, already resolved, so the notifier only draws.
 *
 * @param logSetId the set the notification's "Log set" ticks (the current set
 *   by the default rule); null when every set is ticked.
 * @param logSetWeightKg that set's weight in kg, for the Live Update chip on
 *   Android 16 ([chipText]); null with [logSetId].
 * @param logSetReps that set's reps, the same way; 0 means logging it would
 *   tick a set that does not count ([logSetHasReps]).
 * @param addSetWorkoutExerciseId with every set ticked, the workout exercise the
 *   notification's "Add set" adds a set to ([stickyAction]); null otherwise.
 */
data class OngoingWorkout(
    val workoutId: String,
    val startedAt: Long,
    val status: OngoingStatus,
    val startedBy: String? = null,
    val logSetId: String? = null,
    val logSetWeightKg: Double? = null,
    val logSetReps: Int? = null,
    val addSetWorkoutExerciseId: String? = null,
) {
    /**
     * False when the current set has 0 reps: logging it would tick a set that
     * does not count, so the action opens the workout instead, as the tick on
     * the row opens the reps sheet.
     */
    val logSetHasReps: Boolean get() = (logSetReps ?: 0) > 0
}

/**
 * The text of the Live Update's chip in the status bar (Android 16; see
 * `OngoingWorkoutNotifier`): the current set's numbers, "80×8", the weight
 * as the app writes it ([formatWeight], the locale's decimal separator) and
 * no spaces, since the chip is 96 dp wide and shows a text whole only under
 * seven characters. Null when that set has no reps yet (0 × 0 says nothing)
 * or when every set is ticked; the chip then shows the icon and, per the
 * Live Updates guide, the chronometer.
 */
fun OngoingWorkout.chipText(context: Context): String? {
    val weightKg = logSetWeightKg ?: return null
    val reps = logSetReps ?: return null
    if (reps <= 0) return null
    val weight = formatWeight(weightKg, DecimalFormatSymbols.getInstance().decimalSeparator)
    return context.getString(R.string.ongoing_chip_set, weight, reps)
}

/** The notification's body: "Bench press · set 3 of 5", "4 sets logged", or "Bench press · all sets logged". */
fun OngoingStatus.text(context: Context): String = when (this) {
    is OngoingStatus.AtSet -> context.getString(R.string.ongoing_at_set, exercise, set, of)
    is OngoingStatus.Logged -> context.resources.getQuantityString(R.plurals.ongoing_sets_logged, count, count)
    is OngoingStatus.AllLogged -> context.getString(R.string.ongoing_all_logged, exercise)
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
    val completed = ongoing.map { e -> e.sets.map { it.completed } }
    val index = currentSet(completed)
    val set = index?.let { exercises[it.exercise].sortedSets[it.set] }
    val touched = lastTouchedByTime(exercises)
    val addSet = if (index == null) stickyAction(completed, lastTouchedExercise = touched) as? StickyAction.AddSet else null
    return OngoingWorkout(
        workoutId = workout.id,
        startedAt = workout.startedAt,
        status = ongoingStatus(ongoing, index, touched),
        startedBy = workout.startedBy,
        logSetId = set?.id,
        logSetWeightKg = set?.weightKg,
        logSetReps = set?.reps,
        addSetWorkoutExerciseId = addSet?.let { exercises[it.exercise].workoutExercise.id },
    )
}
