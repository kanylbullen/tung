package io.github.kanylbullen.tung.wear

import android.content.Context
import android.util.Log
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.wear.WatchExercise
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.core.wear.WatchRoutine
import io.github.kanylbullen.tung.core.wear.WatchSet
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.core.wear.toWatch
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerController
import io.github.kanylbullen.tung.ui.common.displayName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The watch's choice of current set, kept on the phone in memory. The phone's
 * own selection lives in the workout screen's ViewModel and is not visible
 * here; this one is what the watch's commands set ([WearCommandHandler]).
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
 * Mirrors the active workout to the watch: observes the workout in progress,
 * its exercises and sets, the stored rest timer and the routines, and writes a
 * [WatchWorkoutState] through [writer] on every change, debounced by
 * [DEBOUNCE_MILLIS].
 *
 * It runs for as long as the app's process does: started from
 * [io.github.kanylbullen.tung.TungApp] when Play services is present, and
 * again (a no-op then) by [WearSyncService] when the system starts the process
 * for a command, which also publishes once right after applying it
 * ([publishNow]). When Android kills the idle process, nothing publishes until
 * it runs again; the DataItem keeps the last state meanwhile, and the phone
 * cannot change the workout without the process running anyway.
 *
 * @param restChanges emits whenever the stored rest timer may have changed
 *   (the settings DataStore's data); the timer itself is read through [restTimer].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class WearStatePublisher(
    context: Context,
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val restTimer: RestTimerController,
    private val restChanges: Flow<Any?>,
    private val writer: WearStateWriter,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext
    val selection = WearSelectionHolder()

    private val started = AtomicBoolean(false)
    private val writeLock = Mutex()

    /** The last state written, with `updatedAt` zeroed: an unchanged state is not written again. */
    private var lastWritten: WatchWorkoutState? = null

    /** Last session's sets per (workout, exercise). They cannot change while that workout is in progress. */
    private val lastTimeCache = ConcurrentHashMap<Pair<String, String>, List<LoggedSet>>()

    /** Starts observing and publishing. Calling it again does nothing. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            states().debounce(DEBOUNCE_MILLIS).collect { write(it) }
        }
    }

    /** Builds the current state and writes it now, without waiting for the observer. */
    suspend fun publishNow() = write(snapshot())

    /** The state as it is right now. */
    suspend fun snapshot(): WatchWorkoutState = states().first()

    private fun states(): Flow<WatchWorkoutState> =
        combine(activeParts(), routines.observeRoutines(), selection.state) { parts, routineRows, sel ->
            build(parts, routineRows, sel)
        }

    private fun activeParts(): Flow<ActiveParts?> =
        workouts.observeActiveWorkout().flatMapLatest { workout ->
            if (workout == null) {
                flowOf(null)
            } else {
                combine(
                    workouts.observeExercises(workout.id),
                    restChanges.map { restTimer.stored(workout.id) },
                ) { exercises, rest -> ActiveParts(workout, exercises.sortedBy { it.workoutExercise.position }, rest) }
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
            )
        }
        return WatchWorkoutState(
            workoutId = workoutId,
            startedAt = parts.workout.startedAt,
            exercises = exercises,
            currentSetId = currentSetId(exercises, sel.takeIf { it.workoutId == workoutId }),
            rest = parts.rest?.takeIf { it.isRunningAt(now) }?.toWatch(),
            routines = watchRoutines,
            updatedAt = now,
        )
    }

    private suspend fun lastTime(workoutId: String, exerciseId: String): List<LoggedSet> {
        val key = workoutId to exerciseId
        lastTimeCache[key]?.let { return it }
        return workouts.previousSessionSets(exerciseId, workoutId).also { lastTimeCache[key] = it }
    }

    private suspend fun write(state: WatchWorkoutState) = writeLock.withLock {
        val comparable = state.copy(updatedAt = 0L)
        if (comparable == lastWritten) return@withLock
        try {
            writer.write(WatchProtocol.encodeState(state))
            lastWritten = comparable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No watch app, no Wear OS app on the phone, or the API is unavailable: not an error for the phone.
            Log.w(TAG, "Could not publish the workout to the watch", e)
        }
    }

    private data class ActiveParts(
        val workout: Workout,
        val exercises: List<WorkoutExerciseWithSets>,
        val rest: RestTimer?,
    )

    companion object {
        const val DEBOUNCE_MILLIS = 100L
        private const val TAG = "WearStatePublisher"

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
    }
}
