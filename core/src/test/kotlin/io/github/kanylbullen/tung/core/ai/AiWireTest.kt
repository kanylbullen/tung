package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two wire formats against recorded responses (`src/test/resources/ai/`). */
class AiWireTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/ai/$name")) { "missing fixture $name" }.readText()

    private val prompt = AiPrompt(system = "Answer in JSON.", user = "Suggest a program.\n\nMy training data (JSON):\n{}")

    private fun body(request: AiHttpRequest): JsonObject = Json.parseToJsonElement(request.body).jsonObject

    @Test
    fun openAiCompatibleRequest() {
        val request = aiHttpRequest(AiCall(AiProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.20:11434/v1/", "llama3.1"), prompt, "sk-test")
        assertEquals("http://192.168.1.20:11434/v1/chat/completions", request.url)
        assertEquals("Bearer sk-test", request.headers["Authorization"])
        assertEquals("application/json", request.headers["Content-Type"])
        val body = body(request)
        assertEquals("llama3.1", body.getValue("model").jsonPrimitive.content)
        assertEquals(AI_MAX_TOKENS, body.getValue("max_tokens").jsonPrimitive.int)
        assertEquals("json_object", body.getValue("response_format").jsonObject.getValue("type").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertEquals(prompt.user, messages[1].getValue("content").jsonPrimitive.content)

        // Without JSON mode and without a key (a local server): neither is sent.
        val plain = aiHttpRequest(AiCall(AiProviderKind.OPENAI_COMPATIBLE, "http://localhost:11434/v1", "llama3.1", jsonMode = false), prompt, null)
        assertFalse("response_format" in body(plain))
        assertFalse("Authorization" in plain.headers)
    }

    @Test
    fun anthropicRequest() {
        val request = aiHttpRequest(AiCall(AiProviderKind.ANTHROPIC, AiProviderKind.ANTHROPIC.defaultBaseUrl, "claude-test-model"), prompt, "sk-ant-test")
        assertEquals("https://api.anthropic.com/v1/messages", request.url)
        assertEquals("sk-ant-test", request.headers["x-api-key"])
        assertEquals(ANTHROPIC_VERSION, request.headers["anthropic-version"])
        assertNull(request.headers["Authorization"])
        val body = body(request)
        assertEquals("claude-test-model", body.getValue("model").jsonPrimitive.content)
        assertEquals(prompt.system, body.getValue("system").jsonPrimitive.content)
        val messages = body.getValue("messages").jsonArray.map { it.jsonObject }
        assertEquals(listOf("user"), messages.map { it.getValue("role").jsonPrimitive.content })
        assertFalse("response_format" in body)
        assertEquals(AI_MAX_TOKENS, body.getValue("max_tokens").jsonPrimitive.int)
        // Not a Claude 5.x id: no effort, and never a `thinking` field.
        assertFalse("output_config" in body)
        assertFalse("thinking" in body)
    }

    @Test
    fun claude5ModelsAreAskedForLowEffortAndOlderOnesAreNot() {
        fun anthropicBody(model: String) = body(aiHttpRequest(AiCall(AiProviderKind.ANTHROPIC, AiProviderKind.ANTHROPIC.defaultBaseUrl, model), prompt, "k"))

        for (model in listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1", "claude-mythos-5")) {
            val body = anthropicBody(model)
            assertEquals(model, "low", body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content)
            assertEquals(model, 16000, body.getValue("max_tokens").jsonPrimitive.int)
            assertFalse(model, "thinking" in body)
        }
        for (model in listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3-5-haiku-latest", "my-claude-haiku-5-5")) {
            assertFalse(model, "output_config" in anthropicBody(model))
        }
        // The OpenAI-compatible format never carries it, even for a Claude id behind a proxy.
        assertFalse("output_config" in body(aiHttpRequest(AiCall(AiProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "claude-haiku-5-5"), prompt, "k")))
    }

    @Test
    fun aThinkingOnlyAnswerCutAtMaxTokensIsOutOfRoom() {
        assertEquals(AiReply.OutOfRoom, aiReply(AiProviderKind.ANTHROPIC, fixture("anthropic-thinking-max-tokens.json")))
        assertNull(aiResponseText(AiProviderKind.ANTHROPIC, fixture("anthropic-thinking-max-tokens.json")))
    }

    @Test
    fun thinkingBeforeTheTextIsSkippedAndTheTextRead() {
        val reply = aiReply(AiProviderKind.ANTHROPIC, fixture("anthropic-thinking-text.json"))
        val text = (reply as AiReply.Text).text
        assertTrue(text.startsWith("{\"rationale\":\"Squat day"))
        val workout = parseAiAnswer(AiRequest.NextWorkout, text, setOf("back_squat"))
        assertEquals(1, ((workout as AiParse.Parsed).draft as AiDraft.NextWorkout).plan.exercises.size)
    }

    @Test
    fun aRefusalIsDeclined() {
        assertEquals(AiReply.Declined, aiReply(AiProviderKind.ANTHROPIC, fixture("anthropic-refusal.json")))
    }

    @Test
    fun anOpenAiAnswerCutAtTheLengthWithNoContentIsOutOfRoom() {
        assertEquals(AiReply.OutOfRoom, aiReply(AiProviderKind.OPENAI_COMPATIBLE, fixture("openai-length.json")))
        // No text and no reason is still plain nothing.
        assertEquals(AiReply.Empty, aiReply(AiProviderKind.OPENAI_COMPATIBLE, "{\"choices\": []}"))
        assertEquals(AiReply.Empty, aiReply(AiProviderKind.ANTHROPIC, "{\"content\": [], \"stop_reason\": \"end_turn\"}"))
    }

    @Test
    fun responsesAreReadBackToTheModelsText() {
        val openAi = aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, fixture("openai-chat-completion.json"))
        assertTrue(openAi.orEmpty().startsWith("{\"rationale\":\"Two upper"))
        val program = parseAiAnswer(AiRequest.Program, openAi.orEmpty(), setOf("bench_press", "overhead_press", "back_squat"))
        assertEquals(2, ((program as AiParse.Parsed).draft as AiDraft.Program).routines.size)

        val anthropic = aiResponseText(AiProviderKind.ANTHROPIC, fixture("anthropic-message.json"))
        val workout = parseAiAnswer(AiRequest.NextWorkout, anthropic.orEmpty(), setOf("back_squat"))
        assertEquals(1, ((workout as AiParse.Parsed).draft as AiDraft.NextWorkout).plan.exercises.size)

        // The other provider's shape, or no text, reads as nothing.
        assertNull(aiResponseText(AiProviderKind.ANTHROPIC, fixture("openai-chat-completion.json")))
        assertNull(aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, "{\"choices\": []}"))
        assertNull(aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, "not json"))
    }

    @Test
    fun errorMessagesAreReadFromBothProviders() {
        assertTrue(aiErrorMessage(fixture("openai-error.json")).orEmpty().startsWith("Incorrect API key"))
        assertEquals("invalid x-api-key", aiErrorMessage(fixture("anthropic-error.json")))
        assertNull(aiErrorMessage("<html>Bad gateway</html>"))
    }

    @Test
    fun theBaseUrlNeedsHttpsExceptOnTheLocalNetwork() {
        assertEquals(ServerUrlCheck.Valid("https://api.openai.com/v1"), checkAiBaseUrl("https://API.openai.com/v1/"))
        assertEquals(ServerUrlCheck.Valid("http://192.168.1.20:11434/v1"), checkAiBaseUrl("http://192.168.1.20:11434/v1"))
        assertEquals(ServerUrlCheck.Valid("http://localhost:11434"), checkAiBaseUrl("http://localhost:11434"))
        assertEquals(ServerUrlCheck.NeedsHttps, checkAiBaseUrl("http://api.openai.com/v1"))
        assertEquals(ServerUrlCheck.Malformed, checkAiBaseUrl("api.openai.com"))
        assertEquals(ServerUrlCheck.Malformed, checkAiBaseUrl("https://user:pw@llm.example.org"))
    }

    @Test
    fun thePreviewIsTheSameBodyIndented() {
        val request = aiHttpRequest(AiCall(AiProviderKind.ANTHROPIC, "https://api.anthropic.com", "m"), prompt, "k")
        val pretty = prettyAiBody(request.body)
        assertTrue(pretty.contains("\n    "))
        assertEquals(Json.parseToJsonElement(request.body), Json.parseToJsonElement(pretty))
    }
}
