package io.github.kanylbullen.tung.server.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.NeedsAnswers
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.PlanResolution
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.RoutineExercisePlan
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.resolvePlan
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.command.resolveRoutinePlan
import io.github.kanylbullen.tung.core.command.validate
import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.ExerciseStore
import io.github.kanylbullen.tung.server.Exercises
import io.github.kanylbullen.tung.server.WorkoutStore
import io.github.kanylbullen.tung.server.lockUser
import io.github.kanylbullen.tung.server.push.ChangeNotifier
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.longParam
import org.jetbrains.exposed.sql.update

/** Longest name a command may give an exercise, routine or program; the same cap as the REST API's. */
const val MAX_COMMAND_NAME = 100

/**
 * Builds the [ServerCommandHandler] for one user. Every REST command route
 * and every MCP tool that changes data gets its handler here, so there is one
 * path from a command to the tables.
 */
class CommandService(
    private val db: Db,
    private val catalogue: ServerCatalogue,
    private val exercises: ExerciseStore,
    private val workouts: WorkoutStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notifier: ChangeNotifier = ChangeNotifier.NONE,
) {
    /** [sourceDeviceId] is the calling client's device id (push skips it), null for a client without one, as every MCP client. */
    fun handlerFor(userId: String, sourceDeviceId: String? = null): ServerCommandHandler =
        ServerCommandHandler(db, userId, catalogue, exercises, workouts, clock, notifier, sourceDeviceId)

    fun resolverFor(userId: String): ServerExerciseResolver = ServerExerciseResolver(userId, catalogue)
}

/**
 * Core's [CommandHandler] over the server's tables for one user: the server's
 * twin of the app's `AppCommandHandler`, with the same rules. Every command is
 * validated first ([validate]), then runs in **one transaction** that starts
 * with the user's lock ([lockUser]): names are resolved (core's [resolveRef],
 * [resolvePlan]; anything unknown is [NeedsAnswers] and nothing is written),
 * and the rows are written with `updated_at = now` (or `stored + 1` when that
 * is later, the server's rule for its own changes). Deletes are tombstones.
 * The `seq` trigger stamps every written row, so a change made here reaches
 * the phone on its next sync like any other write.
 *
 * Every command is core's [CommandExecutor] over [ExposedCommandTx] (issue
 * #87), the rule the app runs over Room; what is written here is the row an
 * extended-catalogue entry resolves to ([rowFor]) and the REST API's cap on
 * names ([MAX_COMMAND_NAME]). An id that is gone is [Rejected], naming it.
 */
class ServerCommandHandler(
    private val db: Db,
    private val userId: String,
    private val catalogue: ServerCatalogue,
    private val exercises: ExerciseStore,
    private val workouts: WorkoutStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val notifier: ChangeNotifier = ChangeNotifier.NONE,
    private val sourceDeviceId: String? = null,
) : CommandHandler {

    val resolver = ServerExerciseResolver(userId, catalogue)
    private val executor = CommandExecutor()

    override suspend fun execute(command: Command): CommandResult {
        (validate(command) as? Validation.Invalid)?.let { return Rejected(it.reasons) }
        val result = try {
            db.suspendingTx {
                lockUser(userId)
                val result = perform(command, clock())
                // Every refusal is decided before the first write; this only makes that a guarantee.
                if (result !is Done) rollback()
                result
            }
        } catch (e: ApiException) {
            Rejected(listOf(e.message))
        }
        // After the commit: push the change to the user's other devices. select_current only answers ids and writes nothing.
        if (result is Done && command !is Command.SelectCurrent) notifier.changed(userId, sourceDeviceId)
        return result
    }

    private suspend fun Transaction.perform(command: Command, now: Long): CommandResult {
        return when (command) {
            // Every server command is a REST command or an MCP tool: an assistant or a script.
            is Command.StartWorkout -> {
                val routineId = command.routineId
                if (routineId != null) {
                    executor.startRoutine(commandTx(now), routineId, command.programDayId, StartedBy.AGENT)
                } else {
                    executor.startWorkout(commandTx(now), StartedBy.AGENT)
                }
            }
            is Command.StartRoutine -> executor.startRoutine(commandTx(now), command.routineId, command.programDayId, StartedBy.AGENT)
            is Command.AddExercise -> addExercise(command, now)
            is Command.RemoveExercise -> executor.removeExercise(commandTx(now), command.workoutExerciseId)
            is Command.AddSet -> executor.addSet(commandTx(now), command.workoutExerciseId, command.count)
            is Command.RemoveSet -> executor.removeSet(commandTx(now), command.setId)
            is Command.SetWeight -> executor.setWeight(commandTx(now), command.setId, command.kg, command.applyToOpenSets)
            is Command.SetReps -> executor.setReps(commandTx(now), command.setId, command.reps, command.applyToOpenSets)
            is Command.TickSet -> executor.tickSet(commandTx(now), command.setId, command.done)
            is Command.LogSet -> executor.logSet(commandTx(now), command)
            is Command.SelectCurrent -> executor.selectCurrent(commandTx(now), command)
            // The id comes back only when the workout was kept; one with nothing logged is gone.
            is Command.FinishWorkout -> executor.finishWorkout(commandTx(now), command.workoutId)
            is Command.DeleteWorkout -> executor.deleteWorkout(commandTx(now), command.workoutId)
            is Command.ApplyPlan -> applyPlan(command, now)
            is Command.SetRest -> executor.setRest(commandTx(now), command.exerciseId, command.seconds)
            is Command.CreateExercise -> {
                nameTooLong(command.name)?.let { return it }
                executor.createExercise(commandTx(now), command.name, command.muscleGroup, command.equipment)
            }
            is Command.SaveRoutine -> {
                nameTooLong(command.name)?.let { return it }
                executor.saveRoutine(commandTx(now), command.workoutId, command.name)
            }
            is Command.SaveRoutinePlan -> saveRoutinePlan(command, now)
            is Command.CreateRoutine -> {
                nameTooLong(command.name)?.let { return it }
                executor.createRoutine(commandTx(now), command.name)
            }
            is Command.RenameRoutine -> {
                nameTooLong(command.name)?.let { return it }
                executor.renameRoutine(commandTx(now), command.routineId, command.name)
            }
            is Command.DeleteRoutine -> executor.deleteRoutine(commandTx(now), command.routineId)
            is Command.MoveRoutineExercise -> executor.moveRoutineExercise(commandTx(now), command.routineExerciseId, command.delta)
            is Command.AdjustTargetSets -> executor.adjustTargetSets(commandTx(now), command.routineExerciseId, command.delta)
            is Command.AddRoutineExercise -> addRoutineExercise(command, now)
            is Command.RemoveRoutineExercise -> executor.removeRoutineExercise(commandTx(now), command.routineExerciseId)
            is Command.SetTargetReps -> executor.setTargetReps(commandTx(now), command.routineExerciseId, command.reps)
            is Command.CreateProgram -> {
                nameTooLong(command.name)?.let { return it }
                executor.createProgram(commandTx(now), command.name, command.mode)
            }
            is Command.RenameProgram -> {
                nameTooLong(command.name)?.let { return it }
                executor.renameProgram(commandTx(now), command.programId, command.name)
            }
            is Command.SetProgramMode -> executor.setProgramMode(commandTx(now), command.programId, command.mode)
            is Command.ActivateProgram -> executor.activateProgram(commandTx(now), command.programId)
            is Command.DeactivateProgram -> executor.deactivateProgram(commandTx(now), command.programId)
            is Command.DeleteProgram -> executor.deleteProgram(commandTx(now), command.programId)
            is Command.AddProgramDay -> executor.addProgramDay(commandTx(now), command.programId, command.routineId)
            is Command.RemoveProgramDay -> executor.removeProgramDay(commandTx(now), command.dayId)
            is Command.MoveProgramDay -> executor.moveProgramDay(commandTx(now), command.dayId, command.delta)
            is Command.ToggleProgramWeekday -> executor.toggleProgramWeekday(commandTx(now), command.dayId, command.weekday)
        }
    }

    // --- workouts ---

    private suspend fun Transaction.addExercise(command: Command.AddExercise, now: Long): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        val tx = commandTx(now)
        // The workout first, so a missing one does not even get the extended entry's row; then core's rule.
        tx.workout(command.workoutId) ?: return noSuch("workout", command.workoutId)
        return executor.addExercise(tx, command.workoutId, rowFor(exercise, now), command.sets)
    }

    /** Core's store over this transaction, for the rules that have moved into [CommandExecutor] (issue #87). */
    private fun Transaction.commandTx(now: Long): ExposedCommandTx = ExposedCommandTx(this, userId, workouts, now)

    /**
     * Resolves the whole plan first (anything unknown → questions, nothing
     * written), then: the workout in progress or a new one, each exercise
     * appended with its planned sets, nothing ticked.
     */
    private suspend fun Transaction.applyPlan(command: Command.ApplyPlan, now: Long): CommandResult {
        val plan = when (val resolution = resolvePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        // The rows first (an extended entry's is created here), then core's rule: the workout in progress or a new one, the plan appended.
        return executor.applyPlan(commandTx(now), plan.exercises.map { rowFor(it.exercise, now) to it.sets }, StartedBy.AGENT)
    }

    // --- routines ---

    /** Resolves the whole plan first (anything unknown → questions, nothing written), then core's rule with the rows. */
    private suspend fun Transaction.saveRoutinePlan(command: Command.SaveRoutinePlan, now: Long): CommandResult {
        nameTooLong(command.plan.name)?.let { return it }
        val plan = when (val resolution = resolveRoutinePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        val rows = plan.exercises.map { RoutineExercisePlan(rowFor(it.exercise, now), it.targetSets, it.targetReps) }
        return executor.saveRoutinePlan(commandTx(now), plan.name, rows)
    }

    /** Appends to a saved routine; resolves first, so an unknown name writes nothing, and a missing routine not even the row. */
    private suspend fun Transaction.addRoutineExercise(command: Command.AddRoutineExercise, now: Long): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        val tx = commandTx(now)
        tx.routine(command.routineId) ?: return noSuch("routine", command.routineId)
        return executor.addRoutineExercise(tx, command.routineId, rowFor(exercise, now), command.targetSets, command.targetReps)
    }

    /**
     * The id of [candidate]'s row, creating the row of an extended-catalogue
     * entry the first time, with the same deterministic id as the app's
     * `getOrCreateExtended` (and reviving it if sync tombstoned it).
     */
    private fun Transaction.rowFor(candidate: ExerciseCandidate, now: Long): String {
        val extendedId = candidate.extendedId ?: return candidate.id
        val entry = catalogue.extendedById.getValue(extendedId)
        val stored = with(exercises) { ownRowAnyState(userId, candidate.id) }
        when {
            stored == null -> with(exercises) {
                insertOwn(
                    userId, candidate.id, entry.displayName,
                    MuscleGroup.fromKey(entry.muscleGroup).key, Equipment.fromKey(entry.equipment).key,
                    isCustom = false, now = now,
                )
            }
            stored[Exercises.deletedAt] != null -> Exercises.update({ (Exercises.userId eq userId) and (Exercises.id eq candidate.id) }) {
                it[deletedAt] = null
                it[updatedAt] = bumped(updatedAt, now)
            }
        }
        return candidate.id
    }

    private fun noSuch(what: String, id: String): Rejected = Rejected(listOf("no $what $id"))

    private fun nameTooLong(name: String): Rejected? =
        if (name.trim().length > MAX_COMMAND_NAME) Rejected(listOf("the name can be at most $MAX_COMMAND_NAME characters")) else null
}

/** `GREATEST(now, updated_at + 1)`: the server's stamp for its own changes, so a write always moves a row forward. */
internal fun bumped(updatedAt: Column<Long>, now: Long): Expression<Long> =
    CustomFunction("GREATEST", LongColumnType(), longParam(now), with(SqlExpressionBuilder) { updatedAt + 1L })

/** Tombstones the live rows [where] matches: `deleted_at` and `updated_at` both get [bumped]. */
internal fun <T : Table> tombstone(table: T, updatedAt: Column<Long>, deletedAt: Column<Long?>, now: Long, where: SqlExpressionBuilder.() -> Op<Boolean>) {
    table.update({ where() and deletedAt.isNull() }) {
        it[deletedAt] = bumped(updatedAt, now)
        it[updatedAt] = bumped(updatedAt, now)
    }
}
