package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    // --- workouts ---

    @Insert
    suspend fun insertWorkout(workout: Workout)

    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveWorkout(): Flow<Workout?>

    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveWorkout(): Workout?

    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkout(id: String): Flow<Workout?>

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun getWorkout(id: String): Workout?

    @Query("UPDATE workout SET endedAt = :endedAt, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun setEndedAt(id: String, endedAt: Long, updatedAt: Long)

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: String)

    @Query(
        """
        SELECT w.id, w.startedAt, w.endedAt,
            (SELECT COUNT(*) FROM workout_exercise we WHERE we.workoutId = w.id) AS exerciseCount,
            (SELECT COALESCE(SUM(s.weightKg * s.reps), 0.0)
               FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id
              WHERE we.workoutId = w.id AND s.completed = 1) AS volumeKg
        FROM workout w
        WHERE w.endedAt IS NOT NULL
        ORDER BY w.startedAt DESC
        """
    )
    fun observeFinishedWorkouts(): Flow<List<WorkoutSummaryRow>>

    // --- exercises within a workout ---

    @Transaction
    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY position")
    fun observeExercisesWithSets(workoutId: String): Flow<List<WorkoutExerciseWithSets>>

    @Transaction
    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY position")
    suspend fun getExercisesWithSets(workoutId: String): List<WorkoutExerciseWithSets>

    @Query("SELECT * FROM workout_exercise WHERE id = :id")
    suspend fun getWorkoutExercise(id: String): WorkoutExercise?

    @Query("SELECT COALESCE(MAX(position), -1) FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun maxExercisePosition(workoutId: String): Int

    @Insert
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExercise)

    @Query("DELETE FROM workout_exercise WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: String)

    @Query("SELECT id FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun workoutExerciseIds(workoutId: String): List<String>

    /** The exercises [deleteEmptyExercises] would delete. */
    @Query(
        """
        SELECT id FROM workout_exercise
        WHERE workoutId = :workoutId
          AND NOT EXISTS (SELECT 1 FROM set_entry s WHERE s.workoutExerciseId = workout_exercise.id)
        """
    )
    suspend fun emptyExerciseIds(workoutId: String): List<String>

    @Query(
        """
        DELETE FROM workout_exercise
        WHERE workoutId = :workoutId
          AND NOT EXISTS (SELECT 1 FROM set_entry s WHERE s.workoutExerciseId = workout_exercise.id)
        """
    )
    suspend fun deleteEmptyExercises(workoutId: String)

    // --- sets ---

    @Query("SELECT * FROM set_entry WHERE workoutExerciseId = :workoutExerciseId ORDER BY position")
    suspend fun getSets(workoutExerciseId: String): List<SetEntry>

    @Query("SELECT * FROM set_entry WHERE id = :id")
    suspend fun getSet(id: String): SetEntry?

    @Insert
    suspend fun insertSet(set: SetEntry)

    @Query("UPDATE set_entry SET weightKg = :weightKg, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateWeight(id: String, weightKg: Double, updatedAt: Long)

    @Query("UPDATE set_entry SET reps = :reps, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateReps(id: String, reps: Int, updatedAt: Long)

    @Query("UPDATE set_entry SET completed = :completed, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun setCompleted(id: String, completed: Boolean, updatedAt: Long)

    @Query("DELETE FROM set_entry WHERE id = :id")
    suspend fun deleteSet(id: String)

    @Query("SELECT id FROM set_entry WHERE workoutExerciseId = :workoutExerciseId")
    suspend fun setIds(workoutExerciseId: String): List<String>

    @Query(
        """
        SELECT s.id FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id
        WHERE we.workoutId = :workoutId
        """
    )
    suspend fun setIdsOfWorkout(workoutId: String): List<String>

    /** The sets [deleteUnloggedSets] would delete. */
    @Query(
        """
        SELECT id FROM set_entry
        WHERE (completed = 0 OR reps <= 0)
          AND workoutExerciseId IN (SELECT id FROM workout_exercise WHERE workoutId = :workoutId)
        """
    )
    suspend fun unloggedSetIds(workoutId: String): List<String>

    /** Removes sets that were never ticked or have no reps — see [io.github.kanylbullen.tung.core.isLogged]. */
    @Query(
        """
        DELETE FROM set_entry
        WHERE (completed = 0 OR reps <= 0)
          AND workoutExerciseId IN (SELECT id FROM workout_exercise WHERE workoutId = :workoutId)
        """
    )
    suspend fun deleteUnloggedSets(workoutId: String)

    @Query(
        """
        SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id
        WHERE we.workoutId = :workoutId AND s.completed = 1 AND s.reps > 0
        """
    )
    suspend fun countLoggedSets(workoutId: String): Int

    /**
     * The app's most important query: the sets of exercise [exerciseId] from the
     * most recent finished workout that contains it, in order.
     */
    @Query(
        """
        SELECT s.* FROM set_entry s
        WHERE s.completed = 1 AND s.workoutExerciseId = (
            SELECT we.id FROM workout_exercise we JOIN workout w ON we.workoutId = w.id
            WHERE we.exerciseId = :exerciseId
              AND w.endedAt IS NOT NULL
              AND w.id != :excludeWorkoutId
            ORDER BY w.startedAt DESC, we.position ASC
            LIMIT 1
        )
        ORDER BY s.position
        """
    )
    suspend fun previousSessionSets(exerciseId: String, excludeWorkoutId: String): List<SetEntry>

    /** Heaviest completed set for an exercise in finished workouts started before [before]. */
    @Query(
        """
        SELECT MAX(s.weightKg) FROM set_entry s
        JOIN workout_exercise we ON s.workoutExerciseId = we.id
        JOIN workout w ON we.workoutId = w.id
        WHERE we.exerciseId = :exerciseId
          AND w.endedAt IS NOT NULL
          AND w.startedAt < :before
          AND s.completed = 1 AND s.reps > 0
        """
    )
    suspend fun previousMaxWeight(exerciseId: String, before: Long): Double?

    /**
     * How many sets the routine that [workoutId] was started from asks for of
     * [exerciseId], or null when the workout has no routine, the routine is
     * gone, or the exercise is not in it. Read when "Log set" decides whether to
     * add a set, so a routine's sets are not padded past their target.
     */
    @Query(
        """
        SELECT MAX(re.targetSets) FROM routine_exercise re
        JOIN workout w ON re.routineId = w.routineId
        WHERE w.id = :workoutId AND re.exerciseId = :exerciseId
        """
    )
    suspend fun routineTargetSets(workoutId: String, exerciseId: String): Int?
}
