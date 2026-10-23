package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.ProgramMode

/**
 * What the [CommandExecutor] reads and writes, inside one transaction the
 * store has opened: Room in `data/`, Exposed on the server, and later a web
 * or iOS client's own store (issue #87). Storage only: the rules live in the
 * executor, ids come from it, and the store stamps time its own way
 * (`updatedAt` and `dirty` on a device, `GREATEST(now, updated_at + 1)` on
 * the server). Reads see live rows only (a tombstoned row is none) and lists
 * come in position order. A delete takes the store's own record for sync
 * (a tombstone per row) with it; a delete of a parent is asked for only once
 * the executor has deleted its children, so a store deletes one row at a time.
 */
interface CommandTx {
    /** The store's clock, what it stamps writes with; a rule uses it for the times it records (a workout's start and end). */
    fun now(): Long

    // --- workouts ---

    suspend fun workout(id: String): WorkoutRow?

    /** The workout in progress (no end), the latest started when there are several; null when none. */
    suspend fun activeWorkout(): WorkoutRow?

    suspend fun workoutExercise(id: String): WorkoutExerciseRow?

    suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow>

    suspend fun set(id: String): SetRow?

    suspend fun sets(workoutExerciseId: String): List<SetRow>

    /**
     * The sets of [exerciseId] in the latest finished workout that has it,
     * other than [excludeWorkoutId] (the one being written): the pre-fill's
     * source. Empty with no history.
     */
    suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet>

    /** How many sets the routine [workoutId] was started from asks for of [exerciseId]; null when the workout is not from a routine. */
    suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int?

    suspend fun insertWorkout(row: WorkoutRow)

    suspend fun insertWorkoutExercise(row: WorkoutExerciseRow)

    suspend fun insertSet(row: SetRow)

    /** Ends the workout at [endedAt]. */
    suspend fun endWorkout(id: String, endedAt: Long)

    suspend fun tickSet(id: String, done: Boolean)

    suspend fun setWeight(id: String, weightKg: Double)

    suspend fun setReps(id: String, reps: Int)

    /** Deletes one set, with whatever the store records for sync (a tombstone). */
    suspend fun deleteSet(id: String)

    /** Deletes an exercise of a workout and its sets, with the store's tombstones for each. */
    suspend fun deleteWorkoutExercise(id: String)

    /** Deletes a workout with its exercises and sets, with the store's tombstones for each. */
    suspend fun deleteWorkout(id: String)

    // --- routines ---

    suspend fun routine(id: String): RoutineRow?

    suspend fun routineExercise(id: String): RoutineExerciseRow?

    suspend fun routineExercises(routineId: String): List<RoutineExerciseRow>

    suspend fun insertRoutine(row: RoutineRow)

    suspend fun insertRoutineExercise(row: RoutineExerciseRow)

    suspend fun renameRoutine(id: String, name: String)

    /** Records that a workout was started from the routine now (`lastUsedAt`). */
    suspend fun markRoutineUsed(routineId: String)

    suspend fun setRoutineExercisePosition(id: String, position: Int)

    suspend fun setTargetSets(id: String, targetSets: Int)

    suspend fun setTargetReps(id: String, targetReps: Int?)

    /** Deletes one exercise of a routine, with the store's tombstone. */
    suspend fun deleteRoutineExercise(id: String)

    /** Deletes the routine's own row, with the store's tombstone; its exercises and the days that used it are already gone. */
    suspend fun deleteRoutine(id: String)

    // --- programs ---

    suspend fun program(id: String): ProgramRow?

    /** Every program that is active (there should be at most one; a sync can bring a second). */
    suspend fun activePrograms(): List<ProgramRow>

    suspend fun programDay(id: String): ProgramDayRow?

    suspend fun programDays(programId: String): List<ProgramDayRow>

    /** The days of every program that start [routineId]. */
    suspend fun programDaysUsing(routineId: String): List<ProgramDayRow>

    suspend fun insertProgram(row: ProgramRow)

    suspend fun insertProgramDay(row: ProgramDayRow)

    suspend fun renameProgram(id: String, name: String)

    suspend fun setProgramMode(id: String, mode: ProgramMode)

    suspend fun setProgramActive(id: String, active: Boolean)

    suspend fun setProgramDayPosition(id: String, position: Int)

    suspend fun setProgramDayWeekdays(id: String, weekdays: Int)

    /** Deletes one day of a program, with the store's tombstone. */
    suspend fun deleteProgramDay(id: String)

    /** Deletes the program's own row, with the store's tombstone; its days are already gone. */
    suspend fun deleteProgram(id: String)
}

/** A workout as the rules see it. */
data class WorkoutRow(
    val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val routineId: String?,
    val programDayId: String?,
    /** Who started it (`core.StartedBy`), null when unknown. */
    val startedBy: String?,
)

/** One exercise of a workout as the rules see it. */
data class WorkoutExerciseRow(val id: String, val workoutId: String, val exerciseId: String, val position: Int)

/** One set as the rules see it. */
data class SetRow(
    val id: String,
    val workoutExerciseId: String,
    val position: Int,
    val weightKg: Double,
    val reps: Int,
    val completed: Boolean,
) {
    val values: LoggedSet get() = LoggedSet(weightKg, reps)
}

/** A saved routine as the rules see it. */
data class RoutineRow(val id: String, val name: String, val lastUsedAt: Long?)

/** One exercise of a routine as the rules see it. */
data class RoutineExerciseRow(
    val id: String,
    val routineId: String,
    val exerciseId: String,
    val position: Int,
    val targetSets: Int,
    val targetReps: Int?,
)

/** A program as the rules see it. */
data class ProgramRow(val id: String, val name: String, val mode: ProgramMode, val isActive: Boolean)

/** One day of a program as the rules see it. */
data class ProgramDayRow(val id: String, val programId: String, val routineId: String, val position: Int, val weekdays: Int)

/** One exercise of a routine to create: the exercise's row id with its targets, for [CommandExecutor.saveRoutinePlan]. */
data class RoutineExercisePlan(val exerciseId: String, val targetSets: Int, val targetReps: Int?)
