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
 * The Health Connect columns on workouts (V9, issue #45): the phone syncs
 * them, a null from any client keeps them, and the REST reads, the export and
 * MCP's history show them.
 */
class HealthColumnsTest {

    private fun row(id: String, updatedAt: Long, note: String? = null) =
        SyncWorkout(id, 1_700_000_000_000, 1_700_003_600_000, note, null, null, updatedAt)

    private fun SyncWorkout.withHealth() = copy(avgHeartRate = 128, maxHeartRate = 171, activeCalories = 342, healthConnectId = "hc-abc")

    @Test
    fun syncCarriesThemAndANullDoesNotEraseThem() = tungTest {
        val token = client.register()
        val workoutId = id()
        val first = client.synced(token, changes = SyncChanges(workouts = listOf(row(workoutId, 1_000).withHealth())))
        first.changes.workouts.single().let {
            assertEquals(128, it.avgHeartRate)
            assertEquals(171, it.maxHeartRate)
            assertEquals(342, it.activeCalories)
            assertEquals("hc-abc", it.healthConnectId)
        }

        // A newer row from a client without them (the watch, an older app) wins the row and keeps them.
        val newer = client.synced(token, changes = SyncChanges(workouts = listOf(row(workoutId, 2_000, note = "later"))))
        newer.changes.workouts.single().let {
            assertEquals("later", it.note)
            assertEquals(2_000, it.updatedAt)
            assertEquals(128, it.avgHeartRate)
            assertEquals(171, it.maxHeartRate)
            assertEquals(342, it.activeCalories)
            assertEquals("hc-abc", it.healthConnectId)
        }

        // Another device pulling from scratch gets them.
        val pulled = client.synced(token).changes.workouts.single { it.id == workoutId }
        assertEquals(342, pulled.activeCalories)
        assertEquals("hc-abc", pulled.healthConnectId)

        // So do the REST API ...
        val viaRest = client.authedGet("/api/v1/workouts/$workoutId", token).workout()
        assertEquals(128, viaRest.avgHeartRate)
        assertEquals(171, viaRest.maxHeartRate)
        assertEquals(342, viaRest.activeCalories)
        assertEquals("hc-abc", viaRest.healthConnectId)

        // ... and a PUT that does not carry them keeps them.
        val put = client.put(token, workoutId, workout(1_700_000_000_000, updatedAt = 3_000, note = "edited"))
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals("edited", put.workout().note)
        assertEquals(128, put.workout().avgHeartRate)
        assertEquals("hc-abc", put.workout().healthConnectId)
    }

    @Test
    fun theExportShowsThem() = tungTest {
        val token = client.register()
        val workoutId = id()
        client.synced(token, changes = SyncChanges(workouts = listOf(row(workoutId, 1_000).withHealth())))

        val text = client.authedGet("/api/v1/export", token).bodyAsText()
        val exported = json(text)["workouts"]!!.jsonArray.single().jsonObject
        assertEquals("128", exported["avg_heart_rate"]!!.str)
        assertEquals("171", exported["max_heart_rate"]!!.str)
        assertEquals("342", exported["active_calories"]!!.str)
        assertEquals("hc-abc", exported["health_connect_id"]!!.str)
    }

    @Test
    fun aWorkoutWithoutThemExportsThemAsNull() = tungTest {
        val token = client.register()
        client.synced(token, changes = SyncChanges(workouts = listOf(row(id(), 1_000))))
        val exported = json(client.authedGet("/api/v1/export", token).bodyAsText())["workouts"]!!.jsonArray.single().jsonObject
        for (key in listOf("avg_heart_rate", "max_heart_rate", "active_calories", "health_connect_id")) {
            assertEquals("null", exported[key].toString(), key)
        }
    }

    @Test
    fun implausibleValuesAreRejected() = tungTest {
        val token = client.register()
        for (bad in listOf(
            row(id(), 1_000).copy(avgHeartRate = 5),
            row(id(), 1_000).copy(maxHeartRate = 400),
            row(id(), 1_000).copy(activeCalories = -1),
            row(id(), 1_000).copy(healthConnectId = ""),
        )) {
            val response = client.sync(token, changes = SyncChanges(workouts = listOf(bad)))
            assertEquals(HttpStatusCode.BadRequest, response.status, bad.toString())
            assertEquals("invalid_rows", response.errorCode())
            val body = response.bodyAsText()
            assertTrue(listOf("avg_heart_rate", "max_heart_rate", "active_calories", "health_connect_id").any { it in body }, body)
        }
    }
}
