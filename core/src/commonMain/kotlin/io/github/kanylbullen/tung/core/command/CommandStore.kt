package io.github.kanylbullen.tung.core.command

import io.github.kanylbullen.tung.core.LoggedSet

/**
 * What the [CommandExecutor] reads and writes, inside one transaction the
 * store has opened: Room in `data/`, Exposed on the server, and later a web
 * or iOS client's own store (issue #87). Storage only: the rules live in the
 * executor, ids come from it, and the store stamps time its own way
 * (`updatedAt` and `dirty` on a device, `GREATEST(now, updated_at + 1)` on
 * the server). Reads see live rows only (a tombstoned row is none) and lists
 * come in position order.
 */
interface CommandTx {
    suspend fun workout(id: String): WorkoutRow?

    suspend fun workoutExercise(id: String): WorkoutExerciseRow?

    suspend fun workoutExercises(workoutId: String): List<WorkoutExerciseRow>

    suspend fun set(id: String): SetRow?

    suspend fun sets(workoutExerciseId: String): List<SetRow>

    /**
     * Last session's logged sets of [exerciseId] in order: the latest finished
     * workout other than [excludeWorkoutId] that has it (the app's
     * `previousSessionSets`, the server's `lastSessionIn`).
     */
    suspend fun previousSession(exerciseId: String, excludeWorkoutId: String): List<LoggedSet>

    /** How many sets the routine [workoutId] was started from asks for of [exerciseId], read live; null outside a routine. */
    suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int?

    suspend fun insertWorkoutExercise(row: WorkoutExerciseRow)

    suspend fun insertSet(row: SetRow)

    suspend fun tickSet(id: String, done: Boolean)

    suspend fun setWeight(id: String, weightKg: Double)

    suspend fun setReps(id: String, reps: Int)

    /** Deletes one set, with whatever the store records for sync (a tombstone). */
    suspend fun deleteSet(id: String)

    /** Deletes an exercise of a workout and its sets, with the store's tombstones for each. */
    suspend fun deleteWorkoutExercise(id: String)
}

/** A workout as the rules see it. */
data class WorkoutRow(val id: String, val routineId: String?, val endedAt: Long?)

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
