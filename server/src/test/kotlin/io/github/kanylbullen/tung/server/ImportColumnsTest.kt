package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `import_source` and `import_batch_id` on workouts (V11, issue #5): the
 * importing phone syncs them, a null from any client keeps them, and the
 * REST reads and the export show them.
 */
class ImportColumnsTest {

    private val batch = "0b6f7d3e-2c4a-4b1e-9f53-8d1c2a7e6b40"

    private fun row(id: String, updatedAt: Long, note: String? = null) =
        SyncWorkout(id, 1_600_000_000_000, 1_600_003_600_000, note, null, null, updatedAt)

    private fun SyncWorkout.imported() = copy(importSource = "jefit", importBatchId = batch)

    @Test
    fun syncCarriesThemAndANullDoesNotEraseThem() = tungTest {
        val token = client.register()
        val workoutId = id()
        val first = client.synced(token, changes = SyncChanges(workouts = listOf(row(workoutId, 1_000).imported())))
        assertEquals("jefit", first.changes.workouts.single().importSource)
        assertEquals(batch, first.changes.workouts.single().importBatchId)

        val newer = client.synced(token, changes = SyncChanges(workouts = listOf(row(workoutId, 2_000, note = "later"))))
        newer.changes.workouts.single().let {
            assertEquals("later", it.note)
            assertEquals("jefit", it.importSource)
            assertEquals(batch, it.importBatchId)
        }

        val pulled = client.synced(token).changes.workouts.single { it.id == workoutId }
        assertEquals("jefit", pulled.importSource)

        val viaRest = client.authedGet("/api/v1/workouts/$workoutId", token).workout()
        assertEquals("jefit", viaRest.importSource)
        assertEquals(batch, viaRest.importBatchId)

        val put = client.put(token, workoutId, workout(1_600_000_000_000, updatedAt = 3_000, note = "edited"))
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals("edited", put.workout().note)
        assertEquals("jefit", put.workout().importSource)
        assertEquals(batch, put.workout().importBatchId)
    }

    @Test
    fun theExportShowsThemAndNullForAWorkoutLoggedInTung() = tungTest {
        val token = client.register()
        val imported = id()
        val own = id()
        client.synced(token, changes = SyncChanges(workouts = listOf(row(imported, 1_000).imported(), row(own, 1_000))))
        val workouts = json(client.authedGet("/api/v1/export", token).bodyAsText())["workouts"]!!.jsonArray.map { it.jsonObject }
        val exported = workouts.single { it["id"]!!.str == imported }
        assertEquals("jefit", exported["import_source"]!!.str)
        assertEquals(batch, exported["import_batch_id"]!!.str)
        val logged = workouts.single { it["id"]!!.str == own }
        assertEquals("null", logged["import_source"].toString())
        assertEquals("null", logged["import_batch_id"].toString())
    }

    @Test
    fun malformedValuesAreRejected() = tungTest {
        val token = client.register()
        for (bad in listOf(
            row(id(), 1_000).copy(importSource = "Jefit!"),
            row(id(), 1_000).copy(importSource = ""),
            row(id(), 1_000).copy(importBatchId = "not-a-uuid"),
        )) {
            val response = client.sync(token, changes = SyncChanges(workouts = listOf(bad)))
            assertEquals(HttpStatusCode.BadRequest, response.status, bad.toString())
            assertEquals("invalid_rows", response.errorCode())
            val body = response.bodyAsText()
            assertTrue(listOf("import_source", "import_batch_id").any { it in body }, body)
        }
    }
}
