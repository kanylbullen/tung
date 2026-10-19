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
    suspend fun insertWorkout(workout: Workout): Long

    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    fun observeActiveWorkout(): Flow<Workout?>

    @Query("SELECT * FROM workout WHERE endedAt IS NULL ORDER BY startedAt DESC LIMIT 1")
    suspend fun getActiveWorkout(): Workout?

    @Query("SELECT * FROM workout WHERE id = :id")
    fun observeWorkout(id: Long): Flow<Workout?>

    @Query("SELECT * FROM workout WHERE id = :id")
    suspend fun getWorkout(id: Long): Workout?

    @Query("UPDATE workout SET endedAt = :endedAt WHERE id = :id")
    suspend fun setEndedAt(id: Long, endedAt: Long)

    @Query("DELETE FROM workout WHERE id = :id")
    suspend fun deleteWorkout(id: Long)

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
    fun observeExercisesWithSets(workoutId: Long): Flow<List<WorkoutExerciseWithSets>>

    @Query("SELECT * FROM workout_exercise WHERE id = :id")
    suspend fun getWorkoutExercise(id: Long): WorkoutExercise?

    @Query("SELECT COALESCE(MAX(position), -1) FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun maxExercisePosition(workoutId: Long): Int

    @Insert
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExercise): Long

    @Query("DELETE FROM workout_exercise WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: Long)

    @Query(
        """
        DELETE FROM workout_exercise
        WHERE workoutId = :workoutId
          AND NOT EXISTS (SELECT 1 FROM set_entry s WHERE s.workoutExerciseId = workout_exercise.id)
        """
    )
    suspend fun deleteEmptyExercises(workoutId: Long)

    // --- sets ---

    @Query("SELECT * FROM set_entry WHERE workoutExerciseId = :workoutExerciseId ORDER BY position")
    suspend fun getSets(workoutExerciseId: Long): List<SetEntry>

    @Insert
    suspend fun insertSet(set: SetEntry): Long

    @Query("UPDATE set_entry SET weightKg = :weightKg WHERE id = :id")
    suspend fun updateWeight(id: Long, weightKg: Double)

    @Query("UPDATE set_entry SET reps = :reps WHERE id = :id")
    suspend fun updateReps(id: Long, reps: Int)

    @Query("UPDATE set_entry SET completed = :completed WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean)

    @Query("DELETE FROM set_entry WHERE id = :id")
    suspend fun deleteSet(id: Long)

    /** Removes sets that were never ticked or have no reps — see [io.github.kanylbullen.tung.domain.isLogged]. */
    @Query(
        """
        DELETE FROM set_entry
        WHERE (completed = 0 OR reps <= 0)
          AND workoutExerciseId IN (SELECT id FROM workout_exercise WHERE workoutId = :workoutId)
        """
    )
    suspend fun deleteUnloggedSets(workoutId: Long)

    @Query(
        """
        SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON s.workoutExerciseId = we.id
        WHERE we.workoutId = :workoutId AND s.completed = 1 AND s.reps > 0
        """
    )
    suspend fun countLoggedSets(workoutId: Long): Int

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
    suspend fun previousSessionSets(exerciseId: Long, excludeWorkoutId: Long): List<SetEntry>

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
    suspend fun previousMaxWeight(exerciseId: Long, before: Long): Double?
}
