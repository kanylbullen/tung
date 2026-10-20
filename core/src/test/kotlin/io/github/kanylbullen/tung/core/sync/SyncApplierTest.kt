package io.github.kanylbullen.tung.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncApplierTest {

    private fun set(id: String, updatedAt: Long, deletedAt: Long? = null) =
        SyncSet(id, "we", 0, 80.0, 8, true, updatedAt, deletedAt)

    private fun entry(updatedAt: Long, deletedAt: Long? = null) =
        SyncChanges(sets = listOf(set("s", updatedAt, deletedAt))).entries().single()

    private fun decide(updatedAt: Long, deletedAt: Long? = null, local: LocalVersion?) =
        SyncApplier.decide(entry(updatedAt, deletedAt), local)

    @Test
    fun aRowTheDeviceNeverHadIsInserted() {
        assertEquals(SyncAction.Insert, decide(100, local = null))
    }

    @Test
    fun aTombstoneForARowTheDeviceNeverHadIsIgnored() {
        assertEquals(SyncAction.Ignore, decide(100, deletedAt = 100, local = null))
    }

    @Test
    fun aStrictlyNewerRowUpdatesTheLocalOne() {
        assertEquals(SyncAction.Update, decide(101, local = LocalVersion(100)))
    }

    @Test
    fun anOlderRowIsIgnored() {
        assertEquals(SyncAction.Ignore, decide(99, local = LocalVersion(100)))
    }

    @Test
    fun anEqualTimestampIsIgnored() {
        // The device's own row echoed back by the server, or the same edit from elsewhere.
        assertEquals(SyncAction.Ignore, decide(100, local = LocalVersion(100)))
        assertEquals(SyncAction.Ignore, decide(100, deletedAt = 100, local = LocalVersion(100)))
    }

    @Test
    fun aLocalDirtyRowNewerThanTheServersIsKept() {
        // Edited on the device after the server's version; it goes out with the next push.
        assertEquals(SyncAction.Ignore, decide(150, local = LocalVersion(200)))
    }

    @Test
    fun aNewerTombstoneDeletesTheLocalRow() {
        assertEquals(SyncAction.Delete, decide(101, deletedAt = 101, local = LocalVersion(100)))
    }

    @Test
    fun anOlderTombstoneLosesToANewerLocalEdit() {
        assertEquals(SyncAction.Ignore, decide(99, deletedAt = 99, local = LocalVersion(100)))
    }

    @Test
    fun aNewerRowBringsBackALocallyDeletedOne() {
        assertEquals(SyncAction.Insert, decide(101, local = LocalVersion(100, deleted = true)))
    }

    @Test
    fun anOlderRowDoesNotUndoALocalDelete() {
        assertEquals(SyncAction.Ignore, decide(99, local = LocalVersion(100, deleted = true)))
    }

    @Test
    fun aTombstoneForALocallyDeletedRowIsIgnored() {
        assertEquals(SyncAction.Ignore, decide(101, deletedAt = 101, local = LocalVersion(100, deleted = true)))
    }

    @Test
    fun decisionsComeParentsFirstAndReadTheRightTable() {
        val response = SyncResponse(
            cursor = 9,
            changes = SyncChanges(
                sets = listOf(set("s1", 10)),
                workouts = listOf(SyncWorkout("w1", 1, null, null, null, null, 10)),
                workoutExercises = listOf(SyncWorkoutExercise("we1", "w1", "e", 0, 10)),
            ),
        )
        val asked = mutableListOf<Pair<SyncTable, String>>()
        val decisions = SyncApplier.decide(response) { table, id ->
            asked += table to id
            if (table == SyncTable.WORKOUTS) LocalVersion(20) else null
        }
        assertEquals(listOf(SyncTable.WORKOUTS, SyncTable.WORKOUT_EXERCISES, SyncTable.SETS), decisions.map { it.entry.table })
        assertEquals(listOf(SyncAction.Ignore, SyncAction.Insert, SyncAction.Insert), decisions.map { it.action })
        assertEquals(
            listOf(SyncTable.WORKOUTS to "w1", SyncTable.WORKOUT_EXERCISES to "we1", SyncTable.SETS to "s1"),
            asked,
        )
    }
}
