package io.github.kanylbullen.tung.wear

import android.content.Context
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.StickyAction
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.stickyAction
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchRoutine
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.core.wear.toWatch
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.ongoing.routineTargets
import io.github.kanylbullen.tung.timer.RestTimerController
import io.github.kanylbullen.tung.ui.common.displayName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.util.concurrent.ConcurrentHashMap

/**
 * The watch's choice of current set, kept in memory next to the database it
 * acts on: on the phone for a watch in companion mode, on the watch itself
 * when it runs standalone. The phone's own selection lives in the workout
 * screen's ViewModel and is not visible here.
 */
data class WearSelection(
    val workoutId: String? = null,
    val selectedSetId: String? = null,
    val touchedWorkoutExerciseId: String? = null,
)

class WearSelectionHolder {
    private val mutable = MutableStateFlow(WearSelection())
    val state: StateFlow<WearSelection> = mutable

    fun set(selection: WearSelection) {
        mutable.value = selection
    }

    fun clear() = set(WearSelection())
}

/**
 * The [WatchWorkoutState] of this device's database, as a Flow: the workout in
 * progress, its exercises and sets (names in this device's language, "Last
 * time" from this device's history), the stored rest timer, the routines and
 * [selection]'s current set. The phone publishes it to a watch in companion
 * mode (`app/.../wear/WearStatePublisher`); a standalone watch shows its own.
 *
 * @param restChanges emits whenever the stored rest timer may have changed
 *   (the settings DataStore's data); the timer itself is read through [restTimer].
 * @param serverUrl the server this device is signed in to, carried in the state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchStateSource(
    context: Context,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val restTimer: RestTimerController,
    private val restChanges: Flow<Any?>,
    val selection: WearSelectionHolder = WearSelectionHolder(),
    private val serverUrl: Flow<String?> = flowOf(null),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext ?: context

    /** Last session's sets per (workout, exercise). They cannot change while that workout is in progress. */
    private val lastTimeCache = ConcurrentHashMap<Pair<String, String>, List<LoggedSet>>()

    fun states(): Flow<WatchWorkoutState> =
        combine(activeParts(), routines.observeRoutines(), selection.state, serverUrl) { parts, routineRows, sel, url ->
            build(parts, routineRows, sel).copy(serverUrl = url)
        }

    private fun activeParts(): Flow<ActiveParts?> =
        workouts.observeActiveWorkout().flatMapLatest { workout ->
            if (workout == null) {
                flowOf(null)
            } else {
                combine(
                    workouts.observeExercises(workout.id),
                    restChanges.map { restTimer.stored(workout.id) },
                    routineTargets(routines, workout.routineId),
                ) { exercises, rest, targets ->
                    ActiveParts(workout, exercises.sortedBy { it.workoutExercise.position }, rest, targets)
                }
            }
        }

    private suspend fun build(
        parts: ActiveParts?,
        routineRows: List<RoutineSummaryRow>,
        sel: WearSelection,
    ): WatchWorkoutState {
        val now = clock()
        val watchRoutines = routineRows.map { WatchRoutine(it.id, it.name, it.exerciseCount) }
        if (parts == null) return WatchWorkoutState(routines = watchRoutines, updatedAt = now)

        val workoutId = parts.workout.id
        val exercises = parts.exercises.map { e ->
            WatchExercise(
                workoutExerciseId = e.workoutExercise.id,
                name = e.exercise.displayName(appContext),
                sets = e.sortedSets.map { WatchSet(it.id, it.position, it.weightKg, it.reps, it.completed) },
                lastTime = lastTime(workoutId, e.exercise.id).map { it.toWatch() },
                targetSets = parts.targets[e.exercise.id],
            )
        }
        val mine = sel.takeIf { it.workoutId == workoutId }
        val current = currentSetId(exercises, mine)
        return WatchWorkoutState(
            workoutId = workoutId,
            startedAt = parts.workout.startedAt,
            exercises = exercises,
            currentSetId = current,
            addSetWorkoutExerciseId = if (current == null) {
                addSetWorkoutExerciseId(exercises, mine, lastTouchedByTime(parts.exercises))
            } else {
                null
            },
            rest = parts.rest?.takeIf { it.isRunningAt(now) }?.toWatch(),
            routines = watchRoutines,
            updatedAt = now,
            startedBy = parts.workout.startedBy,
        )
    }

    private suspend fun lastTime(workoutId: String, exerciseId: String): List<LoggedSet> {
        val key = workoutId to exerciseId
        lastTimeCache[key]?.let { return it }
        return workouts.previousSessionSets(exerciseId, workoutId).also { lastTimeCache[key] = it }
    }

    private data class ActiveParts(
        val workout: Workout,
        val exercises: List<WorkoutExerciseWithSets>,
        val rest: RestTimer?,
        val targets: Map<String, Int>,
    )

    companion object {
        /**
         * The current set by the phone's rule ([currentSet]), with the watch's
         * selection and touched exercise in place of the screen's.
         */
        fun currentSetId(exercises: List<WatchExercise>, sel: WearSelection?): String? {
            val completed = exercises.map { e -> e.sets.map { it.completed } }
            val selected = sel?.selectedSetId?.let { id ->
                exercises.withIndex().firstNotNullOfOrNull { (ei, e) ->
                    e.sets.indexOfFirst { it.setId == id }.takeIf { it >= 0 }?.let { SetIndex(ei, it) }
                }
            }
            val touched = sel?.touchedWorkoutExerciseId?.let { id ->
                exercises.indexOfFirst { it.workoutExerciseId == id }.takeIf { it >= 0 }
            }
            val index = currentSet(completed, selected, touched) ?: return null
            return exercises[index.exercise].sets[index.set].setId
        }

        /**
         * With every set ticked, the exercise the log button's "Add set" adds
         * to ([stickyAction]): the watch's touched exercise, else [fallback]
         * (an index into [exercises]), else the last one. Null while a set is
         * open or with no exercises.
         */
        fun addSetWorkoutExerciseId(exercises: List<WatchExercise>, sel: WearSelection?, fallback: Int? = null): String? {
            val touched = sel?.touchedWorkoutExerciseId?.let { id ->
                exercises.indexOfFirst { it.workoutExerciseId == id }.takeIf { it >= 0 }
            } ?: fallback
            val action = stickyAction(exercises.map { e -> e.sets.map { it.completed } }, lastTouchedExercise = touched)
            return (action as? StickyAction.AddSet)?.let { exercises[it.exercise].workoutExerciseId }
        }
    }
}

/**
 * The exercise whose sets changed last (the newest `updatedAt` of its sets),
 * as an index into [exercises]: the best guess at "the exercise just logged"
 * for a reader that cannot see a screen's touched exercise (the ongoing
 * notification, a watch whose own selection is empty). Null with no sets.
 */
internal fun lastTouchedByTime(exercises: List<WorkoutExerciseWithSets>): Int? =
    exercises.withIndex()
        .mapNotNull { (i, e) -> e.sets.maxOfOrNull { it.updatedAt }?.let { i to it } }
        .maxByOrNull { it.second }
        ?.first
