package io.github.kanylbullen.tung.sync

import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.data.Workout
import org.junit.Assert.assertEquals
import org.junit.Test

/** `import_source` and `import_batch_id` (issue #5) over sync: carried both ways, and a null from a peer never erases them. */
class SyncMappingImportTest {

    private val local = Workout(
        id = "w", startedAt = 1_000, endedAt = 2_000, updatedAt = 2_000, dirty = false,
        importSource = "jefit", importBatchId = "batch-1",
    )

    @Test
    fun theColumnsArePushed() {
        val row = local.toSync()
        assertEquals("jefit", row.importSource)
        assertEquals("batch-1", row.importBatchId)
    }

    @Test
    fun aNullFromAPeerKeepsThem() {
        val merged = SyncWorkout("w", 1_000, 2_000, "Edited", null, null, 3_000).toEntity(local)!!
        assertEquals("Edited", merged.note)
        assertEquals("jefit", merged.importSource)
        assertEquals("batch-1", merged.importBatchId)
    }

    @Test
    fun aValueFromAPeerIsTaken() {
        val row = SyncWorkout("w", 1_000, 2_000, null, null, null, 3_000, importSource = "jefit", importBatchId = "batch-2")
        assertEquals("batch-2", row.toEntity(null)!!.importBatchId)
        assertEquals("jefit", row.toEntity(Workout(id = "w", startedAt = 1_000, updatedAt = 2_000))!!.importSource)
    }
}
