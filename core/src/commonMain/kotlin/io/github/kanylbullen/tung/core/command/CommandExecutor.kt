package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.command.CommandResult.NeedsAnswers
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected

/**
 * Where a [CommandExecutor] runs a command: one transaction per command over
 * a [CommandTx], opened and committed by the store (issue #87). Room in
 * `data/` (`db.withTransaction`), Exposed on the server (under the user's
 * lock, rolling back a result that is not [CommandResult.Done] as a
 * guarantee; the rules never write before a refusal), a memory store in
 * tests. Names are resolved inside it too, because the server's resolver
 * reads the user's rows.
 */
interface CommandStore {
    suspend fun transaction(block: suspend (CommandTx) -> CommandResult): CommandResult
}

/**
 * The one [CommandHandler]: every change to training data on every
 * platform. It validates ([validate]) before anything else, then in one
 * transaction of the [store] resolves exercise names (core's [resolveRef],
 * [resolvePlan], [resolveRoutinePlan]: anything unknown is [NeedsAnswers]
 * and nothing is written), makes the row an extended-catalogue entry
 * resolves to ([rowFor], the same deterministic id on every device) and
 * runs the [rules]. The platforms wrap it for what is theirs alone: the
 * app's `AppCommandHandler` asks for a sync after `finish_workout`, the
 * server's `ServerCommandHandler` caps names for the REST API and wakes the
 * user's other devices.
 *
 * [startedBy] (`core.StartedBy`) is stamped on every workout this executor
 * starts: `phone` on the phone, `watch` on a standalone watch, `agent` on
 * the server.
 */
class CommandExecutor(
    private val store: CommandStore,
    private val resolver: ExerciseResolver,
    private val startedBy: String?,
    private val rules: CommandRules = CommandRules(),
) : CommandHandler {

    override suspend fun execute(command: Command): CommandResult {
        (validate(command) as? Validation.Invalid)?.let { return Rejected(it.reasons) }
        return store.transaction { tx -> perform(tx, command) }
    }

    private suspend fun perform(tx: CommandTx, command: Command): CommandResult = when (command) {
        is Command.StartWorkout -> {
            val routineId = command.routineId
            if (routineId != null) rules.startRoutine(tx, routineId, command.programDayId, startedBy) else rules.startWorkout(tx, startedBy)
        }
        is Command.StartRoutine -> rules.startRoutine(tx, command.routineId, command.programDayId, startedBy)
        is Command.AddExercise -> addExercise(tx, command)
        is Command.RemoveExercise -> rules.removeExercise(tx, command.workoutExerciseId)
        is Command.AddSet -> rules.addSet(tx, command.workoutExerciseId, command.count)
        is Command.RemoveSet -> rules.removeSet(tx, command.setId)
        is Command.SetWeight -> rules.setWeight(tx, command.setId, command.kg, command.applyToOpenSets)
        is Command.SetReps -> rules.setReps(tx, command.setId, command.reps, command.applyToOpenSets)
        is Command.TickSet -> rules.tickSet(tx, command.setId, command.done)
        is Command.LogSet -> rules.logSet(tx, command)
        is Command.SelectCurrent -> rules.selectCurrent(tx, command)
        is Command.FinishWorkout -> rules.finishWorkout(tx, command.workoutId)
        is Command.DeleteWorkout -> rules.deleteWorkout(tx, command.workoutId)
        is Command.ApplyPlan -> applyPlan(tx, command)
        is Command.SetRest -> rules.setRest(tx, command.exerciseId, command.seconds)
        is Command.CreateExercise -> rules.createExercise(tx, command.name, command.muscleGroup, command.equipment)
        is Command.SaveRoutine -> rules.saveRoutine(tx, command.workoutId, command.name)
        is Command.SaveRoutinePlan -> saveRoutinePlan(tx, command)
        is Command.CreateRoutine -> rules.createRoutine(tx, command.name)
        is Command.RenameRoutine -> rules.renameRoutine(tx, command.routineId, command.name)
        is Command.DeleteRoutine -> rules.deleteRoutine(tx, command.routineId)
        is Command.MoveRoutineExercise -> rules.moveRoutineExercise(tx, command.routineExerciseId, command.delta)
        is Command.AdjustTargetSets -> rules.adjustTargetSets(tx, command.routineExerciseId, command.delta)
        is Command.AddRoutineExercise -> addRoutineExercise(tx, command)
        is Command.RemoveRoutineExercise -> rules.removeRoutineExercise(tx, command.routineExerciseId)
        is Command.SetTargetReps -> rules.setTargetReps(tx, command.routineExerciseId, command.reps)
        is Command.CreateProgram -> rules.createProgram(tx, command.name, command.mode)
        is Command.RenameProgram -> rules.renameProgram(tx, command.programId, command.name)
        is Command.SetProgramMode -> rules.setProgramMode(tx, command.programId, command.mode)
        is Command.ActivateProgram -> rules.activateProgram(tx, command.programId)
        is Command.DeactivateProgram -> rules.deactivateProgram(tx, command.programId)
        is Command.DeleteProgram -> rules.deleteProgram(tx, command.programId)
        is Command.AddProgramDay -> rules.addProgramDay(tx, command.programId, command.routineId)
        is Command.RemoveProgramDay -> rules.removeProgramDay(tx, command.dayId)
        is Command.MoveProgramDay -> rules.moveProgramDay(tx, command.dayId, command.delta)
        is Command.ToggleProgramWeekday -> rules.toggleProgramWeekday(tx, command.dayId, command.weekday)
    }

    /** Resolves first (a question writes nothing), then checks the workout, so a refusal makes no row either, then the rule. */
    private suspend fun addExercise(tx: CommandTx, command: Command.AddExercise): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        tx.workout(command.workoutId) ?: return Rejected(listOf("no workout ${command.workoutId}"))
        return rules.addExercise(tx, command.workoutId, rowFor(tx, exercise), command.sets)
    }

    /** Like [addExercise], for a saved routine. */
    private suspend fun addRoutineExercise(tx: CommandTx, command: Command.AddRoutineExercise): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        tx.routine(command.routineId) ?: return Rejected(listOf("no routine ${command.routineId}"))
        return rules.addRoutineExercise(tx, command.routineId, rowFor(tx, exercise), command.targetSets, command.targetReps)
    }

    /** Resolves the whole plan first (anything unknown → questions, nothing written), then the rows, then the rule. */
    private suspend fun applyPlan(tx: CommandTx, command: Command.ApplyPlan): CommandResult {
        val plan = when (val resolution = resolvePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        return rules.applyPlan(tx, plan.exercises.map { rowFor(tx, it.exercise) to it.sets }, startedBy)
    }

    private suspend fun saveRoutinePlan(tx: CommandTx, command: Command.SaveRoutinePlan): CommandResult {
        val plan = when (val resolution = resolveRoutinePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        return rules.saveRoutinePlan(tx, plan.name, plan.exercises.map { RoutineExercisePlan(rowFor(tx, it.exercise), it.targetSets, it.targetReps) })
    }

    /**
     * The id of [candidate]'s row. An extended-catalogue entry gets its row
     * the first time it is used ([CommandTx.ensureExerciseRow]): its English
     * name, not custom, following the default rest, under the deterministic
     * id the resolver gave it, so the same entry is the same row on every
     * device.
     */
    private suspend fun rowFor(tx: CommandTx, candidate: ExerciseCandidate): String {
        val extendedId = candidate.extendedId ?: return candidate.id
        val muscleGroup = requireNotNull(candidate.muscleGroup) { "the resolver gave extended entry $extendedId no muscle group" }
        tx.ensureExerciseRow(
            ExerciseRow(candidate.id, candidate.displayName, nameKey = null, muscleGroup, candidate.equipment ?: Equipment.OTHER, isCustom = false, restSeconds = null),
        )
        return candidate.id
    }
}
