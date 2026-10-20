package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.catalogExerciseId
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

        val response = client.authedGet("/api/v1/export", session)
        assertEquals(HttpStatusCode.OK, response.status)
        val disposition = response.headers[HttpHeaders.ContentDisposition].orEmpty()
        assertTrue(disposition.startsWith("attachment") && "tung-export-" in disposition && ".json" in disposition, disposition)

        val text = response.bodyAsText()
        val body = json(text)
        assertEquals(setOf("format", "version", "exported_at", "user", "exercises", "workouts", "tokens"), body.keys)
        assertEquals("tung-export", body["format"]!!.str)
        assertEquals("1", body["version"]!!.str)
        assertEquals(email, body["user"]!!.jsonObject["email"]!!.str)

        val doc = ApiJson.decodeFromString(ExportDocument.serializer(), text)
        assertEquals(setOf(catalogExerciseId("deadlift"), customId), doc.exercises.map { it.id }.toSet(), "own rows only, not the catalogue")
        assertEquals(workoutIds.reversed(), doc.workouts.map { it.id })
        assertEquals(40.0, doc.workouts[0].exercises.single().sets.single().weightKg)
        assertEquals(setOf("session" to "write", "agent" to "read"), doc.tokens.map { it.name to it.scope }.toSet())
        assertEquals(setOf("name", "scope", "created_at"), body["tokens"]!!.jsonArray.first().jsonObject.keys)

        assertFalse(session in text || apiToken in text, "no token values")
        assertFalse(Tokens.hash(session) in text, "no token hashes")
        assertFalse("argon2" in text, "no password hash")
        assertFalse("not yours" in text)
    }
}
