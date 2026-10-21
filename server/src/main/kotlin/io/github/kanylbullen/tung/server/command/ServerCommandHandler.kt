package io.github.kanylbullen.tung.server.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.addedExerciseSets
import io.github.kanylbullen.tung.core.addsNextSet
import io.github.kanylbullen.tung.core.clampTargetSets
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.DELETED_COUNT
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_DAY_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.NeedsAnswers
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.PlanResolution
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.plannedSetValues
import io.github.kanylbullen.tung.core.command.resolvePlan
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.command.resolveRoutinePlan
import io.github.kanylbullen.tung.core.command.validate
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.prefillSet
import io.github.kanylbullen.tung.core.routineStartSets
import io.github.kanylbullen.tung.core.routineTarget
import io.github.kanylbullen.tung.core.toggleWeekday
import io.github.kanylbullen.tung.server.ApiException
import io.github.kanylbullen.tung.server.Db
import io.github.kanylbullen.tung.server.ExerciseStore
import io.github.kanylbullen.tung.server.Exercises
import io.github.kanylbullen.tung.server.ProgramDays
import io.github.kanylbullen.tung.server.Programs
import io.github.kanylbullen.tung.server.RoutineExercises
import io.github.kanylbullen.tung.server.Routines
import io.github.kanylbullen.tung.server.Sets
import io.github.kanylbullen.tung.server.WorkoutExercises
import io.github.kanylbullen.tung.server.WorkoutStore
import io.github.kanylbullen.tung.server.Workouts
import io.github.kanylbullen.tung.server.lockUser
import io.github.kanylbullen.tung.server.push.ChangeNotifier
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.CustomFunction
import org.jetbrains.exposed.sql.Expression
import org.jetbrains.exposed.sql.LongColumnType
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.longParam
import org.jetbrains.exposed.sql.selectAll
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
 * Where the app's repositories silently do nothing for an id that is gone,
 * this handler answers [Rejected] naming it: its callers are scripts and
 * assistants, which need to know.
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
            is Command.StartWorkout -> {
                val routineId = command.routineId
                if (routineId != null) {
                    startRoutine(routineId, command.programDayId, now)
                } else {
                    Done(mapOf(WORKOUT_ID to (activeWorkout()?.get(Workouts.id) ?: insertWorkout(now))))
                }
            }
            is Command.StartRoutine -> startRoutine(command.routineId, command.programDayId, now)
            is Command.AddExercise -> addExercise(command, now)
            is Command.RemoveExercise -> {
                val we = liveWorkoutExercise(command.workoutExerciseId) ?: return noSuch("exercise in a workout", command.workoutExerciseId)
                tombstone(Sets, Sets.updatedAt, Sets.deletedAt, now) { (Sets.userId eq userId) and (Sets.workoutExerciseId eq we[WorkoutExercises.id]) }
                tombstone(WorkoutExercises, WorkoutExercises.updatedAt, WorkoutExercises.deletedAt, now) {
                    (WorkoutExercises.userId eq userId) and (WorkoutExercises.id eq we[WorkoutExercises.id])
                }
                Done()
            }
            is Command.AddSet -> {
                val we = liveWorkoutExercise(command.workoutExerciseId) ?: return noSuch("exercise in a workout", command.workoutExerciseId)
                // Each a copy of the one before: the usual rule, applied [count] times. The first new set is returned.
                Done(mapOf(SET_ID to List(command.count ?: 1) { insertPrefilledSet(we, now) }.first()))
            }
            is Command.RemoveSet -> {
                liveSet(command.setId) ?: return noSuch("set", command.setId)
                tombstone(Sets, Sets.updatedAt, Sets.deletedAt, now) { (Sets.userId eq userId) and (Sets.id eq command.setId) }
                Done()
            }
            is Command.SetWeight -> {
                val ids = setsToWrite(command.setId, command.applyToOpenSets) ?: return noSuch("set", command.setId)
                Sets.update({ (Sets.userId eq userId) and (Sets.id inList ids) }) {
                    it[weightKg] = command.kg
                    it[updatedAt] = bumped(updatedAt, now)
                }
                Done(mapOf(SET_ID to command.setId))
            }
            is Command.SetReps -> {
                val ids = setsToWrite(command.setId, command.applyToOpenSets) ?: return noSuch("set", command.setId)
                Sets.update({ (Sets.userId eq userId) and (Sets.id inList ids) }) {
                    it[reps] = command.reps
                    it[updatedAt] = bumped(updatedAt, now)
                }
                Done(mapOf(SET_ID to command.setId))
            }
            is Command.TickSet -> {
                liveSet(command.setId) ?: return noSuch("set", command.setId)
                tick(command.setId, command.done, now)
                Done(mapOf(SET_ID to command.setId))
            }
            is Command.LogSet -> logSet(command, now)
            is Command.SelectCurrent -> selectCurrent(command)
            is Command.FinishWorkout -> {
                val result = with(workouts) { finishIn(userId, command.workoutId, null) } ?: return noSuch("workout", command.workoutId)
                // The id comes back only when the workout was kept; one with nothing logged is gone.
                if (result.discarded) Done() else Done(mapOf(WORKOUT_ID to command.workoutId))
            }
            is Command.DeleteWorkout -> {
                if (!with(workouts) { deleteIn(userId, command.workoutId) }) return noSuch("workout", command.workoutId)
                Done()
            }
            is Command.ApplyPlan -> applyPlan(command, now)
            is Command.SetRest -> {
                resolver.visibleRow(command.exerciseId) ?: return noSuch("exercise", command.exerciseId)
                with(exercises) { patchIn(userId, command.exerciseId, name = null, restGiven = true, rest = command.seconds, now = now) }
                Done(mapOf(EXERCISE_ID to command.exerciseId))
            }
            is Command.CreateExercise -> {
                nameTooLong(command.name)?.let { return it }
                val id = newId()
                with(exercises) {
                    insertOwn(userId, id, command.name.trim(), command.muscleGroup.key, command.equipment.key, isCustom = true, now = now)
                }
                Done(mapOf(EXERCISE_ID to id))
            }
            is Command.SaveRoutine -> saveRoutine(command, now)
            is Command.SaveRoutinePlan -> saveRoutinePlan(command, now)
            is Command.CreateRoutine -> {
                nameTooLong(command.name)?.let { return it }
                Done(mapOf(ROUTINE_ID to insertRoutine(command.name.trim(), emptyList(), now)))
            }
            is Command.RenameRoutine -> {
                nameTooLong(command.name)?.let { return it }
                liveRoutine(command.routineId) ?: return noSuch("routine", command.routineId)
                Routines.update({ (Routines.userId eq userId) and (Routines.id eq command.routineId) }) {
                    it[name] = command.name.trim()
                    it[updatedAt] = bumped(updatedAt, now)
                }
                Done()
            }
            is Command.DeleteRoutine -> {
                liveRoutine(command.routineId) ?: return noSuch("routine", command.routineId)
                deleteRoutine(command.routineId, now)
                Done()
            }
            is Command.DeleteRoutines -> {
                // The live ones go; ids that are gone are skipped like the app's, but naming none that exists is an error here.
                val ids = command.ids.distinct()
                val live = ids.filter { liveRoutine(it) != null }
                if (live.isEmpty()) return ids.singleOrNull()?.let { noSuch("routine", it) } ?: Rejected(listOf("none of the ${ids.size} routines exists"))
                live.forEach { deleteRoutine(it, now) }
                Done(mapOf(DELETED_COUNT to live.size.toString()))
            }
            is Command.MoveRoutineExercise -> {
                val re = liveRoutineExercise(command.routineExerciseId) ?: return noSuch("exercise in a routine", command.routineExerciseId)
                moved(liveRoutineExercises(re[RoutineExercises.routineId]), RoutineExercises.id, command.routineExerciseId, command.delta)
                    ?.let { writeRoutineExercisePositions(it, now) }
                Done()
            }
            is Command.AdjustTargetSets -> {
                val re = liveRoutineExercise(command.routineExerciseId) ?: return noSuch("exercise in a routine", command.routineExerciseId)
                val target = clampTargetSets(re[RoutineExercises.targetSets] + command.delta)
                if (target != re[RoutineExercises.targetSets]) {
                    RoutineExercises.update({ (RoutineExercises.userId eq userId) and (RoutineExercises.id eq command.routineExerciseId) }) {
                        it[targetSets] = target
                        it[updatedAt] = bumped(updatedAt, now)
                    }
                }
                Done()
            }
            is Command.AddRoutineExercise -> addRoutineExercise(command, now)
            is Command.RemoveRoutineExercise -> {
                val re = liveRoutineExercise(command.routineExerciseId) ?: return noSuch("exercise in a routine", command.routineExerciseId)
                tombstone(RoutineExercises, RoutineExercises.updatedAt, RoutineExercises.deletedAt, now) {
                    (RoutineExercises.userId eq userId) and (RoutineExercises.id eq command.routineExerciseId)
                }
                // Close the gap, as the app does, so the rest keep their order from 0.
                writeRoutineExercisePositions(liveRoutineExercises(re[RoutineExercises.routineId]), now)
                Done()
            }
            is Command.SetTargetReps -> {
                val re = liveRoutineExercise(command.routineExerciseId) ?: return noSuch("exercise in a routine", command.routineExerciseId)
                if (re[RoutineExercises.targetReps] != command.reps) {
                    RoutineExercises.update({ (RoutineExercises.userId eq userId) and (RoutineExercises.id eq command.routineExerciseId) }) {
                        it[targetReps] = command.reps
                        it[updatedAt] = bumped(updatedAt, now)
                    }
                }
                Done()
            }
            is Command.CreateProgram -> {
                nameTooLong(command.name)?.let { return it }
                val id = newId()
                Programs.insert {
                    it[Programs.userId] = this@ServerCommandHandler.userId
                    it[Programs.id] = id
                    it[name] = command.name.trim()
                    it[mode] = command.mode.key
                    it[isActive] = false
                    it[updatedAt] = now
                }
                Done(mapOf(PROGRAM_ID to id))
            }
            is Command.RenameProgram -> {
                nameTooLong(command.name)?.let { return it }
                liveProgram(command.programId) ?: return noSuch("program", command.programId)
                Programs.update({ (Programs.userId eq userId) and (Programs.id eq command.programId) }) {
                    it[name] = command.name.trim()
                    it[updatedAt] = bumped(updatedAt, now)
                }
                Done()
            }
            is Command.SetProgramMode -> {
                val program = liveProgram(command.programId) ?: return noSuch("program", command.programId)
                // Days and their weekdays stay, so switching back restores the schedule.
                if (program[Programs.mode] != command.mode.key) {
                    Programs.update({ (Programs.userId eq userId) and (Programs.id eq command.programId) }) {
                        it[mode] = command.mode.key
                        it[updatedAt] = bumped(updatedAt, now)
                    }
                }
                Done()
            }
            is Command.ActivateProgram -> {
                val program = liveProgram(command.programId) ?: return noSuch("program", command.programId)
                // At most one active program: every other one is switched off in the same transaction.
                Programs.update({
                    (Programs.userId eq userId) and Programs.deletedAt.isNull() and (Programs.isActive eq true) and (Programs.id neq command.programId)
                }) {
                    it[isActive] = false
                    it[updatedAt] = bumped(updatedAt, now)
                }
                if (!program[Programs.isActive]) setActive(command.programId, true, now)
                Done()
            }
            is Command.DeactivateProgram -> {
                val program = liveProgram(command.programId) ?: return noSuch("program", command.programId)
                if (program[Programs.isActive]) setActive(command.programId, false, now)
                Done()
            }
            is Command.DeleteProgram -> {
                liveProgram(command.programId) ?: return noSuch("program", command.programId)
                tombstone(ProgramDays, ProgramDays.updatedAt, ProgramDays.deletedAt, now) {
                    (ProgramDays.userId eq userId) and (ProgramDays.programId eq command.programId)
                }
                tombstone(Programs, Programs.updatedAt, Programs.deletedAt, now) { (Programs.userId eq userId) and (Programs.id eq command.programId) }
                Done()
            }
            is Command.AddProgramDay -> {
                liveProgram(command.programId) ?: return noSuch("program", command.programId)
                liveRoutine(command.routineId) ?: return noSuch("routine", command.routineId)
                val position = (liveDays(command.programId).maxOfOrNull { it[ProgramDays.position] } ?: -1) + 1
                val id = newId()
                ProgramDays.insert {
                    it[ProgramDays.userId] = this@ServerCommandHandler.userId
                    it[ProgramDays.id] = id
                    it[programId] = command.programId
                    it[routineId] = command.routineId
                    it[ProgramDays.position] = position
                    it[weekdays] = 0
                    it[updatedAt] = now
                }
                Done(mapOf(PROGRAM_DAY_ID to id))
            }
            is Command.RemoveProgramDay -> {
                val day = liveDay(command.dayId) ?: return noSuch("program day", command.dayId)
                tombstone(ProgramDays, ProgramDays.updatedAt, ProgramDays.deletedAt, now) { (ProgramDays.userId eq userId) and (ProgramDays.id eq command.dayId) }
                // Close the gap, so rotating days keep counting 1, 2, 3.
                writeDayPositions(liveDays(day[ProgramDays.programId]), now)
                Done()
            }
            is Command.MoveProgramDay -> {
                val day = liveDay(command.dayId) ?: return noSuch("program day", command.dayId)
                moved(liveDays(day[ProgramDays.programId]), ProgramDays.id, command.dayId, command.delta)?.let { writeDayPositions(it, now) }
                Done()
            }
            is Command.ToggleProgramWeekday -> {
                val day = liveDay(command.dayId) ?: return noSuch("program day", command.dayId)
                ProgramDays.update({ (ProgramDays.userId eq userId) and (ProgramDays.id eq command.dayId) }) {
                    it[weekdays] = toggleWeekday(day[ProgramDays.weekdays], command.weekday)
                    it[updatedAt] = bumped(updatedAt, now)
                }
                Done()
            }
        }
    }

    // --- workouts ---

    private fun Transaction.startRoutine(routineId: String, programDayId: String?, now: Long): CommandResult {
        // At most one workout in progress: starting returns that one instead of making a second.
        activeWorkout()?.let { return Done(mapOf(WORKOUT_ID to it[Workouts.id])) }
        liveRoutine(routineId) ?: return noSuch("routine", routineId)
        if (programDayId != null) liveDay(programDayId) ?: return noSuch("program day", programDayId)
        val workoutId = insertWorkout(now, routineId, programDayId)
        liveRoutineExercises(routineId).forEachIndexed { position, re ->
            val exerciseId = re[RoutineExercises.exerciseId]
            val weId = insertWorkoutExercise(workoutId, exerciseId, position, now)
            routineStartSets(re[RoutineExercises.targetSets], re[RoutineExercises.targetReps], previousSession(exerciseId, workoutId))
                .forEachIndexed { setPosition, values -> insertSet(weId, setPosition, values, now) }
        }
        Routines.update({ (Routines.userId eq userId) and (Routines.id eq routineId) }) {
            it[lastUsedAt] = now
            it[updatedAt] = bumped(updatedAt, now)
        }
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    private suspend fun Transaction.addExercise(command: Command.AddExercise, now: Long): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        liveWorkout(command.workoutId) ?: return noSuch("workout", command.workoutId)
        val exerciseId = rowFor(exercise, now)
        val weId = insertWorkoutExercise(command.workoutId, exerciseId, nextExercisePosition(command.workoutId), now)
        // sets given: that many; else as many as last session had, at least one, each at its own position.
        addedExerciseSets(command.sets, previousSession(exerciseId, command.workoutId))
            .forEachIndexed { position, values -> insertSet(weId, position, values, now) }
        return Done(mapOf(WORKOUT_EXERCISE_ID to weId, EXERCISE_ID to exerciseId))
    }

    private fun Transaction.logSet(command: Command.LogSet, now: Long): CommandResult {
        liveWorkout(command.workoutId) ?: return noSuch("workout", command.workoutId)
        val setId = command.setId
            ?: currentSetId(command.workoutId)
            ?: return Rejected(listOf("no un-ticked set left in workout ${command.workoutId}"))
        val set = liveSet(setId) ?: return noSuch("set", setId)
        val we = liveWorkoutExercise(set[Sets.workoutExerciseId]) ?: return noSuch("set", setId)
        if (we[WorkoutExercises.workoutId] != command.workoutId) return Rejected(listOf("set $setId is not in workout ${command.workoutId}"))
        // The app opens the reps sheet here instead of ticking; a set without reps would not count.
        if (set[Sets.reps] <= 0) return Rejected(listOf("set $setId has 0 reps: set the reps first (set_reps), then log it"))
        tick(setId, true, now)
        // Issue #64: logging only ticks; the next set comes from add_set, or here only when the caller asks (add_next).
        if (!command.addNext) return Done(mapOf(SET_ID to setId))
        val sets = liveSets(we[WorkoutExercises.id])
        val wasLast = sets.maxOf { it[Sets.position] } == set[Sets.position]
        val target = if (wasLast) routineTargetSets(command.workoutId, we[WorkoutExercises.exerciseId]) else null
        val added = if (addsNextSet(wasLast, sets.size, target)) insertPrefilledSet(we, now) else null
        return Done(listOfNotNull(SET_ID to setId, added?.let { ADDED_SET_ID to it }).toMap())
    }

    /** The current set with no selection and nothing touched: the first un-ticked set of the first exercise that has one. */
    private fun Transaction.currentSetId(workoutId: String): String? {
        val sets = liveExercises(workoutId).map { liveSets(it[WorkoutExercises.id]) }
        val index = currentSet(sets.map { list -> list.map { it[Sets.completed] } }) ?: return null
        return sets[index.exercise][index.set][Sets.id]
    }

    private fun Transaction.selectCurrent(command: Command.SelectCurrent): CommandResult {
        val setId = command.setId
        val set = if (setId != null) {
            liveSet(setId) ?: return noSuch("set", setId)
        } else {
            val workoutExerciseId = checkNotNull(command.workoutExerciseId)
            liveWorkoutExercise(workoutExerciseId) ?: return noSuch("exercise in a workout", workoutExerciseId)
            liveSets(workoutExerciseId).firstOrNull { !it[Sets.completed] }
                ?: return Rejected(listOf("no un-ticked set in $workoutExerciseId"))
        }
        if (set[Sets.completed]) return Rejected(listOf("set ${set[Sets.id]} is already ticked"))
        return Done(mapOf(SET_ID to set[Sets.id], WORKOUT_EXERCISE_ID to set[Sets.workoutExerciseId]))
    }

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
        val workoutId = activeWorkout()?.get(Workouts.id) ?: insertWorkout(now)
        for (planned in plan.exercises) {
            val exerciseId = rowFor(planned.exercise, now)
            val weId = insertWorkoutExercise(workoutId, exerciseId, nextExercisePosition(workoutId), now)
            plannedSetValues(planned.sets, previousSession(exerciseId, workoutId))
                .forEachIndexed { position, values -> insertSet(weId, position, values, now) }
        }
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    // --- routines ---

    private fun Transaction.saveRoutine(command: Command.SaveRoutine, now: Long): CommandResult {
        nameTooLong(command.name)?.let { return it }
        liveWorkout(command.workoutId) ?: return noSuch("workout", command.workoutId)
        val targets = liveExercises(command.workoutId)
            .map { we ->
                we[WorkoutExercises.exerciseId] to routineTarget(
                    liveSets(we[WorkoutExercises.id])
                        .filter { isLogged(it[Sets.completed], it[Sets.reps]) }
                        .map { LoggedSet(it[Sets.weightKg], it[Sets.reps]) },
                )
            }
            .filter { (_, target) -> target.sets > 0 }
        if (targets.isEmpty()) return Rejected(listOf("the workout has no logged set to save"))
        val routineId = insertRoutine(command.name.trim(), targets.map { (exerciseId, target) -> Triple(exerciseId, target.sets, target.reps) }, now)
        return Done(mapOf(ROUTINE_ID to routineId))
    }

    private suspend fun Transaction.saveRoutinePlan(command: Command.SaveRoutinePlan, now: Long): CommandResult {
        nameTooLong(command.plan.name)?.let { return it }
        val plan = when (val resolution = resolveRoutinePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        val rows = plan.exercises.map { Triple(rowFor(it.exercise, now), it.targetSets, it.targetReps) }
        return Done(mapOf(ROUTINE_ID to insertRoutine(plan.name, rows, now)))
    }

    /** Appends to a saved routine; resolves first, so an unknown name writes nothing. */
    private suspend fun Transaction.addRoutineExercise(command: Command.AddRoutineExercise, now: Long): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        liveRoutine(command.routineId) ?: return noSuch("routine", command.routineId)
        val exerciseId = rowFor(exercise, now)
        val position = (liveRoutineExercises(command.routineId).maxOfOrNull { it[RoutineExercises.position] } ?: -1) + 1
        val id = newId()
        RoutineExercises.insert {
            it[userId] = this@ServerCommandHandler.userId
            it[RoutineExercises.id] = id
            it[routineId] = command.routineId
            it[RoutineExercises.exerciseId] = exerciseId
            it[RoutineExercises.position] = position
            it[targetSets] = clampTargetSets(command.targetSets)
            it[targetReps] = command.targetReps
            it[updatedAt] = now
        }
        return Done(mapOf(ROUTINE_EXERCISE_ID to id, EXERCISE_ID to exerciseId))
    }

    private fun Transaction.insertRoutine(name: String, exercises: List<Triple<String, Int, Int?>>, now: Long): String {
        val newRoutineId = newId()
        Routines.insert {
            it[userId] = this@ServerCommandHandler.userId
            it[id] = newRoutineId
            it[Routines.name] = name
            it[lastUsedAt] = null
            it[updatedAt] = now
        }
        exercises.forEachIndexed { position, (exerciseId, targetSets, targetReps) ->
            RoutineExercises.insert {
                it[userId] = this@ServerCommandHandler.userId
                it[id] = newId()
                it[routineId] = newRoutineId
                it[RoutineExercises.exerciseId] = exerciseId
                it[RoutineExercises.position] = position
                it[RoutineExercises.targetSets] = clampTargetSets(targetSets)
                it[RoutineExercises.targetReps] = targetReps
                it[updatedAt] = now
            }
        }
        return newRoutineId
    }

    // --- rows ---

    private fun Transaction.activeWorkout(): ResultRow? =
        Workouts.selectAll()
            .where { (Workouts.userId eq userId) and Workouts.endedAt.isNull() and Workouts.deletedAt.isNull() }
            .orderBy(Workouts.startedAt, SortOrder.DESC)
            .limit(1)
            .singleOrNull()

    private fun Transaction.liveWorkout(id: String): ResultRow? =
        Workouts.selectAll().where { (Workouts.userId eq userId) and (Workouts.id eq id) and Workouts.deletedAt.isNull() }.singleOrNull()

    private fun Transaction.liveWorkoutExercise(id: String): ResultRow? =
        WorkoutExercises.selectAll()
            .where { (WorkoutExercises.userId eq userId) and (WorkoutExercises.id eq id) and WorkoutExercises.deletedAt.isNull() }
            .singleOrNull()

    private fun Transaction.liveExercises(workoutId: String): List<ResultRow> =
        WorkoutExercises.selectAll()
            .where { (WorkoutExercises.userId eq userId) and (WorkoutExercises.workoutId eq workoutId) and WorkoutExercises.deletedAt.isNull() }
            .orderBy(WorkoutExercises.position, SortOrder.ASC)
            .toList()

    private fun Transaction.liveSet(id: String): ResultRow? =
        Sets.selectAll().where { (Sets.userId eq userId) and (Sets.id eq id) and Sets.deletedAt.isNull() }.singleOrNull()

    private fun Transaction.liveSets(workoutExerciseId: String): List<ResultRow> =
        Sets.selectAll()
            .where { (Sets.userId eq userId) and (Sets.workoutExerciseId eq workoutExerciseId) and Sets.deletedAt.isNull() }
            .orderBy(Sets.position, SortOrder.ASC)
            .toList()

    /**
     * Tombstones a routine, its exercises and the program days that use it, as the
     * foreign keys cascade in the app; the days' positions keep their gap, as there.
     */
    private fun Transaction.deleteRoutine(routineId: String, now: Long) {
        tombstone(ProgramDays, ProgramDays.updatedAt, ProgramDays.deletedAt, now) {
            (ProgramDays.userId eq userId) and (ProgramDays.routineId eq routineId)
        }
        tombstone(RoutineExercises, RoutineExercises.updatedAt, RoutineExercises.deletedAt, now) {
            (RoutineExercises.userId eq userId) and (RoutineExercises.routineId eq routineId)
        }
        tombstone(Routines, Routines.updatedAt, Routines.deletedAt, now) { (Routines.userId eq userId) and (Routines.id eq routineId) }
    }

    private fun Transaction.liveRoutine(id: String): ResultRow? =
        Routines.selectAll().where { (Routines.userId eq userId) and (Routines.id eq id) and Routines.deletedAt.isNull() }.singleOrNull()

    private fun Transaction.liveRoutineExercise(id: String): ResultRow? =
        RoutineExercises.selectAll()
            .where { (RoutineExercises.userId eq userId) and (RoutineExercises.id eq id) and RoutineExercises.deletedAt.isNull() }
            .singleOrNull()

    private fun Transaction.liveRoutineExercises(routineId: String): List<ResultRow> =
        RoutineExercises.selectAll()
            .where { (RoutineExercises.userId eq userId) and (RoutineExercises.routineId eq routineId) and RoutineExercises.deletedAt.isNull() }
            .orderBy(RoutineExercises.position, SortOrder.ASC)
            .toList()

    private fun Transaction.liveProgram(id: String): ResultRow? =
        Programs.selectAll().where { (Programs.userId eq userId) and (Programs.id eq id) and Programs.deletedAt.isNull() }.singleOrNull()

    private fun Transaction.liveDay(id: String): ResultRow? =
        ProgramDays.selectAll().where { (ProgramDays.userId eq userId) and (ProgramDays.id eq id) and ProgramDays.deletedAt.isNull() }.singleOrNull()

    private fun Transaction.liveDays(programId: String): List<ResultRow> =
        ProgramDays.selectAll()
            .where { (ProgramDays.userId eq userId) and (ProgramDays.programId eq programId) and ProgramDays.deletedAt.isNull() }
            .orderBy(ProgramDays.position, SortOrder.ASC)
            .toList()

    private fun Transaction.insertWorkout(now: Long, routineId: String? = null, programDayId: String? = null): String {
        val id = newId()
        Workouts.insert {
            it[userId] = this@ServerCommandHandler.userId
            it[Workouts.id] = id
            it[startedAt] = now
            it[endedAt] = null
            it[note] = null
            it[Workouts.routineId] = routineId
            it[Workouts.programDayId] = programDayId
            // Every server command is a REST command or an MCP tool: an assistant or a script.
            it[startedBy] = StartedBy.AGENT
            it[updatedAt] = now
        }
        return id
    }

    private fun Transaction.insertWorkoutExercise(workoutId: String, exerciseId: String, position: Int, now: Long): String {
        val id = newId()
        WorkoutExercises.insert {
            it[userId] = this@ServerCommandHandler.userId
            it[WorkoutExercises.id] = id
            it[WorkoutExercises.workoutId] = workoutId
            it[WorkoutExercises.exerciseId] = exerciseId
            it[WorkoutExercises.position] = position
            it[updatedAt] = now
        }
        return id
    }

    private fun Transaction.insertSet(workoutExerciseId: String, position: Int, values: LoggedSet, now: Long): String {
        val id = newId()
        Sets.insert {
            it[userId] = this@ServerCommandHandler.userId
            it[Sets.id] = id
            it[Sets.workoutExerciseId] = workoutExerciseId
            it[Sets.position] = position
            it[weightKg] = values.weightKg
            it[reps] = values.reps
            it[completed] = false
            it[updatedAt] = now
        }
        return id
    }

    private fun Transaction.insertPrefilledSet(we: ResultRow, now: Long): String =
        insertPrefilledSet(we[WorkoutExercises.workoutId], we[WorkoutExercises.id], we[WorkoutExercises.exerciseId], now)

    /** A new last set for the exercise, pre-filled by the usual rule: the set before it, else last session's at this position. */
    private fun Transaction.insertPrefilledSet(workoutId: String, workoutExerciseId: String, exerciseId: String, now: Long): String {
        val existing = liveSets(workoutExerciseId)
        val last = existing.lastOrNull()?.let { LoggedSet(it[Sets.weightKg], it[Sets.reps]) }
        val values = prefillSet(last, previousSession(exerciseId, workoutId), existing.size) ?: LoggedSet(weightKg = 0.0, reps = 0)
        val position = (existing.maxOfOrNull { it[Sets.position] } ?: -1) + 1
        return insertSet(workoutExerciseId, position, values, now)
    }

    private fun Transaction.nextExercisePosition(workoutId: String): Int =
        (liveExercises(workoutId).maxOfOrNull { it[WorkoutExercises.position] } ?: -1) + 1

    /** Last session's sets for [exerciseId], from `GET /exercises/{id}/last`'s query. */
    private fun Transaction.previousSession(exerciseId: String, workoutId: String): List<LoggedSet> =
        with(workouts) { lastSessionIn(userId, exerciseId, workoutId) }.sets.map { LoggedSet(it.weightKg, it.reps) }

    /** How many sets the routine [workoutId] was started from asks for of [exerciseId]; null outside a routine. Read live, as in the app. */
    private fun Transaction.routineTargetSets(workoutId: String, exerciseId: String): Int? {
        val routineId = liveWorkout(workoutId)?.get(Workouts.routineId) ?: return null
        liveRoutine(routineId) ?: return null
        return liveRoutineExercises(routineId).filter { it[RoutineExercises.exerciseId] == exerciseId }.maxOfOrNull { it[RoutineExercises.targetSets] }
    }

    /** The set and, with [openSets], every un-ticked set of the same exercise; null when the set does not exist. */
    private fun Transaction.setsToWrite(setId: String, openSets: Boolean): List<String>? {
        val set = liveSet(setId) ?: return null
        if (!openSets) return listOf(setId)
        return liveSets(set[Sets.workoutExerciseId]).filter { it[Sets.id] == setId || !it[Sets.completed] }.map { it[Sets.id] }
    }

    private fun Transaction.tick(setId: String, done: Boolean, now: Long) {
        Sets.update({ (Sets.userId eq userId) and (Sets.id eq setId) }) {
            it[completed] = done
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    private fun Transaction.setActive(programId: String, active: Boolean, now: Long) {
        Programs.update({ (Programs.userId eq userId) and (Programs.id eq programId) }) {
            it[isActive] = active
            it[updatedAt] = bumped(updatedAt, now)
        }
    }

    private fun Transaction.writeRoutineExercisePositions(rows: List<ResultRow>, now: Long) {
        rows.forEachIndexed { position, row ->
            if (row[RoutineExercises.position] != position) {
                RoutineExercises.update({ (RoutineExercises.userId eq userId) and (RoutineExercises.id eq row[RoutineExercises.id]) }) {
                    it[RoutineExercises.position] = position
                    it[updatedAt] = bumped(updatedAt, now)
                }
            }
        }
    }

    private fun Transaction.writeDayPositions(days: List<ResultRow>, now: Long) {
        days.forEachIndexed { position, day ->
            if (day[ProgramDays.position] != position) {
                ProgramDays.update({ (ProgramDays.userId eq userId) and (ProgramDays.id eq day[ProgramDays.id]) }) {
                    it[ProgramDays.position] = position
                    it[updatedAt] = bumped(updatedAt, now)
                }
            }
        }
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

/** [rows] (in position order) with the row [id] moved [delta] places; null when it is not there or would move past an end. */
private fun moved(rows: List<ResultRow>, idColumn: Column<String>, id: String, delta: Int): List<ResultRow>? {
    val list = rows.toMutableList()
    val from = list.indexOfFirst { it[idColumn] == id }
    val to = from + delta
    if (from < 0 || to !in list.indices) return null
    list.add(to, list.removeAt(from))
    return list
}

/** `GREATEST(now, updated_at + 1)`: the server's stamp for its own changes, so a write always moves a row forward. */
private fun bumped(updatedAt: Column<Long>, now: Long): Expression<Long> =
    CustomFunction("GREATEST", LongColumnType(), longParam(now), with(SqlExpressionBuilder) { updatedAt + 1L })

/** Tombstones the live rows [where] matches: `deleted_at` and `updated_at` both get [bumped]. */
private fun <T : Table> tombstone(table: T, updatedAt: Column<Long>, deletedAt: Column<Long?>, now: Long, where: SqlExpressionBuilder.() -> Op<Boolean>) {
    table.update({ where() and deletedAt.isNull() }) {
        it[deletedAt] = bumped(updatedAt, now)
        it[updatedAt] = bumped(updatedAt, now)
    }
}
