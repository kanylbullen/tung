package io.github.kanylbullen.tung.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPlannerTest {

    private fun workout(id: String, at: Long = 1) = SyncWorkout(id, at, null, null, null, null, at)
    private fun exercise(id: String, at: Long = 1) = SyncWorkoutExercise(id, "w", "e", 0, at)
    private fun set(id: String, at: Long = 1) = SyncSet(id, "we", 0, 50.0, 5, true, at)

    @Test
    fun nothingDirtyIsOneEmptyPullWithTheStoredCursor() {
        val plan = SyncPlanner.plan(SyncChanges(), SyncChanges(), cursor = 42)
        assertEquals(listOf(SyncRequest(cursor = 42)), plan)
    }

    @Test
    fun dirtyRowsAndTombstonesGoInOneRequest() {
        val dirty = SyncChanges(workouts = listOf(workout("w")), sets = listOf(set("s1")))
        val tombstones = SyncChanges(
            sets = listOf(tombstoneEntry(SyncTable.SETS, "s2", 7).row as SyncSet),
        )
        val plan = SyncPlanner.plan(dirty, tombstones, cursor = 3)
        assertEquals(1, plan.size)
        val changes = plan.single().changes
        assertEquals(3, plan.single().cursor)
        assertEquals(listOf("w"), changes.workouts.map { it.id })
        assertEquals(listOf("s1", "s2"), changes.sets.map { it.id })
        assertEquals(7L, changes.sets.last().deletedAt)
        assertEquals(null, changes.sets.last().weightKg)
    }

    @Test
    fun requestsAreChunkedAtTheLimitWithParentsFirst() {
        // Children listed before parents on purpose: the plan reorders them.
        val dirty = SyncChanges(
            sets = (1..5).map { set("s$it") },
            workoutExercises = listOf(exercise("we")),
            workouts = listOf(workout("w")),
        )
        val plan = SyncPlanner.plan(dirty, SyncChanges(), cursor = 0, maxRows = 3)
        assertEquals(listOf(3, 3, 1), plan.map { it.changes.size() })
        assertEquals(listOf("w"), plan[0].changes.workouts.map { it.id })
        assertEquals(listOf("we"), plan[0].changes.workoutExercises.map { it.id })
        assertEquals(listOf("s1"), plan[0].changes.sets.map { it.id })
        assertEquals(listOf("s2", "s3", "s4"), plan[1].changes.sets.map { it.id })
        assertEquals(listOf("s5"), plan[2].changes.sets.map { it.id })
        assertTrue(plan.all { it.changes.size() <= 3 })
    }

    @Test
    fun theDefaultLimitIsTheProtocolsMaximum() {
        val dirty = SyncChanges(sets = (0..SYNC_MAX_ROWS).map { set("s$it") })
        val plan = SyncPlanner.plan(dirty, SyncChanges(), cursor = 0)
        assertEquals(listOf(SYNC_MAX_ROWS, 1), plan.map { it.changes.size() })
    }

    @Test
    fun theNewerOfARowAndATombstoneForTheSameIdIsSent() {
        val tombstoneOld = SyncChanges(sets = listOf(tombstoneEntry(SyncTable.SETS, "s", 5).row as SyncSet))
        assertEquals(null, SyncPlanner.plan(SyncChanges(sets = listOf(set("s", 9))), tombstoneOld, 0).single().changes.sets.single().deletedAt)

        val tombstoneNew = SyncChanges(sets = listOf(tombstoneEntry(SyncTable.SETS, "s", 9).row as SyncSet))
        assertEquals(9L, SyncPlanner.plan(SyncChanges(sets = listOf(set("s", 5))), tombstoneNew, 0).single().changes.sets.single().deletedAt)

        val tombstoneTie = SyncChanges(sets = listOf(tombstoneEntry(SyncTable.SETS, "s", 5).row as SyncSet))
        assertEquals(5L, SyncPlanner.plan(SyncChanges(sets = listOf(set("s", 5))), tombstoneTie, 0).single().changes.sets.single().deletedAt)
    }

    @Test
    fun theSameIdInTwoTablesIsTwoRows() {
        val dirty = SyncChanges(workouts = listOf(workout("x")), sets = listOf(set("x")))
        assertEquals(2, SyncPlanner.plan(dirty, SyncChanges(), 0).single().changes.size())
    }

    @Test
    fun tombstonesOfEveryTableHaveOnlyIdAndTimes() {
        SyncTable.entries.forEach { table ->
            val entry = tombstoneEntry(table, "id-${table.key}", 77)
            val back = listOf(entry).toChanges().entries().single()
            assertEquals(table, back.table)
            assertEquals("id-${table.key}", back.id)
            assertEquals(77L, back.updatedAt)
            assertEquals(77L, back.deletedAt)
        }
    }
}
