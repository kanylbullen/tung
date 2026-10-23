package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.NeedsAnswers
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.PlanResolution
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.RoutineExercisePlan
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.resolvePlan
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.command.resolveRoutinePlan
import io.github.kanylbullen.tung.core.command.validate

/**
 * The app's one [CommandHandler]: every change to training data that a
 * screen makes goes through [execute], which validates ([validate]), resolves
 * exercise names ([resolveRef], [resolvePlan]) and then runs the command.
 * Every command runs core's [CommandExecutor] over Room
 * ([WorkoutRepository.commandTx]) in one transaction, the same rules the
 * server runs (issue #87); this class only maps commands onto it, makes the
 * row an extended-catalogue entry resolves to ([rowFor]) and asks for a sync
 * after [Command.FinishWorkout] (through [onWorkoutFinished]), so the API
 * and the MCP server that call the same commands get the same behaviour as
 * the buttons.
 */
class AppCommandHandler(
    private val db: TungDatabase,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val resolver: ExerciseResolver,
    private val extendedCatalog: ExtendedCatalog,
    /** After every `FinishWorkout`: the kept workout's id, or null when it had nothing logged and was discarded. */
    private val onWorkoutFinished: (workoutId: String?) -> Unit = {},
    /** Stamped on every workout this handler creates (`core.StartedBy`): `phone` on the phone, `watch` on a standalone watch. */
    private val startedBy: String? = null,
) : CommandHandler {

    private val executor = CommandExecutor()

    override suspend fun execute(command: Command): CommandResult {
        (validate(command) as? Validation.Invalid)?.let { return Rejected(it.reasons) }
        val result = perform(command)
        // A finished (or discarded) workout is worth syncing at once, whoever sent the command.
        if (command is Command.FinishWorkout && result is Done) onWorkoutFinished(result.ids[WORKOUT_ID])
        return result
    }

    private suspend fun perform(command: Command): CommandResult {
        return when (command) {
            is Command.StartWorkout -> {
                val routineId = command.routineId
                if (routineId != null) {
                    startRoutine(routineId, command.programDayId)
                } else {
                    db.withTransaction { executor.startWorkout(workouts.commandTx(), startedBy) }
                }
            }
            is Command.StartRoutine -> startRoutine(command.routineId, command.programDayId)
            is Command.AddExercise -> addExercise(command)
            is Command.RemoveExercise -> db.withTransaction { executor.removeExercise(workouts.commandTx(), command.workoutExerciseId) }
            is Command.AddSet -> db.withTransaction { executor.addSet(workouts.commandTx(), command.workoutExerciseId, command.count) }
            is Command.RemoveSet -> db.withTransaction { executor.removeSet(workouts.commandTx(), command.setId) }
            is Command.SetWeight ->
                db.withTransaction { executor.setWeight(workouts.commandTx(), command.setId, command.kg, command.applyToOpenSets) }
            is Command.SetReps ->
                db.withTransaction { executor.setReps(workouts.commandTx(), command.setId, command.reps, command.applyToOpenSets) }
            is Command.TickSet -> db.withTransaction { executor.tickSet(workouts.commandTx(), command.setId, command.done) }
            is Command.LogSet -> db.withTransaction { executor.logSet(workouts.commandTx(), command) }
            is Command.SelectCurrent -> executor.selectCurrent(workouts.commandTx(), command)
            // The id comes back only when the workout was kept; one with nothing logged is gone.
            is Command.FinishWorkout -> db.withTransaction { executor.finishWorkout(workouts.commandTx(), command.workoutId) }
            is Command.DeleteWorkout -> db.withTransaction { executor.deleteWorkout(workouts.commandTx(), command.workoutId) }
            is Command.ApplyPlan -> applyPlan(command)
            is Command.SetRest -> db.withTransaction { executor.setRest(workouts.commandTx(), command.exerciseId, command.seconds) }
            is Command.CreateExercise ->
                db.withTransaction { executor.createExercise(workouts.commandTx(), command.name, command.muscleGroup, command.equipment) }
            is Command.SaveRoutine -> db.withTransaction { executor.saveRoutine(workouts.commandTx(), command.workoutId, command.name) }
            is Command.SaveRoutinePlan -> saveRoutinePlan(command)
            is Command.CreateRoutine -> db.withTransaction { executor.createRoutine(workouts.commandTx(), command.name) }
            is Command.RenameRoutine -> db.withTransaction { executor.renameRoutine(workouts.commandTx(), command.routineId, command.name) }
            is Command.DeleteRoutine -> db.withTransaction { executor.deleteRoutine(workouts.commandTx(), command.routineId) }
            is Command.MoveRoutineExercise ->
                db.withTransaction { executor.moveRoutineExercise(workouts.commandTx(), command.routineExerciseId, command.delta) }
            is Command.AdjustTargetSets ->
                db.withTransaction { executor.adjustTargetSets(workouts.commandTx(), command.routineExerciseId, command.delta) }
            is Command.AddRoutineExercise -> addRoutineExercise(command)
            is Command.RemoveRoutineExercise ->
                db.withTransaction { executor.removeRoutineExercise(workouts.commandTx(), command.routineExerciseId) }
            is Command.SetTargetReps -> db.withTransaction { executor.setTargetReps(workouts.commandTx(), command.routineExerciseId, command.reps) }
            is Command.CreateProgram -> db.withTransaction { executor.createProgram(workouts.commandTx(), command.name, command.mode) }
            is Command.RenameProgram -> db.withTransaction { executor.renameProgram(workouts.commandTx(), command.programId, command.name) }
            is Command.SetProgramMode -> db.withTransaction { executor.setProgramMode(workouts.commandTx(), command.programId, command.mode) }
            is Command.ActivateProgram -> db.withTransaction { executor.activateProgram(workouts.commandTx(), command.programId) }
            is Command.DeactivateProgram -> db.withTransaction { executor.deactivateProgram(workouts.commandTx(), command.programId) }
            is Command.DeleteProgram -> db.withTransaction { executor.deleteProgram(workouts.commandTx(), command.programId) }
            is Command.AddProgramDay -> db.withTransaction { executor.addProgramDay(workouts.commandTx(), command.programId, command.routineId) }
            is Command.RemoveProgramDay -> db.withTransaction { executor.removeProgramDay(workouts.commandTx(), command.dayId) }
            is Command.MoveProgramDay -> db.withTransaction { executor.moveProgramDay(workouts.commandTx(), command.dayId, command.delta) }
            is Command.ToggleProgramWeekday ->
                db.withTransaction { executor.toggleProgramWeekday(workouts.commandTx(), command.dayId, command.weekday) }
        }
    }

    private suspend fun startRoutine(routineId: String, programDayId: String?): CommandResult =
        db.withTransaction { executor.startRoutine(workouts.commandTx(), routineId, programDayId, startedBy) }

    private suspend fun addExercise(command: Command.AddExercise): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        // Checked before the transaction, so a refusal creates no extended-catalogue row either.
        val entries = extendedEntries(listOf(exercise))
        return db.withTransaction {
            val tx = workouts.commandTx()
            // The workout first, so a missing one does not even get the extended entry's row; then core's rule.
            tx.workout(command.workoutId) ?: return@withTransaction Rejected(listOf("no workout ${command.workoutId}"))
            executor.addExercise(tx, command.workoutId, rowFor(exercise, entries), command.sets)
        }
    }

    /** Like [addExercise], for a saved routine: resolve first (a question writes nothing), then append. */
    private suspend fun addRoutineExercise(command: Command.AddRoutineExercise): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        val entries = extendedEntries(listOf(exercise))
        return db.withTransaction {
            val tx = workouts.commandTx()
            // The routine first, so a missing one does not even get the extended entry's row; then core's rule.
            tx.routine(command.routineId) ?: return@withTransaction Rejected(listOf("no routine ${command.routineId}"))
            executor.addRoutineExercise(tx, command.routineId, rowFor(exercise, entries), command.targetSets, command.targetReps)
        }
    }

    /**
     * Resolves the whole plan first (anything unknown → questions, nothing
     * written), then one transaction: rows for extended entries, the workout
     * in progress or a new one, the exercises and their sets.
     */
    private suspend fun applyPlan(command: Command.ApplyPlan): CommandResult {
        val plan = when (val resolution = resolvePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        val entries = extendedEntries(plan.exercises.map { it.exercise })
        return db.withTransaction {
            executor.applyPlan(workouts.commandTx(), plan.exercises.map { rowFor(it.exercise, entries) to it.sets }, startedBy)
        }
    }

    private suspend fun saveRoutinePlan(command: Command.SaveRoutinePlan): CommandResult {
        val plan = when (val resolution = resolveRoutinePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        val entries = extendedEntries(plan.exercises.map { it.exercise })
        return db.withTransaction {
            executor.saveRoutinePlan(
                workouts.commandTx(),
                plan.name,
                plan.exercises.map { RoutineExercisePlan(rowFor(it.exercise, entries), it.targetSets, it.targetReps) },
            )
        }
    }

    /** The extended-catalogue entries [candidates] refer to, read before a transaction starts (the catalogue loads on IO). */
    private suspend fun extendedEntries(candidates: List<ExerciseCandidate>): Map<String, ExtendedExercise> {
        val wanted = candidates.mapNotNull { it.extendedId }.toSet()
        if (wanted.isEmpty()) return emptyMap()
        return extendedCatalog.all().filter { it.id in wanted }.associateBy { it.id }
    }

    /** The Room id for [candidate], creating the row of an extended entry the first time. */
    private suspend fun rowFor(candidate: ExerciseCandidate, entries: Map<String, ExtendedExercise>): String {
        val extendedId = candidate.extendedId ?: return candidate.id
        return exercises.getOrCreateExtended(entries.getValue(extendedId))
    }
}
