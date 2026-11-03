package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The two wire formats' requests, on every target; reading recorded responses is `AiWireFixturesTest` in jvmTest. */
class AiWireTest {

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
            assertEquals("low", body.getValue("output_config").jsonObject.getValue("effort").jsonPrimitive.content, model)
            assertEquals(16000, body.getValue("max_tokens").jsonPrimitive.int, model)
            assertFalse("thinking" in body, model)
        }
        for (model in listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3-5-haiku-latest", "my-claude-haiku-5-5")) {
            assertFalse("output_config" in anthropicBody(model), model)
        }
        // The OpenAI-compatible format never carries it, even for a Claude id behind a proxy.
        assertFalse("output_config" in body(aiHttpRequest(AiCall(AiProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "claude-haiku-5-5"), prompt, "k")))
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
