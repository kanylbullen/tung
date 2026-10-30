package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncResponse
import io.github.kanylbullen.tung.core.sync.SyncTable
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.web.store.IndexedDbStore
import io.github.kanylbullen.tung.web.store.Tombstone
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.Write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The browser's real IndexedDB (the tests run in Chrome): what is written is what the next page load reads. */
class IndexedDbStoreTest {

    @Test
    fun whatIsWrittenIsReadBackByTheNextOpen() = runTest {
        val name = "tung-test-${kotlin.js.Date.now().toLong()}"
        IndexedDbStore.delete(name)
        val first = IndexedDbStore.open(name)
        try {
            val store = TungStore()
            val journal = ArrayList<Write>()
            store.apply(
                SyncResponse(
                    7,
                    SyncChanges(
                        exercises = listOf(SyncExercise("ex1", "bench_press", "Bench press", "chest", "barbell", false, 90, updatedAt = 5)),
                        workouts = listOf(SyncWorkout("w1", startedAt = 1, endedAt = 2, updatedAt = 5)),
                    ),
                ),
                SyncChanges(), journal,
            )
            store.tombstones[SyncTable.SETS to "s9"] = Tombstone(deletedAt = 6, dirty = true)
            journal += Write.PutTombstone(SyncTable.SETS, "s9", store.encodeTombstone(SyncTable.SETS, "s9", Tombstone(6, true)))
            first.write(journal)
            first.putMeta("cursor", "7")
            first.putMeta("token", "tung_secret")
            first.putMeta("token", null)
        } finally {
            first.close()
        }

        val second = IndexedDbStore.open(name)
        try {
            val loaded = TungStore()
            second.loadInto(loaded)
            assertEquals(90, loaded.exercises.get("ex1")?.restSeconds)
            assertEquals(2, loaded.workouts.get("w1")?.endedAt)
            assertEquals(Tombstone(6, true), loaded.tombstones[SyncTable.SETS to "s9"])
            assertEquals("7", second.meta("cursor"))
            assertNull(second.meta("token"), "a null removes the key")

            // A delete and a dirty flag survive the round trip too.
            val journal = ArrayList<Write>()
            loaded.apply(SyncResponse(8, SyncChanges(workouts = listOf(SyncWorkout("w1", updatedAt = 9, deletedAt = 9)))), SyncChanges(), journal)
            loaded.exercises.markDirty("ex1")
            journal += Write.Put(SyncTable.EXERCISES, "ex1", loaded.exercises.encoded("ex1") ?: error("ex1"))
            second.write(journal)
        } finally {
            second.close()
        }

        val third = IndexedDbStore.open(name)
        try {
            val loaded = TungStore()
            third.loadInto(loaded)
            assertNull(loaded.workouts.get("w1"))
            assertTrue(loaded.exercises.rows.getValue("ex1").dirty)
        } finally {
            third.close()
            IndexedDbStore.delete(name)
        }
    }
}
