package io.github.kanylbullen.tung.wear

import android.util.Log
import io.github.kanylbullen.tung.core.MAX_REPS
import io.github.kanylbullen.tung.core.MAX_WEIGHT_KG
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.nextCurrentAfterLogging
import io.github.kanylbullen.tung.core.restSecondsFor
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.core.wear.WatchProtocol
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlin.math.roundToLong

/**
 * Applies a [WatchCommand] from the watch through the same repositories the
 * phone's screens use, so a set logged on the wrist is the same row as one
 * logged on the phone. Every command is checked against the workout in
 * progress: a set that is not in it, a value out of range or a duplicate
 * [WatchCommand.commandId] is ignored.
 *
 * What it cannot reach in v0: the workout screen's ViewModel. If the phone has
 * the workout open, a set logged on the watch shows at once (Room), but that
 * screen keeps its own rest countdown and its own current-set selection.
 */
class WearCommandHandler(
    private val workouts: WorkoutRepository,
    private val routines: RoutineRepository,
    private val restTimer: RestTimerController,
    private val selection: WearSelectionHolder,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** Recent command ids, oldest first; a re-sent command is applied once. */
    private val seen = object : LinkedHashMap<String, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?): Boolean = size > SEEN_LIMIT
    }

    /** Decodes [json] and applies it. False when it does not parse or was not applied. */
    suspend fun handle(json: String): Boolean {
        val command = try {
            WatchProtocol.decodeCommand(json)
        } catch (e: SerializationException) {
            Log.w(TAG, "Ignoring a command that does not parse", e)
            return false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Ignoring a command that does not parse", e)
            return false
        }
        return apply(command)
    }

    /** True when [command] changed something. */
    suspend fun apply(command: WatchCommand): Boolean = lock.withLock {
        if (seen.containsKey(command.commandId)) return@withLock false
        seen[command.commandId] = Unit
        when (command) {
            is WatchCommand.LogSet -> logSet(command.setId)
            is WatchCommand.AddSet -> addSet(command.workoutExerciseId)
            is WatchCommand.SetWeight -> setWeight(command.setId, command.kg)
            is WatchCommand.SetReps -> setReps(command.setId, command.reps)
            is WatchCommand.SelectSet -> selectSet(command.setId)
            is WatchCommand.StartWorkout -> {
                workouts.startOrResumeWorkout(StartedBy.WATCH)
                selection.clear()
                true
            }
            is WatchCommand.StartRoutine -> {
                val started = routines.start(command.routineId, startedBy = StartedBy.WATCH) != null
                if (started) selection.clear()
                started
            }
            is WatchCommand.FinishWorkout -> finish()
            is WatchCommand.SkipRest -> {
                val workout = active() ?: return@withLock false
                restTimer.set(workout.id, null)
                true
            }
            is WatchCommand.AddRest30 -> {
                val workout = active() ?: return@withLock false
                val timer = restTimer.stored(workout.id) ?: return@withLock false
                restTimer.set(workout.id, timer.plus(clock()))
                true
            }
        }
    }

    /**
     * Log set, as the phone's button does it: tick, nothing more (issue #64:
     * `log_set` adds no set by default, [WorkoutRepository.logSet]), move the current set
     * ([nextCurrentAfterLogging]) and start the exercise's rest. A set with 0
     * reps is not logged; the watch asks for reps first.
     */
    private suspend fun logSet(setId: String): Boolean {
        val workout = active() ?: return false
        val (exercise, set) = find(workout, setId) ?: return false
        if (set.completed || set.reps <= 0) return false
        workouts.logSet(setId)

        val after = exercisesOf(workout)
        val exerciseIndex = after.indexOfFirst { it.workoutExercise.id == exercise.workoutExercise.id }
        val setIndex = after.getOrNull(exerciseIndex)?.sortedSets?.indexOfFirst { it.id == setId } ?: -1
        val next = if (exerciseIndex >= 0 && setIndex >= 0) {
            nextCurrentAfterLogging(after.map { e -> e.sortedSets.map { it.completed } }, SetIndex(exerciseIndex, setIndex))
        } else {
            null
        }
        val nextExercise = next?.let { after[it.exercise] }
        selection.set(
            WearSelection(
                workoutId = workout.id,
                selectedSetId = next?.let { nextExercise?.sortedSets?.get(it.set)?.id },
                touchedWorkoutExerciseId = nextExercise?.workoutExercise?.id ?: exercise.workoutExercise.id,
            ),
        )

        val seconds = restSecondsFor(exercise.exercise.restSeconds, restTimer.defaultSeconds.first())
        restTimer.set(workout.id, RestTimer.start(clock(), seconds))
        return true
    }

    /**
     * Add set, the log button's other face once every set is ticked: one set
     * on [workoutExerciseId], a copy of its last ([WorkoutRepository.addSet]),
     * which becomes the current set. Ignored for an exercise that is not in
     * the workout in progress.
     */
    private suspend fun addSet(workoutExerciseId: String): Boolean {
        val workout = active() ?: return false
        if (exercisesOf(workout).none { it.workoutExercise.id == workoutExerciseId }) return false
        val added = workouts.addSet(workoutExerciseId) ?: return false
        selection.set(WearSelection(workout.id, added, workoutExerciseId))
        return true
    }

    private suspend fun setWeight(setId: String, kg: Double): Boolean {
        if (kg.isNaN() || kg < 0.0 || kg > MAX_WEIGHT_KG) return false
        val workout = active() ?: return false
        val (exercise, _) = find(workout, setId) ?: return false
        workouts.updateWeight(setId, (kg * 100).roundToLong() / 100.0)
        touch(workout, exercise)
        return true
    }

    private suspend fun setReps(setId: String, reps: Int): Boolean {
        if (reps !in 0..MAX_REPS) return false
        val workout = active() ?: return false
        val (exercise, _) = find(workout, setId) ?: return false
        workouts.updateReps(setId, reps)
        touch(workout, exercise)
        return true
    }

    private suspend fun selectSet(setId: String): Boolean {
        val workout = active() ?: return false
        val (exercise, set) = find(workout, setId) ?: return false
        if (set.completed) return false
        selection.set(WearSelection(workout.id, setId, exercise.workoutExercise.id))
        return true
    }

    private suspend fun finish(): Boolean {
        val workout = active() ?: return false
        restTimer.set(workout.id, null)
        workouts.finishWorkout(workout.id)
        selection.clear()
        return true
    }

    /** Edits move only the touched exercise and keep an explicit selection, as on the phone. */
    private fun touch(workout: Workout, exercise: WorkoutExerciseWithSets) {
        val current = selection.state.value.takeIf { it.workoutId == workout.id }
        selection.set(
            WearSelection(workout.id, current?.selectedSetId, exercise.workoutExercise.id),
        )
    }

    private suspend fun active(): Workout? = workouts.observeActiveWorkout().first()

    private suspend fun exercisesOf(workout: Workout): List<WorkoutExerciseWithSets> =
        workouts.observeExercises(workout.id).first().sortedBy { it.workoutExercise.position }

    private suspend fun find(workout: Workout, setId: String) =
        exercisesOf(workout).firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.id == setId }?.let { e to it } }

    private companion object {
        const val TAG = "WearCommandHandler"
        const val SEEN_LIMIT = 64
    }
}
