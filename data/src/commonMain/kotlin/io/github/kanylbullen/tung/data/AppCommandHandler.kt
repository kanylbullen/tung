package io.github.kanylbullen.tung.data

import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.ExerciseResolver

/**
 * The app's [CommandHandler]: core's [CommandExecutor] over Room
 * ([WorkoutRepository.commandStore], one transaction per command, the same
 * rules the server runs, issue #87), plus the one thing that is the phone's:
 * after [Command.FinishWorkout] it asks for a sync (and Health Connect)
 * through [onWorkoutFinished]. Every change to training data that a screen,
 * the watch or the notification makes goes through [execute].
 */
class AppCommandHandler(
    workouts: WorkoutRepository,
    resolver: ExerciseResolver,
    /** After every `FinishWorkout`: the kept workout's id, or null when it had nothing logged and was discarded. */
    private val onWorkoutFinished: (workoutId: String?) -> Unit = {},
    /** Stamped on every workout this handler creates (`core.StartedBy`): `phone` on the phone, `watch` on a standalone watch. */
    startedBy: String? = null,
) : CommandHandler {

    private val executor = CommandExecutor(workouts.commandStore(), resolver, startedBy)

    override suspend fun execute(command: Command): CommandResult {
        val result = executor.execute(command)
        // A finished (or discarded) workout is worth syncing at once, whoever sent the command.
        if (command is Command.FinishWorkout && result is Done) onWorkoutFinished(result.ids[WORKOUT_ID])
        return result
    }
}
