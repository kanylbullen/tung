package io.github.kanylbullen.tung.ai

import io.github.kanylbullen.tung.core.ai.AiCall
import io.github.kanylbullen.tung.core.ai.AiPrompt
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.ai.aiHttpRequest
import io.github.kanylbullen.tung.core.ai.aiResponseText
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** [AiClient] against a MockWebServer standing in for each provider: what goes out, and how every status reads. */
class AiClientTest {

    private val server = MockWebServer()
    private val client = AiClient(aiHttpClient(timeoutMillis = 1_000))
    private val prompt = AiPrompt(system = "Answer in JSON.", user = "Suggest a program.")

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.shutdown()

    private fun base(path: String = "") = server.url(path).toString().trimEnd('/')

    private fun openAi(key: String? = "sk-test") =
        aiHttpRequest(AiCall(AiProviderKind.OPENAI_COMPATIBLE, base("/v1"), "test-model"), prompt, key)

    private fun anthropic(key: String? = "sk-ant-test") =
        aiHttpRequest(AiCall(AiProviderKind.ANTHROPIC, base(), "claude-test-model"), prompt, key)

    private fun send(request: io.github.kanylbullen.tung.core.ai.AiHttpRequest) = runBlocking { client.send(request) }

    private fun json(code: Int, body: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun openAiCompatibleCallGoesOutAsBuiltAndComesBackAsText() {
        server.enqueue(json(200, """{"choices": [{"index": 0, "message": {"role": "assistant", "content": "{\"routines\": []}"}}]}"""))
        val request = openAi()
        val result = send(request)

        assertTrue("$result", result is AiHttpResult.Ok)
        assertEquals("{\"routines\": []}", aiResponseText(AiProviderKind.OPENAI_COMPATIBLE, (result as AiHttpResult.Ok).body))
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        assertEquals(request.body, recorded.body.readUtf8())
    }

    @Test
    fun anthropicCallCarriesItsHeaders() {
        server.enqueue(json(200, """{"type": "message", "content": [{"type": "text", "text": "{\"plateaus\": []}"}], "stop_reason": "end_turn"}"""))
        val result = send(anthropic())

        assertEquals("{\"plateaus\": []}", aiResponseText(AiProviderKind.ANTHROPIC, (result as AiHttpResult.Ok).body))
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("sk-ant-test", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        assertEquals(null, recorded.getHeader("Authorization"))
    }

    @Test
    fun statusesMapToFailuresWithTheProvidersMessage() {
        server.enqueue(json(401, """{"error": {"message": "Incorrect API key provided", "type": "invalid_request_error"}}"""))
        assertEquals(AiHttpResult.Failed(AiFailure.UNAUTHORIZED, "Incorrect API key provided"), send(openAi()))

        server.enqueue(json(404, """{"type": "error", "error": {"type": "not_found_error", "message": "model: claude-nope"}}"""))
        assertEquals(AiHttpResult.Failed(AiFailure.NOT_FOUND, "model: claude-nope"), send(anthropic()))

        server.enqueue(json(429, """{"type": "error", "error": {"type": "rate_limit_error", "message": "Slow down"}}"""))
        assertEquals(AiHttpResult.Failed(AiFailure.RATE_LIMITED, "Slow down"), send(anthropic()))

        server.enqueue(json(400, """{"error": {"message": "response_format is not supported"}}"""))
        assertEquals(AiHttpResult.Failed(AiFailure.REJECTED, "response_format is not supported"), send(openAi()))

        server.enqueue(MockResponse().setResponseCode(529).setBody("overloaded"))
        assertEquals(AiHttpResult.Failed(AiFailure.SERVER, null), send(anthropic()))
    }

    @Test
    fun oneCallPerTapNoRetryNoRedirect() {
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(AiHttpResult.Failed(AiFailure.SERVER, null), send(openAi()))
        assertEquals(1, server.requestCount)

        // A redirect is not followed: the key in the header would go with it.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertEquals(AiHttpResult.Failed(AiFailure.NOT_FOUND, null), send(openAi()))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aSlowProviderTimesOutAndAGoneOneIsANetworkFailure() {
        server.enqueue(json(200, "{}").setHeadersDelay(3, TimeUnit.SECONDS))
        assertEquals(AiHttpResult.Failed(AiFailure.TIMEOUT), send(openAi()))

        val gone = openAi()
        server.shutdown()
        assertEquals(AiHttpResult.Failed(AiFailure.NETWORK), send(gone))
    }
}
