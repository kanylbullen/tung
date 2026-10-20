package io.github.kanylbullen.tung.data

import androidx.room.withTransaction
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandHandler
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_DAY_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.NeedsAnswers
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseResolver
import io.github.kanylbullen.tung.core.command.PlanResolution
import io.github.kanylbullen.tung.core.command.RefResolution
import io.github.kanylbullen.tung.core.command.Validation
import io.github.kanylbullen.tung.core.command.resolvePlan
import io.github.kanylbullen.tung.core.command.resolveRef
import io.github.kanylbullen.tung.core.command.resolveRoutinePlan
import io.github.kanylbullen.tung.core.command.validate
import io.github.kanylbullen.tung.core.currentSet

/**
 * The app's one [CommandHandler]: every change to training data that a
 * screen makes goes through [execute], which validates ([validate]), resolves
 * exercise names ([resolveRef], [resolvePlan]) and then calls the
 * repositories. The repositories keep their transactions and rules; this
 * class only maps commands onto them (and asks for a sync after
 * [Command.FinishWorkout], through [onWorkoutFinished]), so a future API or MCP server that
 * calls the same commands gets the same behaviour as the buttons.
 */
class AppCommandHandler(
    private val db: TungDatabase,
    private val workouts: WorkoutRepository,
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val programs: ProgramRepository,
    private val resolver: ExerciseResolver,
    private val extendedCatalog: ExtendedCatalog,
    private val onWorkoutFinished: () -> Unit = {},
    /** Stamped on every workout this handler creates (`core.StartedBy`): `phone` on the phone, `watch` on a standalone watch. */
    private val startedBy: String? = null,
) : CommandHandler {

    override suspend fun execute(command: Command): CommandResult {
        (validate(command) as? Validation.Invalid)?.let { return Rejected(it.reasons) }
        val result = perform(command)
        // A finished (or discarded) workout is worth syncing at once, whoever sent the command.
        if (command is Command.FinishWorkout && result is Done) onWorkoutFinished()
        return result
    }

    private suspend fun perform(command: Command): CommandResult {
        return when (command) {
            is Command.StartWorkout -> {
                val routineId = command.routineId
                if (routineId != null) {
                    startRoutine(routineId, command.programDayId)
                } else {
                    Done(mapOf(WORKOUT_ID to workouts.startOrResumeWorkout(startedBy)))
                }
            }
            is Command.StartRoutine -> startRoutine(command.routineId, command.programDayId)
            is Command.AddExercise -> addExercise(command)
            is Command.RemoveExercise -> {
                workouts.removeExercise(command.workoutExerciseId)
                Done()
            }
            is Command.AddSet ->
                workouts.addSet(command.workoutExerciseId)?.let { Done(mapOf(SET_ID to it)) }
                    ?: Rejected(listOf("no exercise ${command.workoutExerciseId} in the workout"))
            is Command.RemoveSet -> {
                workouts.deleteSet(command.setId)
                Done()
            }
            is Command.SetWeight -> {
                if (command.applyToOpenSets) {
                    workouts.updateWeightForOpenSets(command.setId, command.kg)
                } else {
                    workouts.updateWeight(command.setId, command.kg)
                }
                Done(mapOf(SET_ID to command.setId))
            }
            is Command.SetReps -> {
                if (command.applyToOpenSets) {
                    workouts.updateRepsForOpenSets(command.setId, command.reps)
                } else {
                    workouts.updateReps(command.setId, command.reps)
                }
                Done(mapOf(SET_ID to command.setId))
            }
            is Command.TickSet -> {
                workouts.setCompleted(command.setId, command.done)
                Done(mapOf(SET_ID to command.setId))
            }
            is Command.LogSet -> logSet(command)
            is Command.SelectCurrent -> selectCurrent(command)
            is Command.FinishWorkout ->
                // The id comes back only when the workout was kept; one with nothing logged is gone.
                if (workouts.finishWorkout(command.workoutId)) Done(mapOf(WORKOUT_ID to command.workoutId)) else Done()
            is Command.DeleteWorkout -> {
                workouts.deleteWorkout(command.workoutId)
                Done()
            }
            is Command.ApplyPlan -> applyPlan(command)
            is Command.SetRest -> {
                exercises.setRestSeconds(command.exerciseId, command.seconds)
                Done(mapOf(EXERCISE_ID to command.exerciseId))
            }
            is Command.CreateExercise ->
                Done(mapOf(EXERCISE_ID to exercises.createExercise(command.name, command.muscleGroup, command.equipment)))
            is Command.SaveRoutine ->
                routines.saveFromWorkout(command.workoutId, command.name)?.let { Done(mapOf(ROUTINE_ID to it)) }
                    ?: Rejected(listOf("the workout has no logged set to save"))
            is Command.SaveRoutinePlan -> saveRoutinePlan(command)
            is Command.RenameRoutine -> {
                routines.rename(command.routineId, command.name)
                Done()
            }
            is Command.DeleteRoutine -> {
                routines.delete(command.routineId)
                Done()
            }
            is Command.MoveRoutineExercise -> {
                routines.move(command.routineExerciseId, command.delta)
                Done()
            }
            is Command.AdjustTargetSets -> {
                routines.adjustTargetSets(command.routineExerciseId, command.delta)
                Done()
            }
            is Command.CreateProgram -> Done(mapOf(PROGRAM_ID to programs.create(command.name, command.mode)))
            is Command.RenameProgram -> {
                programs.rename(command.programId, command.name)
                Done()
            }
            is Command.ActivateProgram -> {
                programs.activate(command.programId)
                Done()
            }
            is Command.DeactivateProgram -> {
                programs.deactivate(command.programId)
                Done()
            }
            is Command.DeleteProgram -> {
                programs.delete(command.programId)
                Done()
            }
            is Command.AddProgramDay -> Done(mapOf(PROGRAM_DAY_ID to programs.addDay(command.programId, command.routineId)))
            is Command.RemoveProgramDay -> {
                programs.removeDay(command.dayId)
                Done()
            }
            is Command.MoveProgramDay -> {
                programs.moveDay(command.dayId, command.delta)
                Done()
            }
            is Command.ToggleProgramWeekday -> {
                programs.toggleWeekday(command.dayId, command.weekday)
                Done()
            }
        }
    }

    private suspend fun startRoutine(routineId: String, programDayId: String?): CommandResult =
        routines.start(routineId, programDayId, startedBy)?.let { Done(mapOf(WORKOUT_ID to it)) }
            ?: Rejected(listOf("no routine $routineId"))

    private suspend fun addExercise(command: Command.AddExercise): CommandResult {
        val exercise = when (val resolution = resolveRef(command.exercise, resolver)) {
            is RefResolution.Unresolved -> return NeedsAnswers(listOf(resolution.question))
            is RefResolution.Resolved -> resolution.exercise
        }
        workouts.getWorkout(command.workoutId) ?: return Rejected(listOf("no workout ${command.workoutId}"))
        val entries = extendedEntries(listOf(exercise))
        val workoutExerciseId = db.withTransaction {
            workouts.addExercise(command.workoutId, rowFor(exercise, entries))
        }
        return Done(mapOf(WORKOUT_EXERCISE_ID to workoutExerciseId, EXERCISE_ID to exercise.id))
    }

    private suspend fun logSet(command: Command.LogSet): CommandResult {
        val setId = command.setId
            ?: currentSetId(command.workoutId)
            ?: return Rejected(listOf("no un-ticked set left in workout ${command.workoutId}"))
        val added = workouts.logSet(setId)
        return Done(listOfNotNull(SET_ID to setId, added?.let { ADDED_SET_ID to it }).toMap())
    }

    /** The current set with no selection and nothing touched: the first un-ticked set of the first exercise that has one. */
    private suspend fun currentSetId(workoutId: String): String? {
        val list = workouts.getExercisesWithSets(workoutId)
        val index = currentSet(list.map { e -> e.sortedSets.map { it.completed } }) ?: return null
        return list[index.exercise].sortedSets[index.set].id
    }

    private suspend fun selectCurrent(command: Command.SelectCurrent): CommandResult {
        val setId = command.setId
        val set = if (setId != null) {
            workouts.getSet(setId) ?: return Rejected(listOf("no set $setId"))
        } else {
            val workoutExerciseId = checkNotNull(command.workoutExerciseId)
            workouts.getSets(workoutExerciseId).sortedBy { it.position }.firstOrNull { !it.completed }
                ?: return Rejected(listOf("no un-ticked set in $workoutExerciseId"))
        }
        if (set.completed) return Rejected(listOf("set ${set.id} is already ticked"))
        return Done(mapOf(SET_ID to set.id, WORKOUT_EXERCISE_ID to set.workoutExerciseId))
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
        val workoutId = db.withTransaction {
            workouts.applyPlan(plan.exercises.map { rowFor(it.exercise, entries) to it.sets }, startedBy)
        }
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    private suspend fun saveRoutinePlan(command: Command.SaveRoutinePlan): CommandResult {
        val plan = when (val resolution = resolveRoutinePlan(command.plan, resolver)) {
            is PlanResolution.NeedsAnswers -> return NeedsAnswers(resolution.questions)
            is PlanResolution.Resolved -> resolution.plan
        }
        val entries = extendedEntries(plan.exercises.map { it.exercise })
        val routineId = db.withTransaction {
            routines.createFromPlan(
                plan.name,
                plan.exercises.map { Triple(rowFor(it.exercise, entries), it.targetSets, it.targetReps) },
            )
        }
        return Done(mapOf(ROUTINE_ID to routineId))
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
