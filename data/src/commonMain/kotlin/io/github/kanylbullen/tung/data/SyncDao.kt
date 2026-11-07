package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/**
 * Sync's access to the eight synced tables (`sync/SyncStore.kt` is the only
 * caller): the dirty rows to push, rows by id to merge into, upserts and
 * deletes of what the server sent, and clearing the dirty flag of what was
 * pushed. Upserts write rows as the server has them, `dirty = 0`; @Upsert
 * updates in place, so a parent's update never cascades to its children the
 * way REPLACE would.
 */
@Dao
interface SyncDao {

    // --- dirty rows; an untouched catalogue exercise is never one ---

    @Query("SELECT * FROM exercise WHERE dirty = 1 AND NOT (nameKey IS NOT NULL AND updatedAt = 0)")
    suspend fun dirtyExercises(): List<Exercise>

    @Query("SELECT * FROM workout WHERE dirty = 1")
    suspend fun dirtyWorkouts(): List<Workout>

    @Query("SELECT * FROM workout_exercise WHERE dirty = 1")
    suspend fun dirtyWorkoutExercises(): List<WorkoutExercise>

    @Query("SELECT * FROM set_entry WHERE dirty = 1")
    suspend fun dirtySets(): List<SetEntry>

    @Query("SELECT * FROM routine WHERE dirty = 1")
    suspend fun dirtyRoutines(): List<Routine>

    @Query("SELECT * FROM routine_exercise WHERE dirty = 1")
    suspend fun dirtyRoutineExercises(): List<RoutineExercise>

    @Query("SELECT * FROM program WHERE dirty = 1")
    suspend fun dirtyPrograms(): List<Program>

    @Query("SELECT * FROM program_day WHERE dirty = 1")
    suspend fun dirtyProgramDays(): List<ProgramDay>

    // --- rows by id; callers keep each list under SQLite's 999 variables ---

    @Query("SELECT * FROM exercise WHERE id IN (:ids)")
    suspend fun exercises(ids: List<String>): List<Exercise>

    @Query("SELECT * FROM workout WHERE id IN (:ids)")
    suspend fun workouts(ids: List<String>): List<Workout>

    @Query("SELECT * FROM workout_exercise WHERE id IN (:ids)")
    suspend fun workoutExercises(ids: List<String>): List<WorkoutExercise>

    @Query("SELECT * FROM set_entry WHERE id IN (:ids)")
    suspend fun sets(ids: List<String>): List<SetEntry>

    @Query("SELECT * FROM routine WHERE id IN (:ids)")
    suspend fun routines(ids: List<String>): List<Routine>

    @Query("SELECT * FROM routine_exercise WHERE id IN (:ids)")
    suspend fun routineExercises(ids: List<String>): List<RoutineExercise>

    @Query("SELECT * FROM program WHERE id IN (:ids)")
    suspend fun programs(ids: List<String>): List<Program>

    @Query("SELECT * FROM program_day WHERE id IN (:ids)")
    suspend fun programDays(ids: List<String>): List<ProgramDay>

    // --- clearing the flag: only while the row is still the version that was pushed ---

    @Query("UPDATE exercise SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearExercise(id: String, updatedAt: Long)

    @Query("UPDATE workout SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearWorkout(id: String, updatedAt: Long)

    @Query("UPDATE workout_exercise SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearWorkoutExercise(id: String, updatedAt: Long)

    @Query("UPDATE set_entry SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearSet(id: String, updatedAt: Long)

    @Query("UPDATE routine SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearRoutine(id: String, updatedAt: Long)

    @Query("UPDATE routine_exercise SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearRoutineExercise(id: String, updatedAt: Long)

    @Query("UPDATE program SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearProgram(id: String, updatedAt: Long)

    @Query("UPDATE program_day SET dirty = 0 WHERE id = :id AND updatedAt = :updatedAt")
    suspend fun clearProgramDay(id: String, updatedAt: Long)

    // --- what the server sent ---

    @Upsert
    suspend fun upsertExercise(row: Exercise)

    @Upsert
    suspend fun upsertWorkout(row: Workout)

    @Upsert
    suspend fun upsertWorkoutExercise(row: WorkoutExercise)

    @Upsert
    suspend fun upsertSet(row: SetEntry)

    @Upsert
    suspend fun upsertRoutine(row: Routine)

    @Upsert
    suspend fun upsertRoutineExercise(row: RoutineExercise)

    @Upsert
    suspend fun upsertProgram(row: Program)

    @Upsert
    suspend fun upsertProgramDay(row: ProgramDay)

    @Query("DELETE FROM exercise WHERE id = :id")
    suspend fun deleteExercise(id: String)

    /** An exercise used by a workout or routine cannot be deleted (RESTRICT). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM workout_exercise WHERE exerciseId = :id) " +
            "OR EXISTS(SELECT 1 FROM routine_exercise WHERE exerciseId = :id)"
    )
    suspend fun exerciseInUse(id: String): Boolean

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: String)

    @Query("DELETE FROM workout_exercise WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: String)

    @Query("DELETE FROM set_entry WHERE id = :id")
    suspend fun deleteSet(id: String)

    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun deleteRoutine(id: String)

    @Query("DELETE FROM routine_exercise WHERE id = :id")
    suspend fun deleteRoutineExercise(id: String)

    @Query("DELETE FROM program WHERE id = :id")
    suspend fun deleteProgram(id: String)

    @Query("DELETE FROM program_day WHERE id = :id")
    suspend fun deleteProgramDay(id: String)

    // --- a new account: everything the user has goes up again ---

    @Query("UPDATE exercise SET dirty = 1 WHERE NOT (nameKey IS NOT NULL AND updatedAt = 0)")
    suspend fun markExercisesDirty()

    @Query("UPDATE workout SET dirty = 1")
    suspend fun markWorkoutsDirty()

    @Query("UPDATE workout_exercise SET dirty = 1")
    suspend fun markWorkoutExercisesDirty()

    @Query("UPDATE set_entry SET dirty = 1")
    suspend fun markSetsDirty()

    @Query("UPDATE routine SET dirty = 1")
    suspend fun markRoutinesDirty()

    @Query("UPDATE routine_exercise SET dirty = 1")
    suspend fun markRoutineExercisesDirty()

    @Query("UPDATE program SET dirty = 1")
    suspend fun markProgramsDirty()

    @Query("UPDATE program_day SET dirty = 1")
    suspend fun markProgramDaysDirty()

    // --- another account's data on this device (issue #152) ---

    /**
     * Anything a sign-in would upload: a workout, a routine, a program, or an
     * exercise that is not an untouched catalogue row (a custom one, an
     * extended entry picked, a catalogue row whose rest time was set).
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM workout) OR EXISTS(SELECT 1 FROM routine) OR EXISTS(SELECT 1 FROM program) " +
            "OR EXISTS(SELECT 1 FROM exercise WHERE NOT (nameKey IS NOT NULL AND updatedAt = 0))"
    )
    suspend fun hasUserData(): Boolean

    /**
     * Changes no account has: the dirty rows (an untouched catalogue row
     * never counts) and dirty tombstones, which "Delete them" would lose.
     */
    @Query(
        "SELECT (SELECT COUNT(*) FROM exercise WHERE dirty = 1 AND NOT (nameKey IS NOT NULL AND updatedAt = 0)) " +
            "+ (SELECT COUNT(*) FROM workout WHERE dirty = 1) + (SELECT COUNT(*) FROM workout_exercise WHERE dirty = 1) " +
            "+ (SELECT COUNT(*) FROM set_entry WHERE dirty = 1) + (SELECT COUNT(*) FROM routine WHERE dirty = 1) " +
            "+ (SELECT COUNT(*) FROM routine_exercise WHERE dirty = 1) + (SELECT COUNT(*) FROM program WHERE dirty = 1) " +
            "+ (SELECT COUNT(*) FROM program_day WHERE dirty = 1) + (SELECT COUNT(*) FROM tombstones WHERE dirty = 1)"
    )
    suspend fun unsyncedCount(): Int

    @Query("DELETE FROM set_entry")
    suspend fun wipeSets()

    @Query("DELETE FROM workout_exercise")
    suspend fun wipeWorkoutExercises()

    @Query("DELETE FROM workout")
    suspend fun wipeWorkouts()

    @Query("DELETE FROM program_day")
    suspend fun wipeProgramDays()

    @Query("DELETE FROM program")
    suspend fun wipePrograms()

    @Query("DELETE FROM routine_exercise")
    suspend fun wipeRoutineExercises()

    @Query("DELETE FROM routine")
    suspend fun wipeRoutines()

    /** Custom exercises and picked extended entries: everything but the seeded catalogue. */
    @Query("DELETE FROM exercise WHERE nameKey IS NULL")
    suspend fun wipeOwnExercises()

    /** The catalogue's rows back to the seed: no rest time of the account's, `updatedAt` 0 ([DatabaseSchema.CATALOG_UPDATED_AT]), not dirty. */
    @Query("UPDATE exercise SET restSeconds = NULL, updatedAt = 0, dirty = 0 WHERE nameKey IS NOT NULL")
    suspend fun resetCatalogue()

    @Query("DELETE FROM tombstones")
    suspend fun wipeTombstones()
}
