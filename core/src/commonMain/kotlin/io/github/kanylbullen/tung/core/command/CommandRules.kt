package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.addedExerciseSets
import io.github.kanylbullen.tung.core.addsNextSet
import io.github.kanylbullen.tung.core.clampTargetSets
import io.github.kanylbullen.tung.core.command.CommandIds.ADDED_SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_DAY_ID
import io.github.kanylbullen.tung.core.command.CommandIds.PROGRAM_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.ROUTINE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.SET_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_EXERCISE_ID
import io.github.kanylbullen.tung.core.command.CommandIds.WORKOUT_ID
import io.github.kanylbullen.tung.core.command.CommandResult.Done
import io.github.kanylbullen.tung.core.command.CommandResult.Rejected
import io.github.kanylbullen.tung.core.currentSet
import io.github.kanylbullen.tung.core.isLogged
import io.github.kanylbullen.tung.core.newId
import io.github.kanylbullen.tung.core.prefillSet
import io.github.kanylbullen.tung.core.routineStartSets
import io.github.kanylbullen.tung.core.routineTarget
import io.github.kanylbullen.tung.core.toggleWeekday

/**
 * The rules of the commands, once, over a [CommandTx] (issue #87): what to
 * read, what to write and in which order, one function per command, so the
 * phone, the server and later a web or iOS client cannot drift apart.
 * [CommandExecutor] is the [CommandHandler] that validates, resolves names,
 * opens the store's transaction and calls in here; the repositories' write
 * functions and the server's REST finish and delete call in here directly
 * with a transaction of their own.
 *
 * Every refusal is decided before the first write. An id that does not
 * exist is [Rejected], naming it, on every platform; a value that is
 * already what is asked for is not written again.
 */
class CommandRules(private val ids: () -> String = { newId() }) {

    // --- exercises ---

    /** `set_rest`: the seconds between the exercise's sets, 0 for none, null to follow the default; written only when it changes. */
    suspend fun setRest(tx: CommandTx, exerciseId: String, seconds: Int?): CommandResult {
        val exercise = tx.exercise(exerciseId) ?: return noSuch("exercise", exerciseId)
        if (exercise.restSeconds != seconds) tx.setRest(exerciseId, seconds)
        return Done(mapOf(EXERCISE_ID to exerciseId))
    }

    /** `create_exercise`: a custom exercise of the user's own, named as typed (trimmed), following the default rest. */
    suspend fun createExercise(tx: CommandTx, name: String, muscleGroup: MuscleGroup, equipment: Equipment): CommandResult {
        val row = ExerciseRow(ids(), name.trim(), nameKey = null, muscleGroup, equipment, isCustom = true, restSeconds = null)
        tx.insertExercise(row)
        return Done(mapOf(EXERCISE_ID to row.id))
    }

    // --- workouts ---

    /**
     * `select_current`: the set a client means to log next, resolved and
     * checked but not written anywhere (the selection is view state): the set
     * named, or the first un-ticked set of the exercise named; a ticked set is
     * refused. Answers [SET_ID] and [WORKOUT_EXERCISE_ID].
     */
    suspend fun selectCurrent(tx: CommandTx, command: Command.SelectCurrent): CommandResult {
        val setId = command.setId
        val set = if (setId != null) {
            tx.set(setId) ?: return noSuch("set", setId)
        } else {
            val workoutExerciseId = checkNotNull(command.workoutExerciseId) { "a setId or a workoutExerciseId" }
            tx.workoutExercise(workoutExerciseId) ?: return noSuch("exercise in a workout", workoutExerciseId)
            tx.sets(workoutExerciseId).firstOrNull { !it.completed } ?: return Rejected(listOf("no un-ticked set in $workoutExerciseId"))
        }
        if (set.completed) return Rejected(listOf("set ${set.id} is already ticked"))
        return Done(mapOf(SET_ID to set.id, WORKOUT_EXERCISE_ID to set.workoutExerciseId))
    }

    /** `start_workout` without a routine: the workout in progress (there is at most one), else a new one started now by [startedBy]. */
    suspend fun startWorkout(tx: CommandTx, startedBy: String?): CommandResult {
        tx.activeWorkout()?.let { return Done(mapOf(WORKOUT_ID to it.id)) }
        return Done(mapOf(WORKOUT_ID to insertWorkout(tx, startedBy, routineId = null, programDayId = null)))
    }

    /**
     * `start_routine`: the workout in progress if there is one (never a
     * second), else a new workout from the routine, recording [routineId] and
     * [programDayId]: its exercises in order with `targetSets` un-ticked sets
     * each, filled by [routineStartSets] (last session's set at the same
     * position, past its end the set before, 0 kg × `targetReps` with no
     * history), and the routine marked used.
     */
    suspend fun startRoutine(tx: CommandTx, routineId: String, programDayId: String?, startedBy: String?): CommandResult {
        tx.activeWorkout()?.let { return Done(mapOf(WORKOUT_ID to it.id)) }
        tx.routine(routineId) ?: return noSuch("routine", routineId)
        if (programDayId != null) tx.programDay(programDayId) ?: return noSuch("program day", programDayId)
        val workoutId = insertWorkout(tx, startedBy, routineId, programDayId)
        tx.routineExercises(routineId).forEachIndexed { position, re ->
            val row = WorkoutExerciseRow(ids(), workoutId, re.exerciseId, position)
            tx.insertWorkoutExercise(row)
            routineStartSets(re.targetSets, re.targetReps, tx.previousSession(re.exerciseId, workoutId))
                .forEachIndexed { setPosition, values ->
                    tx.insertSet(SetRow(ids(), row.id, setPosition, values.weightKg, values.reps, completed = false))
                }
        }
        tx.markRoutineUsed(routineId)
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    /**
     * `finish_workout`: sets that are not logged ([isLogged]: ticked with
     * reps) are dropped, then exercises left without sets, and a workout
     * without one logged set is deleted rather than kept empty (then no
     * [WORKOUT_ID] comes back). It ends at [endedAt], or now (at its start if
     * that lies ahead, a device clock that runs fast); an end before the start
     * is refused. A workout already finished is left as it is.
     */
    suspend fun finishWorkout(tx: CommandTx, workoutId: String, endedAt: Long? = null): CommandResult {
        val workout = tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        if (workout.endedAt != null) return Done(mapOf(WORKOUT_ID to workoutId))
        val end = endedAt ?: maxOf(tx.now(), workout.startedAt)
        if (end < workout.startedAt) return Rejected(listOf("ended_at is before started_at"))
        var logged = 0
        for (we in tx.workoutExercises(workoutId)) {
            val (keep, drop) = tx.sets(we.id).partition { isLogged(it.completed, it.reps) }
            logged += keep.size
            if (keep.isEmpty()) tx.deleteWorkoutExercise(we.id) else drop.forEach { tx.deleteSet(it.id) }
        }
        if (logged == 0) {
            tx.deleteWorkout(workoutId)
            return Done()
        }
        tx.endWorkout(workoutId, end)
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    /** `delete_workout`: the workout with its exercises and sets. */
    suspend fun deleteWorkout(tx: CommandTx, workoutId: String): CommandResult {
        tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        tx.deleteWorkout(workoutId)
        return Done()
    }

    /**
     * `apply_plan` once every exercise is a row: the workout in progress, or a
     * new one started by [startedBy], gets each of [exercises] appended in
     * order with its planned sets, none ticked; a number the plan leaves out
     * comes from the pre-fill rule ([plannedSetValues]).
     */
    suspend fun applyPlan(tx: CommandTx, exercises: List<Pair<String, List<PlannedSet>>>, startedBy: String?): CommandResult {
        val workoutId = tx.activeWorkout()?.id ?: insertWorkout(tx, startedBy, routineId = null, programDayId = null)
        for ((exerciseId, planned) in exercises) {
            val position = (tx.workoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) + 1
            val row = WorkoutExerciseRow(ids(), workoutId, exerciseId, position)
            tx.insertWorkoutExercise(row)
            plannedSetValues(planned, tx.previousSession(exerciseId, workoutId)).forEachIndexed { setPosition, values ->
                tx.insertSet(SetRow(ids(), row.id, setPosition, values.weightKg, values.reps, completed = false))
            }
        }
        return Done(mapOf(WORKOUT_ID to workoutId))
    }

    private suspend fun insertWorkout(tx: CommandTx, startedBy: String?, routineId: String?, programDayId: String?): String {
        val row = WorkoutRow(ids(), startedAt = tx.now(), endedAt = null, routineId = routineId, programDayId = programDayId, startedBy = startedBy)
        tx.insertWorkout(row)
        return row.id
    }

    /**
     * `add_exercise` once the exercise is the row [exerciseId]: appended after
     * the workout's last exercise with [sets] sets, or, when null, as many as
     * the exercise had last session (at least one), each pre-filled at its own
     * position by [addedExerciseSets]; nothing ticked.
     */
    suspend fun addExercise(tx: CommandTx, workoutId: String, exerciseId: String, sets: Int?): CommandResult {
        tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        val position = (tx.workoutExercises(workoutId).maxOfOrNull { it.position } ?: -1) + 1
        val row = WorkoutExerciseRow(ids(), workoutId, exerciseId, position)
        tx.insertWorkoutExercise(row)
        addedExerciseSets(sets, tx.previousSession(exerciseId, workoutId)).forEachIndexed { setPosition, values ->
            tx.insertSet(SetRow(ids(), row.id, setPosition, values.weightKg, values.reps, completed = false))
        }
        return Done(mapOf(WORKOUT_EXERCISE_ID to row.id, EXERCISE_ID to exerciseId))
    }

    /**
     * `add_set`: [count] sets (null for one) after the exercise's last, each
     * pre-filled by the usual rule, a copy of the one before it
     * ([prefillSet]). The first added set's id comes back as [SET_ID].
     */
    suspend fun addSet(tx: CommandTx, workoutExerciseId: String, count: Int?): CommandResult {
        val we = tx.workoutExercise(workoutExerciseId) ?: return noSuch("exercise in a workout", workoutExerciseId)
        val ids = List((count ?: 1).coerceAtLeast(1)) { insertPrefilledSet(tx, we) }
        return Done(mapOf(SET_ID to ids.first()))
    }

    /**
     * `log_set`: ticks the set and nothing more (issue #64). Without a set id
     * it takes the current set by the default rule ([currentSet]: the first
     * un-ticked set of the first exercise that has one). A set with 0 reps is
     * refused, as it would not count as logged: every client asks for the reps
     * first. With [Command.LogSet.addNext] it also adds the next set when the
     * logged one was the last of its exercise and not past a routine's target
     * sets ([addsNextSet]), returned as [ADDED_SET_ID].
     */
    suspend fun logSet(tx: CommandTx, command: Command.LogSet): CommandResult {
        tx.workout(command.workoutId) ?: return noSuch("workout", command.workoutId)
        val setId = command.setId
            ?: currentSetId(tx, command.workoutId)
            ?: return Rejected(listOf("no un-ticked set left in workout ${command.workoutId}"))
        val set = tx.set(setId) ?: return noSuch("set", setId)
        val we = tx.workoutExercise(set.workoutExerciseId) ?: return noSuch("set", setId)
        if (we.workoutId != command.workoutId) return Rejected(listOf("set $setId is not in workout ${command.workoutId}"))
        if (set.reps <= 0) return Rejected(listOf("set $setId has 0 reps: set the reps first (set_reps), then log it"))
        tx.tickSet(setId, true)
        if (!command.addNext) return Done(mapOf(SET_ID to setId))
        val sets = tx.sets(we.id)
        val wasLast = sets.maxOf { it.position } == set.position
        val target = if (wasLast) tx.routineTargetSets(command.workoutId, we.exerciseId) else null
        val added = if (addsNextSet(wasLast, sets.size, target)) insertPrefilledSet(tx, we) else null
        return Done(listOfNotNull(SET_ID to setId, added?.let { ADDED_SET_ID to it }).toMap())
    }

    /** `tick_set`: the tick on a row, on or off, nothing else. */
    suspend fun tickSet(tx: CommandTx, setId: String, done: Boolean): CommandResult {
        tx.set(setId) ?: return noSuch("set", setId)
        tx.tickSet(setId, done)
        return Done(mapOf(SET_ID to setId))
    }

    /** `set_weight`: the set's weight; with [applyToOpenSets] every un-ticked set of the same exercise gets it too. */
    suspend fun setWeight(tx: CommandTx, setId: String, weightKg: Double, applyToOpenSets: Boolean): CommandResult {
        val ids = setsToWrite(tx, setId, applyToOpenSets) ?: return noSuch("set", setId)
        ids.forEach { tx.setWeight(it, weightKg) }
        return Done(mapOf(SET_ID to setId))
    }

    /** `set_reps`: the set's reps; with [applyToOpenSets] every un-ticked set of the same exercise gets them too. */
    suspend fun setReps(tx: CommandTx, setId: String, reps: Int, applyToOpenSets: Boolean): CommandResult {
        val ids = setsToWrite(tx, setId, applyToOpenSets) ?: return noSuch("set", setId)
        ids.forEach { tx.setReps(it, reps) }
        return Done(mapOf(SET_ID to setId))
    }

    /** `remove_set`. */
    suspend fun removeSet(tx: CommandTx, setId: String): CommandResult {
        tx.set(setId) ?: return noSuch("set", setId)
        tx.deleteSet(setId)
        return Done()
    }

    /** `remove_exercise`: the exercise and its sets. */
    suspend fun removeExercise(tx: CommandTx, workoutExerciseId: String): CommandResult {
        tx.workoutExercise(workoutExerciseId) ?: return noSuch("exercise in a workout", workoutExerciseId)
        tx.deleteWorkoutExercise(workoutExerciseId)
        return Done()
    }

    // --- routines ---

    /** `create_routine`: an empty routine called [name] (trimmed), to be filled with `add_routine_exercise`. */
    suspend fun createRoutine(tx: CommandTx, name: String): CommandResult =
        Done(mapOf(ROUTINE_ID to insertRoutine(tx, name, emptyList(), routineId = ids(), routineExerciseIds = null)))

    /**
     * `save_routine`: [workoutId] as a new routine called [name]: its
     * exercises in workout order, each with one target set per logged set
     * and the most common rep count as target reps ([routineTarget]);
     * exercises without a logged set are left out, and a workout without one
     * is refused. Saving the same workout twice makes two routines.
     */
    suspend fun saveRoutine(tx: CommandTx, workoutId: String, name: String): CommandResult {
        tx.workout(workoutId) ?: return noSuch("workout", workoutId)
        val exercises = tx.workoutExercises(workoutId)
            .map { we -> we.exerciseId to routineTarget(tx.sets(we.id).filter { isLogged(it.completed, it.reps) }.map { it.values }) }
            .filter { (_, target) -> target.sets > 0 }
            .map { (exerciseId, target) -> RoutineExercisePlan(exerciseId, target.sets, target.reps) }
        if (exercises.isEmpty()) return Rejected(listOf("the workout has no logged set to save"))
        return Done(mapOf(ROUTINE_ID to insertRoutine(tx, name, exercises, routineId = ids(), routineExerciseIds = null)))
    }

    /**
     * `save_routine_plan` once every exercise is a row: a routine called
     * [name] with [exercises] in order, each with its target sets (kept
     * within the allowed range) and target reps. The ids are new unless the
     * caller has its own ([routineId], [routineExerciseIds], one per
     * exercise): an import passes its deterministic ids.
     */
    suspend fun saveRoutinePlan(
        tx: CommandTx,
        name: String,
        exercises: List<RoutineExercisePlan>,
        routineId: String = ids(),
        routineExerciseIds: List<String>? = null,
    ): CommandResult {
        require(routineExerciseIds == null || routineExerciseIds.size == exercises.size) { "one id per routine exercise" }
        return Done(mapOf(ROUTINE_ID to insertRoutine(tx, name, exercises, routineId, routineExerciseIds)))
    }

    private suspend fun insertRoutine(
        tx: CommandTx,
        name: String,
        exercises: List<RoutineExercisePlan>,
        routineId: String,
        routineExerciseIds: List<String>?,
    ): String {
        tx.insertRoutine(RoutineRow(routineId, name.trim(), lastUsedAt = null))
        exercises.forEachIndexed { position, plan ->
            val id = routineExerciseIds?.get(position) ?: ids()
            tx.insertRoutineExercise(RoutineExerciseRow(id, routineId, plan.exerciseId, position, clampTargetSets(plan.targetSets), plan.targetReps))
        }
        return routineId
    }

    /** `rename_routine`: the name, trimmed. */
    suspend fun renameRoutine(tx: CommandTx, routineId: String, name: String): CommandResult {
        tx.routine(routineId) ?: return noSuch("routine", routineId)
        tx.renameRoutine(routineId, name.trim())
        return Done()
    }

    /**
     * `delete_routine`: the routine, its exercises and the program days that
     * start it (a day without a routine has nothing to start; the other days
     * keep their positions). Workouts started from it are kept.
     */
    suspend fun deleteRoutine(tx: CommandTx, routineId: String): CommandResult {
        tx.routine(routineId) ?: return noSuch("routine", routineId)
        tx.programDaysUsing(routineId).forEach { tx.deleteProgramDay(it.id) }
        tx.routineExercises(routineId).forEach { tx.deleteRoutineExercise(it.id) }
        tx.deleteRoutine(routineId)
        return Done()
    }

    /**
     * `add_routine_exercise` once the exercise is the row [exerciseId]:
     * appended after the routine's last exercise with [targetSets] (kept
     * within the allowed range) and [targetReps].
     */
    suspend fun addRoutineExercise(tx: CommandTx, routineId: String, exerciseId: String, targetSets: Int, targetReps: Int?): CommandResult {
        tx.routine(routineId) ?: return noSuch("routine", routineId)
        val position = (tx.routineExercises(routineId).maxOfOrNull { it.position } ?: -1) + 1
        val row = RoutineExerciseRow(ids(), routineId, exerciseId, position, clampTargetSets(targetSets), targetReps)
        tx.insertRoutineExercise(row)
        return Done(mapOf(ROUTINE_EXERCISE_ID to row.id, EXERCISE_ID to exerciseId))
    }

    /** `remove_routine_exercise`: the row goes and the positions after it close up, so the rest keep their order from 0. */
    suspend fun removeRoutineExercise(tx: CommandTx, routineExerciseId: String): CommandResult {
        val re = tx.routineExercise(routineExerciseId) ?: return noSuch("exercise in a routine", routineExerciseId)
        tx.deleteRoutineExercise(routineExerciseId)
        writeRoutinePositions(tx, tx.routineExercises(re.routineId))
        return Done()
    }

    /** `move_routine_exercise`: [delta] places (−1 up, +1 down) within its routine; a move past either end changes nothing. */
    suspend fun moveRoutineExercise(tx: CommandTx, routineExerciseId: String, delta: Int): CommandResult {
        val re = tx.routineExercise(routineExerciseId) ?: return noSuch("exercise in a routine", routineExerciseId)
        moved(tx.routineExercises(re.routineId), routineExerciseId, delta, RoutineExerciseRow::id)?.let { writeRoutinePositions(tx, it) }
        return Done()
    }

    /** `adjust_target_sets`: [delta] added to the target sets, kept within the allowed range; written only when it changes. */
    suspend fun adjustTargetSets(tx: CommandTx, routineExerciseId: String, delta: Int): CommandResult {
        val re = tx.routineExercise(routineExerciseId) ?: return noSuch("exercise in a routine", routineExerciseId)
        val target = clampTargetSets(re.targetSets + delta)
        if (target != re.targetSets) tx.setTargetSets(routineExerciseId, target)
        return Done()
    }

    /** `set_target_reps`: the reps the exercise aims at per set, null for none; written only when it changes. */
    suspend fun setTargetReps(tx: CommandTx, routineExerciseId: String, targetReps: Int?): CommandResult {
        val re = tx.routineExercise(routineExerciseId) ?: return noSuch("exercise in a routine", routineExerciseId)
        if (re.targetReps != targetReps) tx.setTargetReps(routineExerciseId, targetReps)
        return Done()
    }

    /** Gives [rows] (in list order) the positions 0, 1, 2 …; only rows whose position changes are written. */
    private suspend fun writeRoutinePositions(tx: CommandTx, rows: List<RoutineExerciseRow>) {
        rows.forEachIndexed { position, re -> if (re.position != position) tx.setRoutineExercisePosition(re.id, position) }
    }

    // --- programs ---

    /** `create_program`: an inactive, empty program called [name] (trimmed) in [mode]. */
    suspend fun createProgram(tx: CommandTx, name: String, mode: ProgramMode): CommandResult {
        val row = ProgramRow(ids(), name.trim(), mode, isActive = false)
        tx.insertProgram(row)
        return Done(mapOf(PROGRAM_ID to row.id))
    }

    /** `rename_program`: the name, trimmed. */
    suspend fun renameProgram(tx: CommandTx, programId: String, name: String): CommandResult {
        tx.program(programId) ?: return noSuch("program", programId)
        tx.renameProgram(programId, name.trim())
        return Done()
    }

    /** `set_program_mode`: fixed or rotating; the days, their order and their weekdays stay, so switching back restores the schedule. */
    suspend fun setProgramMode(tx: CommandTx, programId: String, mode: ProgramMode): CommandResult {
        val program = tx.program(programId) ?: return noSuch("program", programId)
        if (program.mode != mode) tx.setProgramMode(programId, mode)
        return Done()
    }

    /** `activate_program`: at most one active program, so every other active one is switched off in the same transaction. */
    suspend fun activateProgram(tx: CommandTx, programId: String): CommandResult {
        val program = tx.program(programId) ?: return noSuch("program", programId)
        tx.activePrograms().filter { it.id != programId }.forEach { tx.setProgramActive(it.id, false) }
        if (!program.isActive) tx.setProgramActive(programId, true)
        return Done()
    }

    /** `deactivate_program`: leaves no program active, if this was the active one. */
    suspend fun deactivateProgram(tx: CommandTx, programId: String): CommandResult {
        val program = tx.program(programId) ?: return noSuch("program", programId)
        if (program.isActive) tx.setProgramActive(programId, false)
        return Done()
    }

    /** `delete_program`: the program and its days. Workouts started from it are kept. */
    suspend fun deleteProgram(tx: CommandTx, programId: String): CommandResult {
        tx.program(programId) ?: return noSuch("program", programId)
        tx.programDays(programId).forEach { tx.deleteProgramDay(it.id) }
        tx.deleteProgram(programId)
        return Done()
    }

    /** `add_program_day`: [routineId] as the program's last day, with no weekdays. */
    suspend fun addProgramDay(tx: CommandTx, programId: String, routineId: String): CommandResult {
        tx.program(programId) ?: return noSuch("program", programId)
        tx.routine(routineId) ?: return noSuch("routine", routineId)
        val position = (tx.programDays(programId).maxOfOrNull { it.position } ?: -1) + 1
        val row = ProgramDayRow(ids(), programId, routineId, position, weekdays = 0)
        tx.insertProgramDay(row)
        return Done(mapOf(PROGRAM_DAY_ID to row.id))
    }

    /** `remove_program_day`: the day goes and the gap closes, so rotating days keep counting 1, 2, 3. */
    suspend fun removeProgramDay(tx: CommandTx, dayId: String): CommandResult {
        val day = tx.programDay(dayId) ?: return noSuch("program day", dayId)
        tx.deleteProgramDay(dayId)
        writeDayPositions(tx, tx.programDays(day.programId))
        return Done()
    }

    /** `move_program_day`: [delta] places (−1 up, +1 down); a move past either end changes nothing. */
    suspend fun moveProgramDay(tx: CommandTx, dayId: String, delta: Int): CommandResult {
        val day = tx.programDay(dayId) ?: return noSuch("program day", dayId)
        moved(tx.programDays(day.programId), dayId, delta, ProgramDayRow::id)?.let { writeDayPositions(tx, it) }
        return Done()
    }

    /** `toggle_program_weekday`: [weekday] (ISO, 1 Monday … 7 Sunday) on or off for the day ([toggleWeekday]). */
    suspend fun toggleProgramWeekday(tx: CommandTx, dayId: String, weekday: Int): CommandResult {
        val day = tx.programDay(dayId) ?: return noSuch("program day", dayId)
        tx.setProgramDayWeekdays(dayId, toggleWeekday(day.weekdays, weekday))
        return Done()
    }

    private suspend fun writeDayPositions(tx: CommandTx, days: List<ProgramDayRow>) {
        days.forEachIndexed { position, day -> if (day.position != position) tx.setProgramDayPosition(day.id, position) }
    }

    /** [rows] (in position order) with the row [id] moved [delta] places; null when it is not there or would move past an end. */
    private fun <T> moved(rows: List<T>, id: String, delta: Int, idOf: (T) -> String): List<T>? {
        val list = rows.toMutableList()
        val from = list.indexOfFirst { idOf(it) == id }
        val to = from + delta
        if (from < 0 || to !in list.indices) return null
        list.add(to, list.removeAt(from))
        return list
    }

    /**
     * The set and, with [openSets], every un-ticked set of the same exercise
     * ("Apply to all sets": ticked sets stay as logged, the edited one gets the
     * value even if ticked); null when the set does not exist.
     */
    private suspend fun setsToWrite(tx: CommandTx, setId: String, openSets: Boolean): List<String>? {
        val set = tx.set(setId) ?: return null
        if (!openSets) return listOf(setId)
        return tx.sets(set.workoutExerciseId).filter { it.id == setId || !it.completed }.map { it.id }
    }

    private suspend fun currentSetId(tx: CommandTx, workoutId: String): String? {
        val sets = tx.workoutExercises(workoutId).map { tx.sets(it.id) }
        val index = currentSet(sets.map { list -> list.map { it.completed } }) ?: return null
        return sets[index.exercise][index.set].id
    }

    /** A new last set for [we], pre-filled by the usual rule: the set before it, else last session's at this position, else 0 × 0. */
    private suspend fun insertPrefilledSet(tx: CommandTx, we: WorkoutExerciseRow): String {
        val existing = tx.sets(we.id)
        val values = prefillSet(existing.lastOrNull()?.values, tx.previousSession(we.exerciseId, we.workoutId), existing.size)
            ?: LoggedSet(weightKg = 0.0, reps = 0)
        val row = SetRow(ids(), we.id, (existing.maxOfOrNull { it.position } ?: -1) + 1, values.weightKg, values.reps, completed = false)
        tx.insertSet(row)
        return row.id
    }

    private fun noSuch(what: String, id: String): Rejected = Rejected(listOf("no $what $id"))
}
