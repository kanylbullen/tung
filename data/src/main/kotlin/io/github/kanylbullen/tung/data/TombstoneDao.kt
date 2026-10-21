package io.github.kanylbullen.tung.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kanylbullen.tung.core.sync.SyncTable

@Dao
interface TombstoneDao {
    /** A second delete of the same id (after it came back from the server) replaces the first. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tombstones: List<Tombstone>)

    @Query("SELECT * FROM tombstones WHERE dirty = 1")
    suspend fun dirty(): List<Tombstone>

    @Query("SELECT * FROM tombstones WHERE tableName = :tableName AND id IN (:ids)")
    suspend fun byIds(tableName: String, ids: List<String>): List<Tombstone>

    /** Clears the flag only if the tombstone is still the one that was pushed. */
    @Query("UPDATE tombstones SET dirty = 0 WHERE tableName = :tableName AND id = :id AND deletedAt = :deletedAt")
    suspend fun clearDirty(tableName: String, id: String, deletedAt: Long)

    /** A row the server brought back: it is no longer deleted here. */
    @Query("DELETE FROM tombstones WHERE tableName = :tableName AND id = :id")
    suspend fun delete(tableName: String, id: String)

    /** Rows written again here (a re-import after an undo): they are no longer deleted. */
    @Query("DELETE FROM tombstones WHERE tableName = :tableName AND id IN (:ids)")
    suspend fun deleteAll(tableName: String, ids: List<String>)

    @Query("UPDATE tombstones SET dirty = 1")
    suspend fun markAllDirty()
}

/**
 * Records that [ids] of [table] were deleted at [at]. Call it inside the
 * transaction that deletes them, with every id the delete cascades to as well,
 * so the server hears of each row.
 */
suspend fun TungDatabase.recordDeletes(table: SyncTable, ids: Collection<String>, at: Long) {
    if (ids.isNotEmpty()) tombstoneDao().insertAll(ids.map { Tombstone(table.key, it, at) })
}
