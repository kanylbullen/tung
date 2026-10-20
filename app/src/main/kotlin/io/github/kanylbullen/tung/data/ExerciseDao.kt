package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercise ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercise")
    suspend fun getAll(): List<Exercise>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun getById(id: String): Exercise?

    @Insert
    suspend fun insert(exercise: Exercise)

    /** Inserts unless a row with the same id exists; an existing row is left untouched. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(exercise: Exercise)

    @Query("UPDATE exercise SET restSeconds = :restSeconds, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun updateRestSeconds(id: String, restSeconds: Int?, updatedAt: Long)
}
