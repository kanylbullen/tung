package io.github.kanylbullen.tung.core.command

/**
 * Runs [Command]s. One implementation per place that holds the data: the app
 * over Room today (`AppCommandHandler`), a server later. Every implementation
 * validates first ([validate]) and resolves names before writing anything
 * ([resolvePlan]), so a command means the same thing wherever it is executed.
 */
interface CommandHandler {
    suspend fun execute(command: Command): CommandResult
}

/** What a command produced. */
sealed interface CommandResult {
    /**
     * It ran. [ids] names what it created or acted on, under the keys in
     * [CommandIds]: `"workoutId"` for a started workout, `"setId"` for an added
     * set, and so on. Which keys a command returns is documented on
     * [CommandIds].
     */
    data class Done(val ids: Map<String, String> = emptyMap()) : CommandResult

    /** Nothing ran: some exercises could not be resolved without guessing. Ask the user, then send the command again. */
    data class NeedsAnswers(val questions: List<UnresolvedExercise>) : CommandResult

    /** Nothing ran: the command was invalid ([validate]) or refers to something that does not exist. */
    data class Rejected(val reasons: List<String>) : CommandResult
}

/** The id under [key] if the result is [CommandResult.Done] and has one. */
fun CommandResult.id(key: String): String? = (this as? CommandResult.Done)?.ids?.get(key)

/**
 * The keys of [CommandResult.Done.ids]. Stable, like [CommandNames]: the API
 * and MCP tools return them as they are.
 *
 * - [WORKOUT_ID]: StartWorkout, StartRoutine, ApplyPlan (the workout started or
 *   reused); FinishWorkout only when the workout was saved, absent when it was
 *   discarded for having nothing logged.
 * - [WORKOUT_EXERCISE_ID]: AddExercise; SelectCurrent.
 * - [SET_ID]: AddSet (the new set); LogSet (the set logged); SelectCurrent.
 * - [ADDED_SET_ID]: LogSet, when it added the next set.
 * - [EXERCISE_ID]: CreateExercise; AddExercise (the exercise it resolved to).
 * - [ROUTINE_ID]: SaveRoutine, SaveRoutinePlan, CreateRoutine.
 * - [ROUTINE_EXERCISE_ID]: AddRoutineExercise (the new row); [EXERCISE_ID] with it (the exercise it resolved to).
 * - [PROGRAM_ID]: CreateProgram.
 * - [PROGRAM_DAY_ID]: AddProgramDay.
 */
object CommandIds {
    const val WORKOUT_ID = "workoutId"
    const val WORKOUT_EXERCISE_ID = "workoutExerciseId"
    const val SET_ID = "setId"
    const val ADDED_SET_ID = "addedSetId"
    const val EXERCISE_ID = "exerciseId"
    const val ROUTINE_ID = "routineId"
    const val ROUTINE_EXERCISE_ID = "routineExerciseId"
    const val PROGRAM_ID = "programId"
    const val PROGRAM_DAY_ID = "programDayId"
}
