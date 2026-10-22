package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutDao {

    // --- workouts ---

    @Insert
    suspend fun insertWorkout(workout: Workout)

    // --- an import's rows: deterministic ids, so a second import of the same rows is ignored ---

    /** -1 when a workout with this id exists (an earlier import of the same session). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkoutIfAbsent(workout: Workout): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkoutExercisesIfAbsent(workoutExercises: List<WorkoutExercise>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSetsIfAbsent(sets: List<SetEntry>)

    /** The workouts one import wrote ([Workout.importBatchId]). */
    @Query("SELECT id FROM workout WHERE importBatchId = :batchId")
    suspend fun importBatchWorkoutIds(batchId: String): List<String>

    /** Which of [ids] are workouts here already. */
    @Query("SELECT id FROM workout WHERE id IN (:ids)")
    suspend fun existingWorkoutIds(ids: List<String>): List<String>

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

    /**
     * Remembers the Health Connect session written for the workout, only if
     * none is stored yet: the id is written once (CLAUDE.md, "Health Connect").
     * @return 1 if it was stored, 0 if the workout is gone or already had one.
     */
    @Query("UPDATE workout SET healthConnectId = :healthConnectId, updatedAt = :updatedAt, dirty = 1 WHERE id = :id AND healthConnectId IS NULL")
    suspend fun setHealthConnectId(id: String, healthConnectId: String, updatedAt: Long): Int

    /** Stores what Health Connect had for the workout; a null keeps the stored value. */
    @Query(
        """
        UPDATE workout SET avgHeartRate = COALESCE(:avgHeartRate, avgHeartRate),
            maxHeartRate = COALESCE(:maxHeartRate, maxHeartRate),
            activeCalories = COALESCE(:activeCalories, activeCalories),
            updatedAt = :updatedAt, dirty = 1
        WHERE id = :id
        """,
    )
    suspend fun setVitals(id: String, avgHeartRate: Int?, maxHeartRate: Int?, activeCalories: Int?, updatedAt: Long)

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

    /**
     * The exercises of the latest [limit] finished workouts, newest workout
     * first and each in position order: what the picker's suggestions learn
     * from (`core.picker.suggest`).
     */
    @Query(
        """
        SELECT w.id AS workoutId, w.startedAt AS startedAt, we.exerciseId AS exerciseId
        FROM workout_exercise we JOIN workout w ON we.workoutId = w.id
        WHERE w.id IN (SELECT id FROM workout WHERE endedAt IS NOT NULL ORDER BY startedAt DESC LIMIT :limit)
        ORDER BY w.startedAt DESC, we.position ASC
        """
    )
    suspend fun recentExerciseOrder(limit: Int): List<WorkoutExerciseOrderRow>

    // --- exercises within a workout ---

    @Transaction
    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY position")
    fun observeExercisesWithSets(workoutId: String): Flow<List<WorkoutExerciseWithSets>>

    @Transaction
    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY position")
    suspend fun getExercisesWithSets(workoutId: String): List<WorkoutExerciseWithSets>

    @Query("SELECT * FROM workout_exercise WHERE id = :id")
    suspend fun getWorkoutExercise(id: String): WorkoutExercise?

    @Query("SELECT * FROM workout_exercise WHERE workoutId = :workoutId ORDER BY position")
    suspend fun getWorkoutExercises(workoutId: String): List<WorkoutExercise>

    @Insert
    suspend fun insertWorkoutExercise(workoutExercise: WorkoutExercise)

    @Query("DELETE FROM workout_exercise WHERE id = :id")
    suspend fun deleteWorkoutExercise(id: String)

    @Query("SELECT id FROM workout_exercise WHERE workoutId = :workoutId")
    suspend fun workoutExerciseIds(workoutId: String): List<String>

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

    /**
     * Every counted set (ticked, reps > 0) of [exerciseId] in finished
     * workouts, for the exercise page's history; core's `exerciseHistory`
     * summarises them.
     */
    @Query(
        """
        SELECT w.id AS workoutId, we.id AS workoutExerciseId, w.startedAt AS startedAt,
            we.position AS exercisePosition, s.position AS setPosition, s.weightKg AS weightKg, s.reps AS reps
        FROM set_entry s
        JOIN workout_exercise we ON s.workoutExerciseId = we.id
        JOIN workout w ON we.workoutId = w.id
        WHERE we.exerciseId = :exerciseId
          AND w.endedAt IS NOT NULL
          AND s.completed = 1 AND s.reps > 0
        ORDER BY w.startedAt DESC, we.position, s.position
        """
    )
    fun observeExerciseHistory(exerciseId: String): Flow<List<ExerciseHistoryRow>>

    /**
     * Every counted set (ticked, reps > 0) in finished workouts, of every
     * exercise, with its exercise and the workout's start: the one read behind
     * the Progress tab (`core.progress.progressOverview`). Imported workouts are
     * ordinary finished workouts and count the same.
     */
    @Query(
        """
        SELECT we.exerciseId AS exerciseId, w.id AS workoutId, we.id AS workoutExerciseId, w.startedAt AS startedAt,
            we.position AS exercisePosition, s.position AS setPosition, s.weightKg AS weightKg, s.reps AS reps
        FROM set_entry s
        JOIN workout_exercise we ON s.workoutExerciseId = we.id
        JOIN workout w ON we.workoutId = w.id
        WHERE w.endedAt IS NOT NULL
          AND s.completed = 1 AND s.reps > 0
        ORDER BY w.startedAt, we.position, s.position
        """
    )
    fun observeProgressSets(): Flow<List<ProgressSetRow>>

    /** [observeProgressSets] for one exercise: the exercise progress screen and the sheet in a workout. */
    @Query(
        """
        SELECT we.exerciseId AS exerciseId, w.id AS workoutId, we.id AS workoutExerciseId, w.startedAt AS startedAt,
            we.position AS exercisePosition, s.position AS setPosition, s.weightKg AS weightKg, s.reps AS reps
        FROM set_entry s
        JOIN workout_exercise we ON s.workoutExerciseId = we.id
        JOIN workout w ON we.workoutId = w.id
        WHERE we.exerciseId = :exerciseId
          AND w.endedAt IS NOT NULL
          AND s.completed = 1 AND s.reps > 0
        ORDER BY w.startedAt, we.position, s.position
        """
    )
    fun observeProgressSets(exerciseId: String): Flow<List<ProgressSetRow>>

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
     * gone, or the exercise is not in it. Read when "Log set" with `addNext`
     * decides whether to add a set, so a routine's sets are not padded past their target.
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
