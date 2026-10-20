package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgramDao {

    // --- programs ---

    @Insert
    suspend fun insertProgram(program: Program)

    /** The active program first, then by name. */
    @Query(
        """
        SELECT p.id, p.name, p.mode, p.isActive,
            (SELECT COUNT(*) FROM program_day d WHERE d.programId = p.id) AS dayCount
        FROM program p
        ORDER BY p.isActive DESC, p.name COLLATE NOCASE
        """
    )
    fun observeProgramSummaries(): Flow<List<ProgramSummaryRow>>

    @Query("SELECT * FROM program WHERE id = :id")
    fun observeProgram(id: String): Flow<Program?>

    @Query("SELECT * FROM program WHERE id = :id")
    suspend fun getProgram(id: String): Program?

    @Query("SELECT * FROM program WHERE isActive = 1 LIMIT 1")
    fun observeActiveProgram(): Flow<Program?>

    @Query("UPDATE program SET name = :name, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun rename(id: String, name: String, updatedAt: Long)

    /** Deactivates every active program other than [exceptId]. */
    @Query("UPDATE program SET isActive = 0, updatedAt = :updatedAt, dirty = 1 WHERE isActive = 1 AND id != :exceptId")
    suspend fun deactivateOthers(exceptId: String, updatedAt: Long)

    @Query("UPDATE program SET isActive = :isActive, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean, updatedAt: Long)

    @Query("DELETE FROM program WHERE id = :id")
    suspend fun deleteProgram(id: String)

    @Query("SELECT id FROM program_day WHERE programId = :programId")
    suspend fun dayIds(programId: String): List<String>

    // --- days ---

    @Insert
    suspend fun insertDay(day: ProgramDay)

    @Query(
        """
        SELECT d.id, d.programId, d.routineId, d.position, d.weekdays, r.name AS routineName
        FROM program_day d JOIN routine r ON r.id = d.routineId
        WHERE d.programId = :programId
        ORDER BY d.position
        """
    )
    fun observeDays(programId: String): Flow<List<ProgramDayRow>>

    @Query("SELECT * FROM program_day WHERE programId = :programId ORDER BY position")
    suspend fun getDays(programId: String): List<ProgramDay>

    @Query("SELECT * FROM program_day WHERE id = :id")
    suspend fun getDay(id: String): ProgramDay?

    @Query("SELECT COALESCE(MAX(position), -1) FROM program_day WHERE programId = :programId")
    suspend fun maxPosition(programId: String): Int

    @Query("UPDATE program_day SET position = :position, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun setPosition(id: String, position: Int, updatedAt: Long)

    @Query("UPDATE program_day SET weekdays = :weekdays, updatedAt = :updatedAt, dirty = 1 WHERE id = :id")
    suspend fun setWeekdays(id: String, weekdays: Int, updatedAt: Long)

    @Query("DELETE FROM program_day WHERE id = :id")
    suspend fun deleteDay(id: String)

    /**
     * The program day of the latest finished workout started from one of
     * [programId]'s days that still exists; null if there is none.
     */
    @Query(
        """
        SELECT w.programDayId FROM workout w
        JOIN program_day d ON d.id = w.programDayId
        WHERE d.programId = :programId AND w.endedAt IS NOT NULL
        ORDER BY w.endedAt DESC
        LIMIT 1
        """
    )
    fun observeLastCompletedDayId(programId: String): Flow<String?>
}
