package io.github.kanylbullen.tung.ai

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import io.github.kanylbullen.tung.core.CoreCatalog
import io.github.kanylbullen.tung.core.ExtendedCatalog
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.ai.AiConfidence
import io.github.kanylbullen.tung.core.ai.AiRequest
import io.github.kanylbullen.tung.core.ai.aiCatalogue
import io.github.kanylbullen.tung.core.importing.importSuggestions
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.data.AppCommandHandler
import io.github.kanylbullen.tung.data.AppExerciseResolver
import io.github.kanylbullen.tung.data.ExerciseRepository
import io.github.kanylbullen.tung.data.ProgramRepository
import io.github.kanylbullen.tung.data.RoutineRepository
import io.github.kanylbullen.tung.data.TungDatabase
import io.github.kanylbullen.tung.data.WorkoutRepository
import io.github.kanylbullen.tung.sync.MemoryTokenStore
import io.github.kanylbullen.tung.sync.SyncSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * [AiService] end to end over a real Room database and a MockWebServer as the
 * provider: the request carries the training data and nothing personal (the
 * same DataStore holds the sync account's email, a workout has a note with an
 * address in it), the answer becomes a proposal with unknown exercises
 * dropped, and confirming runs the commands.
 */
@RunWith(RobolectricTestRunner::class)
class AiServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = TungDatabase.buildInMemory(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "settings.preferences_pb") } }
    private val server = MockWebServer()
    private val keys = MemoryTokenStore()
    private val now = Instant.parse("2026-10-20T08:00:00Z")
    private var clockMillis = now.toEpochMilli()

    private val catalogue = CoreCatalog.parse(File("../catalog/core.json").readText())
    private val extended = ExtendedCatalog({ File("../catalog/extended.json").readText() })
    private val workouts = WorkoutRepository(db, clock = { clockMillis })
    private val routines = RoutineRepository(db, clock = { clockMillis })
    private val programs = ProgramRepository(db, clock = { clockMillis })
    private val resolver = AppExerciseResolver(db.exerciseDao(), { catalogue.associate { it.key to it.name.values.toList() } }, extended)
    private val commands = AppCommandHandler(
        db = db,
        workouts = workouts,
        exercises = ExerciseRepository(db, clock = { clockMillis }),
        resolver = resolver,
        extendedCatalog = extended,
    )
    private val settings by lazy { AiSettings(store, keys) }
    private val service by lazy {
        AiService(
            settings, AiClient(aiHttpClient(timeoutMillis = 5_000)), workouts, routines, programs, resolver,
            { catalogue.map { it.key } }, commands, Clock.fixed(now, ZoneId.of("Europe/Stockholm")),
            catalogue = { aiCatalogue(catalogue) },
        )
    }

    @Before
    fun start() = server.start()

    @After
    fun stop() {
        server.shutdown()
        scope.cancel()
        db.close()
    }

    private fun count(sql: String): Int = db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    /** A finished bench press workout two days ago, 80 × 8 twice, with a note that must never be sent. */
    private fun seedHistory() = runBlocking {
        clockMillis = now.toEpochMilli() - 2 * 86_400_000L
        val workoutId = workouts.startOrResumeWorkout()
        val exerciseId = workouts.addExercise(workoutId, catalogExerciseId("bench_press"), sets = 1)
        val first = workouts.getSets(exerciseId).single().id
        workouts.updateWeight(first, 80.0)
        workouts.updateReps(first, 8)
        workouts.logSet(first, addNext = true)
        workouts.logSet(workouts.getSets(exerciseId).last().id)
        db.openHelper.writableDatabase.execSQL("UPDATE workout SET note = 'Coach: coach@example.org'")
        clockMillis += 3_600_000L
        check(workouts.finishWorkout(workoutId))
        clockMillis = now.toEpochMilli()
    }

    private fun answer(provider: AiProviderKind, text: String): MockResponse {
        val body = when (provider) {
            AiProviderKind.OPENAI_COMPATIBLE -> buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", text) }) }) })
            }
            AiProviderKind.ANTHROPIC -> buildJsonObject {
                put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
            }
        }
        return MockResponse().setHeader("Content-Type", "application/json").setBody(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), body))
    }

    @Test
    fun notSetUpMeansNothingIsPrepared() = runBlocking {
        assertNull(service.prepare(AiRequest.Program, "en"))
        assertEquals(AiFailure.NOT_CONFIGURED, service.test()?.failure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aProgramIsProposedFromTheHistoryAndSavedOnConfirm() = runBlocking {
        seedHistory()
        SyncSettings(store).signedIn("https://tung.example.org", "lifter@example.org")
        settings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
        settings.setKey("sk-test")

        val prepared = checkNotNull(service.prepare(AiRequest.Program, "sv"))
        assertTrue(prepared.previewBody.contains("bench_press"))
        assertFalse("the key is in the preview", prepared.previewBody.contains("sk-test") || prepared.previewHeaders.values.any { "sk-test" in it })

        server.enqueue(
            answer(
                AiProviderKind.OPENAI_COMPATIBLE,
                """{"rationale": "Keep benching.", "program_name": "Bench focus", "routines": [
                    {"name": "Push", "exercises": [{"key": "bench_press", "target_sets": 3, "target_reps": 8}, {"name": "Moon walk", "target_sets": 3}]},
                    {"name": "Pull", "exercises": [{"key": "barbell_row", "target_sets": 3, "target_reps": 10}, {"name": "Zercher squats", "target_sets": 2, "target_reps": 12}]}]}""",
            ),
        )
        val outcome = service.send(prepared)

        val recorded = server.takeRequest()
        val sent = recorded.body.readUtf8()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertEquals("what was shown is what was sent", prepared.http.body, sent)
        assertFalse("an @ was sent", sent.contains('@'))
        assertFalse(sent.contains("Coach") || sent.contains("tung.example.org"))
        assertTrue(sent.contains("\\\"weight_kg\\\":80.0,\\\"reps\\\":8"))

        val proposal = (outcome as AiOutcome.Proposed).proposal as AiProposal.Program
        assertEquals(listOf("Push", "Pull"), proposal.routines.map { it.name })
        assertEquals(1, proposal.dropped) // "Moon walk" is in no catalogue
        assertEquals("Zercher_Squats", proposal.routines[1].exercises[1].exercise.extendedId)

        val saved = service.confirm(proposal, proposal.programName ?: "x") as AiConfirmation.Saved
        assertEquals(2, saved.routineIds.size)
        assertEquals(2, count("SELECT COUNT(*) FROM routine"))
        assertEquals(3, count("SELECT COUNT(*) FROM routine_exercise"))
        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE name = 'Bench focus' AND mode = 'rotating' AND isActive = 0"))
        val order = db.openHelper.readableDatabase.query(
            "SELECT r.name FROM program_day d JOIN routine r ON r.id = d.routineId ORDER BY d.position",
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        assertEquals(listOf("Push", "Pull"), order)
    }

    @Test
    fun aNextWorkoutFromAnthropicIsAppliedOnConfirm() = runBlocking {
        seedHistory()
        settings.save(AiProviderKind.ANTHROPIC, server.url("/").toString().trimEnd('/'), "claude-test-model", jsonMode = true)
        settings.setKey("sk-ant-test")
        server.enqueue(
            answer(
                AiProviderKind.ANTHROPIC,
                """{"rationale": "Bench went well.", "exercises": [{"key": "bench_press", "sets": [{"weight_kg": 82.5, "reps": 8}, {"weight_kg": 82.5, "reps": 8}]}]}""",
            ),
        )
        val prepared = checkNotNull(service.prepare(AiRequest.NextWorkout, "en"))
        val proposal = (service.send(prepared) as AiOutcome.Proposed).proposal as AiProposal.NextWorkout

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertFalse(recorded.body.readUtf8().contains('@'))

        val started = service.confirm(proposal) as AiConfirmation.Started
        assertEquals(1, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL AND id = '${started.workoutId}'"))
        assertEquals(2, count("SELECT COUNT(*) FROM set_entry s JOIN workout_exercise we ON we.id = s.workoutExerciseId WHERE we.workoutId = '${started.workoutId}' AND s.weightKg = 82.5"))
    }

    @Test
    fun unreadableAndUnusableAnswersAreFailuresAndTestSendsOneToken() = runBlocking {
        settings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)

        server.enqueue(answer(AiProviderKind.OPENAI_COMPATIBLE, "I cannot help with that."))
        assertEquals(AiOutcome.Failed(AiFailure.UNREADABLE), service.send(checkNotNull(service.prepare(AiRequest.Program, "en"))))

        server.enqueue(answer(AiProviderKind.OPENAI_COMPATIBLE, """{"exercises": [{"name": "Moon walk", "sets": [{"reps": 5}]}]}"""))
        assertEquals(AiOutcome.Failed(AiFailure.NOTHING_USABLE), service.send(checkNotNull(service.prepare(AiRequest.NextWorkout, "en"))))

        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error": {"message": "bad key"}}"""))
        assertEquals(AiOutcome.Failed(AiFailure.UNAUTHORIZED, "bad key"), service.test())
        server.takeRequest()
        server.takeRequest()
        val testRequest = server.takeRequest()
        val test = testRequest.body.readUtf8()
        assertTrue(test.contains("\"max_tokens\":1"))
        assertFalse(test.contains("history"))
        // No key stored: no Authorization header at all.
        assertNull(testRequest.getHeader("Authorization"))
    }

    @Test
    fun aClaude5AnswerWithOnlyThinkingIsOutOfRoomAndARefusalIsDeclined() = runBlocking {
        settings.save(AiProviderKind.ANTHROPIC, server.url("/").toString().trimEnd('/'), "claude-haiku-5-5", jsonMode = false)

        val thinkingOnly = """{"type": "message", "role": "assistant", "model": "claude-haiku-5-5", "content": [{"type": "thinking", "thinking": "", "signature": "EuYB"}], "stop_reason": "max_tokens"}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(thinkingOnly))
        assertEquals(AiOutcome.Failed(AiFailure.OUT_OF_ROOM), service.send(checkNotNull(service.prepare(AiRequest.NextWorkout, "en"))))
        val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()) as kotlinx.serialization.json.JsonObject
        assertEquals("16000", sent.getValue("max_tokens").toString())
        assertEquals("{\"effort\":\"low\"}", sent.getValue("output_config").toString())

        val refusal = """{"type": "message", "role": "assistant", "content": [], "stop_reason": "refusal", "stop_details": {"category": "cyber"}}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(refusal))
        assertEquals(AiOutcome.Failed(AiFailure.DECLINED), service.send(checkNotNull(service.prepare(AiRequest.NextWorkout, "en"))))
    }

    /**
     * "Suggest with the assistant" in the import review: the request holds the
     * names, their candidates and the catalogue, and nothing of the history,
     * the routines, the account or the user's own exercises, even with all of
     * them in the database; the answer comes back as matches.
     */
    @Test
    fun matchingSendsOnlyTheNamesAndTheCatalogue() = runBlocking {
        seedHistory()
        routines.createFromPlan("Push day secret", emptyList())
        val ownId = ExerciseRepository(db, clock = { clockMillis })
            .createExercise("Lat pulldown at my gym", io.github.kanylbullen.tung.core.MuscleGroup.BACK, io.github.kanylbullen.tung.core.Equipment.MACHINE)
        SyncSettings(store).signedIn("https://tung.example.org", "lifter@example.org")
        settings.save(AiProviderKind.ANTHROPIC, server.url("/").toString().trimEnd('/'), "claude-test-model", jsonMode = true)
        settings.setKey("sk-ant-test")

        val names = listOf("Machine Lat Pulldown", "Heel Walking")
        val candidates = names.associateWith { importSuggestions(it, resolver.searchByName(it)) }
        val prepared = checkNotNull(service.prepareMatch(names, candidates, "en"))
        assertTrue(prepared.request is AiRequest.MatchExercises)
        server.enqueue(
            answer(
                AiProviderKind.ANTHROPIC,
                """{"matches": [{"name": "Machine Lat Pulldown", "ref": {"key": "lat_pulldown"}, "confidence": "high", "note": "Same pull."},
                    {"name": "Heel Walking", "ref": null, "confidence": "low", "note": ""}]}""",
            ),
        )
        val proposal = (service.send(prepared) as AiOutcome.Proposed).proposal as AiProposal.Matches

        val sent = server.takeRequest().body.readUtf8()
        assertEquals(prepared.http.body, sent)
        assertFalse("an @ was sent", sent.contains('@'))
        listOf("Coach", "tung.example.org", "weight_kg", "Push day secret", ownId, "at my gym", "history", "routines").forEach {
            assertFalse("$it was sent", sent.contains(it))
        }
        assertTrue(sent.contains("Machine Lat Pulldown") && sent.contains("lat_pulldown") && sent.contains("catalogue"))

        assertEquals(catalogExerciseId("lat_pulldown"), proposal.matches[0].exercise?.id)
        assertEquals(AiConfidence.HIGH, proposal.matches[0].confidence)
        assertNull(proposal.matches[1].exercise)
        // Nothing was written by the answer.
        assertEquals(0, count("SELECT COUNT(*) FROM workout WHERE endedAt IS NULL"))
    }
}
