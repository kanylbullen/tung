package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SyncChanges
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExportTest {

    @Test
    fun exportHasEverythingTheUserHasAndNoSecrets() = tungTest {
        val email = uniqueEmail()
        val session = client.register(email)
        val other = client.register()
        val apiToken = client.post("/api/v1/tokens") { bearerAuth(session); jsonBody("""{"name": "agent", "scope": "read"}""") }.json()["token"]!!.str
        client.patch("/api/v1/exercises/${catalogExerciseId("deadlift")}") { bearerAuth(session); jsonBody("""{"rest_seconds": 240}""") }
        val customId = id()
        client.post("/api/v1/exercises") {
            bearerAuth(session)
            jsonBody("""{"id": "$customId", "name": "Sled push", "muscle_group": "legs", "equipment": "other"}""")
        }
        val workoutIds = listOf(id(), id())
        client.put(session, workoutIds[0], workout(1_700_000_000_000, exercise(BENCH, 0, set(0, 80.0, 8))))
        client.put(session, workoutIds[1], workout(1_700_100_000_000, exercise(customId, 0, set(0, 40.0, 20)), endedAt = null))
        client.put(other, id(), workout(1_700_000_000_000, exercise(SQUAT, 0, set(0, 140.0, 3)), note = "not yours"))
        // Routines and programs arrive through sync; a deleted one stays out of the export.
        val graph = Graph()
        val gone = SyncRoutine(id(), "Gone", null, 1_000)
        client.synced(session, 0, SyncChanges(routines = listOf(graph.routine, gone), routineExercises = listOf(graph.routineExercise), programs = listOf(graph.program), programDays = listOf(graph.day)))
        client.synced(session, 0, SyncChanges(routines = listOf(SyncRoutine(gone.id, updatedAt = 2_000, deletedAt = 2_000))))

        val response = client.authedGet("/api/v1/export", session)
        assertEquals(HttpStatusCode.OK, response.status)
        val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(disposition.startsWith("attachment") && "tung-export-" in disposition && ".json" in disposition, disposition)

        val text = response.bodyAsText()
        val body = json(text)
        assertEquals(setOf("format", "version", "exported_at", "user", "exercises", "workouts", "routines", "programs", "tokens"), body.keys)
        assertEquals("tung-export", body["format"]!!.str)
        assertEquals("1", body["version"]!!.str)
        assertEquals(email, body["user"]!!.jsonObject["email"]!!.str)

        val doc = ApiJson.decodeFromString(ExportDocument.serializer(), text)
        assertEquals(setOf(catalogExerciseId("deadlift"), customId), doc.exercises.map { it.id }.toSet(), "own rows only, not the catalogue")
        assertEquals(workoutIds.reversed(), doc.workouts.map { it.id })
        assertEquals(40.0, doc.workouts[0].exercises.single().sets.single().weightKg)
        assertEquals(setOf("session" to "write", "agent" to "read"), doc.tokens.map { it.name to it.scope }.toSet())
        assertEquals(listOf(graph.routine.id), doc.routines.map { it.id })
        assertEquals(listOf(graph.routineExercise.id to 3), doc.routines.single().exercises.map { it.id to it.targetSets })
        assertEquals(listOf(graph.day.id to graph.routine.id), doc.programs.single().days.map { it.id to it.routineId })
        assertEquals("fixed", doc.programs.single().mode)
        assertEquals(setOf("name", "scope", "created_at"), body["tokens"]!!.jsonArray.first().jsonObject.keys)

        assertFalse(session in text || apiToken in text, "no token values")
        assertFalse(Tokens.hash(session) in text, "no token hashes")
        assertFalse("argon2" in text, "no password hash")
        assertFalse("not yours" in text)
    }
}
