package io.github.kanylbullen.tung.server

import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExerciseTest {

    private val catalogue = CoreCatalog.parse(javaClass.getResource("/catalog/core.json")!!.readText())

    private suspend fun io.ktor.client.HttpClient.exercises(token: String): List<ExerciseDto> =
        ApiJson.decodeFromString(ExerciseList.serializer(), authedGet("/api/v1/exercises", token).bodyAsText()).exercises

    @Test
    fun theCatalogueIsSeededWithTheAppsIds() = tungTest {
        val token = client.register()
        val list = client.exercises(token)
        assertEquals(catalogue.size, list.size, "seeded once, however many times the server started")
        assertEquals(catalogue.map { catalogExerciseId(it.key) }.toSet(), list.map { it.id }.toSet())
        val bench = list.single { it.nameKey == "bench_press" }
        assertEquals(catalogExerciseId("bench_press"), bench.id)
        assertEquals("Bench press", bench.name)
        assertFalse(bench.isCustom)
        assertEquals(0, bench.updatedAt)
        // The resource is the repo's catalogue file, not a copy.
        assertEquals(File("../catalog/core.json").readText(), javaClass.getResource("/catalog/core.json")!!.readText())
    }

    @Test
    fun customExercisesAreTheirOwnersOnly() = tungTest {
        val alice = client.register()
        val bob = client.register()
        val customId = id()
        val created = client.post("/api/v1/exercises") {
            bearerAuth(alice)
            jsonBody("""{"id": "$customId", "name": " Zercher squat ", "muscle_group": "legs", "equipment": "barbell", "rest_seconds": 120}""")
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val dto = ApiJson.decodeFromString(ExerciseDto.serializer(), created.bodyAsText())
        assertEquals("Zercher squat", dto.name)
        assertTrue(dto.isCustom)
        assertNull(dto.nameKey)

        assertTrue(client.exercises(alice).any { it.id == customId })
        assertFalse(client.exercises(bob).any { it.id == customId })
        assertEquals(HttpStatusCode.NotFound, client.patch("/api/v1/exercises/$customId") { bearerAuth(bob); jsonBody("""{"name": "mine"}""") }.status)

        val renamed = client.patch("/api/v1/exercises/$customId") { bearerAuth(alice); jsonBody("""{"name": "Zercher", "rest_seconds": null}""") }
        assertEquals(HttpStatusCode.OK, renamed.status, renamed.bodyAsText())
        val after = ApiJson.decodeFromString(ExerciseDto.serializer(), renamed.bodyAsText())
        assertEquals("Zercher", after.name)
        assertNull(after.restSeconds)

        val duplicate = client.post("/api/v1/exercises") {
            bearerAuth(alice)
            jsonBody("""{"id": "$customId", "name": "Again", "muscle_group": "legs", "equipment": "barbell"}""")
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        val catalogueId = client.post("/api/v1/exercises") {
            bearerAuth(alice)
            jsonBody("""{"id": "${catalogExerciseId("bench_press")}", "name": "Mine", "muscle_group": "chest", "equipment": "barbell"}""")
        }
        assertEquals(HttpStatusCode.Conflict, catalogueId.status)
        val badGroup = client.post("/api/v1/exercises") {
            bearerAuth(alice)
            jsonBody("""{"name": "X", "muscle_group": "neck", "equipment": "barbell"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, badGroup.status)
    }

    @Test
    fun aCatalogueExerciseTakesARestTimePerUserButKeepsItsName() = tungTest {
        val alice = client.register()
        val bob = client.register()
        val bench = catalogExerciseId("bench_press")

        val rest = client.patch("/api/v1/exercises/$bench") { bearerAuth(alice); jsonBody("""{"rest_seconds": 180}""") }
        assertEquals(HttpStatusCode.OK, rest.status, rest.bodyAsText())
        assertEquals(180, ApiJson.decodeFromString(ExerciseDto.serializer(), rest.bodyAsText()).restSeconds)

        val aliceBench = client.exercises(alice).filter { it.id == bench }
        assertEquals(1, aliceBench.size, "the user's copy replaces the catalogue row")
        assertEquals(180, aliceBench.single().restSeconds)
        assertEquals("bench_press", aliceBench.single().nameKey)
        assertNull(client.exercises(bob).single { it.id == bench }.restSeconds, "other users still see the catalogue")

        val rename = client.patch("/api/v1/exercises/$bench") { bearerAuth(bob); jsonBody("""{"name": "Bänk"}""") }
        assertEquals(HttpStatusCode.BadRequest, rename.status)
        assertEquals("catalogue_name_fixed", rename.errorCode())
        val tooLong = client.patch("/api/v1/exercises/$bench") { bearerAuth(bob); jsonBody("""{"rest_seconds": 3601}""") }
        assertEquals(HttpStatusCode.BadRequest, tooLong.status)
    }
}
