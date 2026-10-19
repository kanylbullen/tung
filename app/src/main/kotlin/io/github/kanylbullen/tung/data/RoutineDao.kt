package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {

    // --- routines ---

    @Insert
    suspend fun insertRoutine(routine: Routine)

    /** Most recently used first; never-used routines last (NULL sorts last in DESC), then by name. */
    @Query(
        """
        SELECT r.id, r.name, r.lastUsedAt,
            (SELECT COUNT(*) FROM routine_exercise re WHERE re.routineId = r.id) AS exerciseCount
        FROM routine r
        ORDER BY r.lastUsedAt DESC, r.name COLLATE NOCASE
        """
    )
    fun observeRoutineSummaries(): Flow<List<RoutineSummaryRow>>

    @Query("SELECT * FROM routine WHERE id = :id")
    fun observeRoutine(id: String): Flow<Routine?>

    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun getRoutine(id: String): Routine?

    @Query("UPDATE routine SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    @Query("UPDATE routine SET lastUsedAt = :lastUsedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setLastUsedAt(id: String, lastUsedAt: Long, updatedAt: Long)

    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun deleteRoutine(id: String)

    // --- exercises within a routine ---

    @Insert
    suspend fun insertRoutineExercise(routineExercise: RoutineExercise)

    @Transaction
    @Query("SELECT * FROM routine_exercise WHERE routineId = :routineId ORDER BY position")
    fun observeRoutineExercises(routineId: String): Flow<List<RoutineExerciseWithExercise>>

    @Query("SELECT * FROM routine_exercise WHERE routineId = :routineId ORDER BY position")
    suspend fun getRoutineExercises(routineId: String): List<RoutineExercise>

    @Query("SELECT * FROM routine_exercise WHERE id = :id")
    suspend fun getRoutineExercise(id: String): RoutineExercise?

    @Query("UPDATE routine_exercise SET position = :position, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setPosition(id: String, position: Int, updatedAt: Long)

    @Query("UPDATE routine_exercise SET targetSets = :targetSets, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setTargetSets(id: String, targetSets: Int, updatedAt: Long)
}
