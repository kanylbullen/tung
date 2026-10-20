package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.ProgramMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class AiPromptTest {

    private val zone = ZoneId.of("Europe/Stockholm")
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private val day = 86_400_000L

    private fun set(kg: Double, reps: Int, completed: Boolean = true) = AiHistorySet(kg, reps, completed)

    private fun context(): AiContext = buildAiContext(
        now = now,
        zone = zone,
        history = listOf(
            AiHistoryWorkout(
                now.toEpochMilli() - 2 * day,
                routineName = "Push",
                exercises = listOf(
                    AiHistoryExercise("bench_press", "Bench press", listOf(set(80.0, 8), set(80.0, 7), set(80.0, 0, completed = true), set(85.0, 5, completed = false))),
                    AiHistoryExercise(null, "Landmine press", listOf(set(30.0, 10))),
                    AiHistoryExercise("dips", "Dips", listOf(set(0.0, 0, completed = false))),
                ),
            ),
            AiHistoryWorkout(now.toEpochMilli() - 9 * day, null, listOf(AiHistoryExercise("back_squat", "Back squat", listOf(set(100.0, 5))))),
            // Too old, and one with nothing logged: neither is sent.
            AiHistoryWorkout(now.toEpochMilli() - 120 * day, null, listOf(AiHistoryExercise("deadlift", "Deadlift", listOf(set(140.0, 3))))),
            AiHistoryWorkout(now.toEpochMilli() - 3 * day, null, listOf(AiHistoryExercise("deadlift", "Deadlift", listOf(set(140.0, 3, completed = false))))),
        ),
        routines = listOf(AiRoutine("Push", listOf(AiRoutineExercise("bench_press", "Bench press", 3, 8)))),
        programs = listOf(AiProgram("PPL", ProgramMode.ROTATING.key, active = true, days = listOf(AiProgramDay(1, "Push")), lastCompletedDay = 1)),
        catalogueKeys = listOf("deadlift", "bench_press", "back_squat", "dips"),
    )

    @Test
    fun theContextCarriesOnlyLoggedTrainingNewestFirst() {
        val c = context()
        assertEquals("2026-10-20", c.today)
        assertEquals(2, c.weekday)
        assertEquals(listOf("2026-10-18", "2026-10-11"), c.history.map { it.date })
        val push = c.history.first()
        assertEquals("Push", push.routine)
        assertEquals(listOf("bench_press", null), push.exercises.map { it.key })
        assertEquals(listOf(AiSet(80.0, 8), AiSet(80.0, 7)), push.exercises.first().sets)
        assertEquals(listOf("back_squat", "bench_press", "deadlift", "dips"), c.catalogueKeys)
    }

    @Test
    fun historyIsCappedAtThirtyWorkouts() {
        val many = (1..40).map { AiHistoryWorkout(now.toEpochMilli() - it * day, null, listOf(AiHistoryExercise("deadlift", "Deadlift", listOf(set(100.0, 5))))) }
        val c = buildAiContext(now, zone, many, emptyList(), emptyList(), emptyList())
        assertEquals(AI_HISTORY_WORKOUTS, c.history.size)
        assertEquals("2026-10-19", c.history.first().date)
    }

    /**
     * The context is built from shapes that have no field for an email, a name,
     * a device id, a server URL or a note, so the request a provider receives
     * has none: not one `@`, in either wire format.
     */
    @Test
    fun noPersonalDataReachesEitherRequest() {
        val c = context()
        val contextJson = AiContextJson.encodeToString(AiContext.serializer(), c)
        listOf("email", "device", "server", "note", "user_id", "@").forEach { assertFalse("context has $it", contextJson.contains(it)) }

        for (request in listOf(AiRequest.Program, AiRequest.NextWorkout, AiRequest.Plateaus)) {
            val prompt = buildAiPrompt(request, c, language = "sv")
            for (provider in AiProviderKind.entries) {
                val http = aiHttpRequest(AiCall(provider, "https://llm.example.org", "test-model"), prompt, apiKey = "sk-secret")
                assertFalse("${request.key}/$provider body has an @", http.body.contains('@'))
                assertFalse("the key is in the body", http.body.contains("sk-secret"))
            }
        }
    }

    @Test
    fun thePromptAsksForStrictJsonAndCarriesTheData() {
        val c = context()
        val prompt = buildAiPrompt(AiRequest.Program, c, language = "sv")
        assertTrue(prompt.system.contains("exactly one JSON object"))
        assertTrue(prompt.system.contains("\"routines\""))
        assertTrue(prompt.system.contains("\"target_sets\""))
        assertTrue(prompt.system.contains("\"sv\""))
        // The data is the context's JSON, verbatim, after the question.
        val json = prompt.user.substringAfter("My training data (JSON):\n")
        assertEquals(c, Json.decodeFromString(AiContext.serializer(), json))

        assertTrue(buildAiPrompt(AiRequest.NextWorkout, c).system.contains("\"weight_kg\""))
        assertTrue(buildAiPrompt(AiRequest.Plateaus, c).system.contains("\"plateaus\""))
        assertTrue(buildAiPrompt(AiRequest.Plateaus, c).user.contains("stopped progressing"))
    }

    @Test
    fun theContextJsonIsSnakeCase() {
        val o = Json.parseToJsonElement(AiContextJson.encodeToString(AiContext.serializer(), context())) as JsonObject
        assertEquals("kg", o.getValue("unit").jsonPrimitive.content)
        assertTrue("catalogue_keys" in o)
        val set = o.getValue("history").jsonArray[0].jsonObject.getValue("exercises").jsonArray[0].jsonObject.getValue("sets").jsonArray[0].jsonObject
        assertEquals(setOf("weight_kg", "reps"), set.keys)
        assertTrue("last_completed_day" in o.getValue("programs").jsonArray[0].jsonObject)
    }
}
