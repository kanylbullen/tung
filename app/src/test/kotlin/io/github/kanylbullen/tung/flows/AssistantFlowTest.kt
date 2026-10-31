package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * The in-app assistant as a person meets it: the provider set up in Settings
 * against a MockWebServer standing in for an OpenAI-compatible API, the row
 * at the bottom of Home, the first request shown in full before it goes, the
 * proposal, and "Save program" putting the routines on the Routines screen.
 */
class AssistantFlowTest : FlowTest() {

    private val server = MockWebServer()
    private val day = 86_400_000L

    @Before
    fun startServer() = server.start()

    @After
    fun stopServer() = server.shutdown()

    private fun answer(text: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            Json.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", text) }) }) })
                },
            ),
        )

    private fun fill(label: String, text: String) {
        val field = hasSetTextAction() and hasText(label)
        waitFor(field)
        compose.onNode(field).performScrollTo().performTextReplacement(text)
    }

    private fun scrollAndClick(text: String) {
        val node = hasText(text) and isEnabled()
        waitFor(node)
        compose.onNode(node).performScrollTo().performClick()
    }

    private fun openAssistantFromHome() {
        val row = hasText(str(R.string.ai_title))
        waitFor(row)
        compose.onNode(hasScrollAction()).performScrollToNode(row)
        compose.onNode(row).performClick()
        waitForText(str(R.string.ai_intro))
    }

    private fun waitForAlwaysShowPayload(on: Boolean) = waitUntilStored(on) { app.container.aiSettings.alwaysShowPayload.first() }

    private fun ask(label: Int) {
        val button = hasText(str(label)) and isEnabled()
        waitFor(button)
        compose.onNode(button).performClick()
    }

    @Test
    fun setUpReviewTheFirstRequestAndSaveTheProgram() {
        seedFinishedWorkout(System.currentTimeMillis() - 2 * day, bench(), weightKg = 80.0, reps = 8)
        server.enqueue(
            answer(
                """{"rationale": "Two days around the bench press you already do.", "program_name": "Bench and squat",
                    "routines": [
                      {"name": "Heavy day", "exercises": [{"key": "bench_press", "target_sets": 3, "target_reps": 5}, {"name": "Moon walk", "target_sets": 3}]},
                      {"name": "Volume day", "exercises": [{"key": "back_squat", "target_sets": 4, "target_reps": 10}]}]}""",
            ),
        )

        openSettingsGroup(R.string.settings_group_assistant)
        waitForText(str(R.string.ai_settings_intro))
        // http is fine here: the stand-in listens on localhost.
        fill(str(R.string.ai_base_url), server.url("/v1").toString())
        fill(str(R.string.ai_model), "test-model")
        fill(str(R.string.ai_key), "sk-flow-test")
        scrollAndClick(str(R.string.ai_save))
        waitForText(str(R.string.ai_saved))
        screenshot("1-settings")
        assertEquals("sk-flow-test", app.aiKeys.token)
        assertEquals(0, server.requestCount)

        leaveSettings()
        openAssistantFromHome()
        screenshot("2-assistant")
        ask(R.string.ai_ask_program)

        // The first request ever is shown before anything goes out.
        waitForText(str(R.string.ai_payload_title))
        screenshot("3-what-is-sent")
        assertEquals(0, server.requestCount)
        clickText(str(R.string.ai_payload_send))

        waitForText("Heavy day")
        waitForText(plural(R.plurals.ai_dropped, 1, 1))
        screenshot("4-proposal")
        val sent = server.takeRequest()
        assertEquals("/v1/chat/completions", sent.path)
        assertEquals("Bearer sk-flow-test", sent.getHeader("Authorization"))
        val body = sent.body.readUtf8()
        assertFalse("an @ was sent", body.contains('@'))
        assertTrue(body.contains("bench_press"))
        // "Moon walk" is in no catalogue: dropped, not shown.
        assertTrue(compose.onAllNodes(hasText("Moon walk")).fetchSemanticsNodes().isEmpty())

        scrollAndClick(str(R.string.ai_save_program))
        waitForText(str(Res.string.programs_title))
        waitForText("Heavy day")
        waitForText("Volume day")
        screenshot("5-routines")
        assertEquals(2, count("SELECT COUNT(*) FROM routine"))
        assertEquals(1, count("SELECT COUNT(*) FROM program WHERE name = 'Bench and squat'"))
        assertEquals(2, count("SELECT COUNT(*) FROM program_day"))
    }

    @Test
    fun afterTheFirstReviewATapSendsAtOnceAndTheMenuBringsTheReviewBack() {
        runBlocking {
            app.container.aiSettings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
            app.container.aiSettings.setKey("sk-flow-test")
            app.container.aiSettings.markPayloadShown()
        }
        server.enqueue(
            answer("""{"rationale": "Bench day.", "exercises": [{"key": "bench_press", "sets": [{"weight_kg": 82.5, "reps": 8}, {"weight_kg": 82.5, "reps": 8}]}]}"""),
        )

        openAssistantFromHome()

        // "Show what is sent" in the menu: the next request is shown first, and Cancel sends nothing.
        clickDescription(str(Res.string.action_more))
        clickText(str(R.string.ai_show_payload))
        waitForAlwaysShowPayload(true)
        ask(R.string.ai_ask_plateaus)
        waitForText(str(R.string.ai_payload_title))
        clickText(str(Res.string.action_cancel))
        waitGone(hasText(str(R.string.ai_payload_title)))
        assertEquals(0, server.requestCount)

        // Off again: with the first review done, a tap sends at once.
        clickDescription(str(Res.string.action_more))
        clickText(str(R.string.ai_show_payload))
        waitForAlwaysShowPayload(false)
        ask(R.string.ai_ask_next)
        waitForText(str(R.string.ai_start_workout))
        assertEquals(1, server.requestCount)
        assertTrue(compose.onAllNodes(hasText(str(R.string.ai_payload_title))).fetchSemanticsNodes().isEmpty())
        screenshot("1-next-workout")

        scrollAndClick(str(R.string.ai_start_workout))
        waitForText(str(R.string.workout_finish))
        waitForText(str(R.string.ex_bench_press))
        assertEquals(2, count("SELECT COUNT(*) FROM set_entry WHERE weightKg = 82.5 AND completed = 0"))
    }

    /**
     * Issue #75: the owner's Pixel is `en-SE-u-fw-mon-ms-metric-mu-celsius`
     * and shows the app in English, but the model was handed that tag and
     * wrote Swedish. The request names the language the app shows, by name,
     * and carries no tag the region could be read from.
     */
    @Test
    @Config(qualifiers = "en-rSE-w411dp-h891dp-xhdpi")
    fun anEnglishAppInSwedenAsksForEnglish() {
        assertEquals("English", askNextWorkoutAndReadTheLanguage())
    }

    @Test
    @Config(qualifiers = "sv-rSE-w411dp-h891dp-xhdpi")
    fun aSwedishAppAsksForSwedish() {
        assertEquals("Swedish", askNextWorkoutAndReadTheLanguage())
    }

    /** Asks "Next workout" (sent at once, the first review done) and returns the language the system message names. */
    private fun askNextWorkoutAndReadTheLanguage(): String {
        runBlocking {
            app.container.aiSettings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
            app.container.aiSettings.setKey("sk-flow-test")
            app.container.aiSettings.markPayloadShown()
        }
        server.enqueue(answer("""{"rationale": "Bench day.", "exercises": [{"key": "bench_press", "sets": [{"weight_kg": 80, "reps": 8}]}]}"""))
        openAssistantFromHome()
        ask(R.string.ai_ask_next)
        waitForText(str(R.string.ai_start_workout))
        val body = server.takeRequest().body.readUtf8()
        assertFalse("a locale tag was sent", body.contains("en-SE") || body.contains("sv-SE"))
        return checkNotNull(Regex("""Write the text fields \([^)]*\) in (\w+)\.""").find(body)) { "no language in the request" }.groupValues[1]
    }
}
