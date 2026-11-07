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
 * rules the server runs, issue #87), plus what is the device's: after
 * [Command.FinishWorkout] it asks for a sync (and Health Connect) through
 * [onWorkoutFinished], and after any other change for one soon through
 * [onChanged], so another device sees a workout in progress (issue #199). Every change to training data that a screen,
 * the watch or the notification makes goes through [execute].
 */
class AppCommandHandler(
    workouts: WorkoutRepository,
    resolver: ExerciseResolver,
    /** After every `FinishWorkout`: the kept workout's id, or null when it had nothing logged and was discarded. */
    private val onWorkoutFinished: (workoutId: String?) -> Unit = {},
    /** After every other command that went through, except `select_current`, which writes nothing. */
    private val onChanged: () -> Unit = {},
    /** Stamped on every workout this handler creates (`core.StartedBy`): `phone` on the phone, `watch` on a standalone watch. */
    startedBy: String? = null,
) : CommandHandler {

    private val executor = CommandExecutor(workouts.commandStore(), resolver, startedBy)

    override suspend fun execute(command: Command): CommandResult {
        val result = executor.execute(command)
        // A finished (or discarded) workout is worth syncing at once, whoever sent the command.
        if (result is Done) {
            when (command) {
                is Command.FinishWorkout -> onWorkoutFinished(result.ids[WORKOUT_ID])
                is Command.SelectCurrent -> Unit
                else -> onChanged()
            }
        }
        return result
    }
}
