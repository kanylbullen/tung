package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.StartedBy
import io.github.kanylbullen.tung.core.ai.AiHttpRequest
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.ai.AiRequest
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.core.command.CommandExecutor
import io.github.kanylbullen.tung.web.ai.AiConfig
import io.github.kanylbullen.tung.web.ai.AiFailure
import io.github.kanylbullen.tung.web.ai.AiHttpResult
import io.github.kanylbullen.tung.web.ai.AiOutcome
import io.github.kanylbullen.tung.web.ai.ANTHROPIC_BROWSER_HEADER
import io.github.kanylbullen.tung.web.ai.WebAi
import io.github.kanylbullen.tung.web.ai.WebAiSettings
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.store.WebCommandStore
import io.github.kanylbullen.tung.web.store.WebExerciseResolver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.browser.window
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone

class WebAiTest {

    @AfterTest
    fun clear() {
        window.localStorage.removeItem("tung-ai")
        window.localStorage.removeItem("tung-ai-key")
    }

    private class Rig(provider: AiProviderKind, reply: AiHttpResult) {
        val store = TungStore().apply {
            seedCatalogue(listOf(CoreExercise("back_squat", mapOf("en" to "Back squat"), "legs", "barbell")), ArrayList())
        }
        val sent = ArrayList<AiHttpRequest>()
        val settings = WebAiSettings().apply { save(AiConfig(provider, null, "a-model"), "sk-secret") }
        val resolver = WebExerciseResolver(store, { mapOf("back_squat" to mapOf("en" to "Back squat")) })
        val commands = CommandExecutor(WebCommandStore(store, persist = {}, clock = { 1L }), resolver, StartedBy.WEB)
        val ai = WebAi(
            store, settings, resolver,
            catalogueKeys = { setOf("back_squat") },
            commands = commands,
            now = { 1_800_000_000_000L },
            zone = { TimeZone.UTC },
            equipment = { EquipmentFilter.ALL },
            http = { sent += it; reply },
        )
    }

    private val answer = """{"choices":[{"message":{"content":"{\"rationale\":\"Legs.\",\"exercises\":[{\"key\":\"back_squat\",\"sets\":[{\"weight_kg\":100,\"reps\":5}]}]}"},"finish_reason":"stop"}]}"""

    @Test
    fun theKeyIsAddedOnlyWhenSentAndTheAnswerBecomesAPlan() = runTest {
        val rig = Rig(AiProviderKind.OPENAI_COMPATIBLE, AiHttpResult.Ok(answer))
        val prepared = assertNotNull(rig.ai.prepare(AiRequest.NextWorkout, "en"))
        assertFalse(prepared.http.headers.values.any { "sk-secret" in it }, "what is shown has no key")
        val outcome = assertIs<AiOutcome.Proposed>(rig.ai.send(prepared))
        assertEquals("Bearer sk-secret", rig.sent.single().headers["Authorization"])
        assertEquals(prepared.http.body, rig.sent.single().body, "sent byte for byte as shown")
        val plan = assertIs<AiProposal.NextWorkout>(outcome.proposal)
        assertTrue(rig.ai.confirm(plan))
        assertEquals(1, rig.store.workouts.rows.size)
    }

    @Test
    fun anthropicIsAskedToAnswerTheBrowser() = runTest {
        val rig = Rig(AiProviderKind.ANTHROPIC, AiHttpResult.Failed(AiFailure.UNAUTHORIZED, "invalid x-api-key"))
        val prepared = assertNotNull(rig.ai.prepare(AiRequest.Plateaus, "sv"))
        assertEquals("true", prepared.http.headers[ANTHROPIC_BROWSER_HEADER])
        val failed = assertIs<AiOutcome.Failed>(rig.ai.send(prepared))
        assertEquals(AiFailure.UNAUTHORIZED, failed.failure)
        assertEquals("sk-secret", rig.sent.single().headers["x-api-key"])
    }

    @Test
    fun noModelNoRequest() {
        val settings = WebAiSettings()
        settings.save(AiConfig(model = ""), null)
        assertNull(Rig(AiProviderKind.OPENAI_COMPATIBLE, AiHttpResult.Ok("")).let { rig ->
            rig.settings.save(AiConfig(model = " "), null)
            rig.ai.prepare(AiRequest.Program, "en")
        })
    }
}
