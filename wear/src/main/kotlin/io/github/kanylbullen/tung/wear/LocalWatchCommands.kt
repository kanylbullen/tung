package io.github.kanylbullen.tung.wear

import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.SetIndex
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.id
import io.github.kanylbullen.tung.core.nextCurrentAfterLogging
import io.github.kanylbullen.tung.core.restSecondsFor
import io.github.kanylbullen.tung.core.wear.WatchCommand
import io.github.kanylbullen.tung.data.Workout
import io.github.kanylbullen.tung.data.WorkoutExerciseWithSets
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.timer.RestTimerController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A standalone watch's taps, applied on the watch. Every change to training
 * data is a [Command] executed by the shared [CommandHandler] (the same
 * `AppCommandHandler` the phone's screens use), so validation, routine
 * target sets, one workout in progress and finish semantics are the phone's.
 * What is view state on the phone stays here, as the phone's workout screen
 * keeps it in its ViewModel: the current-set selection ([selection]) and the
 * rest timer ([restTimer], stored and alarmed on the watch).
 *
 * The phone's `WearCommandHandler` does the same for a watch in companion
 * mode, from a command that came over the Data Layer.
 *
 * @param workouts read only: the workout in progress and its sets, to check a
 *   tap against what is on screen and to move the current set.
 */
class LocalWatchCommands(
    private val commands: CommandHandler,
    private val workouts: WorkoutRepository,
    private val restTimer: RestTimerController,
    private val selection: WearSelectionHolder,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    /** True when [command] changed something. */
    suspend fun apply(command: WatchCommand): Boolean = lock.withLock {
        when (command) {
            is WatchCommand.LogSet -> logSet(command.setId)
            is WatchCommand.AddSet -> addSet(command.workoutExerciseId)
            is WatchCommand.SetWeight -> edit(command.setId) { Command.SetWeight(command.setId, command.kg) }
            is WatchCommand.SetReps -> edit(command.setId) { Command.SetReps(command.setId, command.reps) }
            is WatchCommand.SelectSet -> selectSet(command.setId)
            is WatchCommand.StartWorkout -> started(commands.execute(Command.StartWorkout()))
            is WatchCommand.StartRoutine -> started(commands.execute(Command.StartRoutine(command.routineId)))
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
     * Log set, as the phone's button does it: `Command.LogSet` ticks the set
     * and adds none (issue #64), then the current set moves ([nextCurrentAfterLogging]) and the
     * exercise's rest starts. A set with 0 reps is not logged; the watch asks
     * for reps first.
     */
    private suspend fun logSet(setId: String): Boolean {
        val workout = active() ?: return false
        val (exercise, set) = find(workout, setId) ?: return false
        if (set.completed || set.reps <= 0) return false
        if (commands.execute(Command.LogSet(workout.id, setId)) !is CommandResult.Done) return false

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
     * Add set, the log button's other face once every set is ticked:
     * `Command.AddSet` (a copy of the exercise's last set), and the new set
     * becomes current. Ignored for an exercise not in the workout in progress.
     */
    private suspend fun addSet(workoutExerciseId: String): Boolean {
        val workout = active() ?: return false
        if (exercisesOf(workout).none { it.workoutExercise.id == workoutExerciseId }) return false
        val result = commands.execute(Command.AddSet(workoutExerciseId)) as? CommandResult.Done ?: return false
        selection.set(WearSelection(workout.id, result.id(CommandIds.SET_ID), workoutExerciseId))
        return true
    }

    /** Weight or reps: the command validates the value; the edit moves only the touched exercise, as on the phone. */
    private suspend fun edit(setId: String, command: () -> Command): Boolean {
        val workout = active() ?: return false
        val (exercise, _) = find(workout, setId) ?: return false
        if (commands.execute(command()) !is CommandResult.Done) return false
        val current = selection.state.value.takeIf { it.workoutId == workout.id }
        selection.set(WearSelection(workout.id, current?.selectedSetId, exercise.workoutExercise.id))
        return true
    }

    private suspend fun selectSet(setId: String): Boolean {
        val workout = active() ?: return false
        val (exercise, set) = find(workout, setId) ?: return false
        if (set.completed) return false
        selection.set(WearSelection(workout.id, setId, exercise.workoutExercise.id))
        return true
    }

    private fun started(result: CommandResult): Boolean {
        val done = result is CommandResult.Done
        if (done) selection.clear()
        return done
    }

    private suspend fun finish(): Boolean {
        val workout = active() ?: return false
        restTimer.set(workout.id, null)
        val result = commands.execute(Command.FinishWorkout(workout.id))
        selection.clear()
        return result is CommandResult.Done
    }

    private suspend fun active(): Workout? = workouts.observeActiveWorkout().first()

    private suspend fun exercisesOf(workout: Workout): List<WorkoutExerciseWithSets> =
        workouts.observeExercises(workout.id).first().sortedBy { it.workoutExercise.position }

    private suspend fun find(workout: Workout, setId: String) =
        exercisesOf(workout).firstNotNullOfOrNull { e -> e.sets.firstOrNull { it.id == setId }?.let { e to it } }
}
