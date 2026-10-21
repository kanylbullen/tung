package io.github.kanylbullen.tung.core.ai

import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.core.sync.checkServerUrl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two APIs the in-app assistant speaks with the user's own key (BYOK),
 * straight from the phone to the provider, as pure functions: a request is a
 * URL, headers and a JSON body; a response is read back to the model's text.
 * The HTTP call itself is `data/`'s `AiClient`.
 */
enum class AiProviderKind(val key: String, val defaultBaseUrl: String) {
    /** `POST {base}/chat/completions`: OpenAI, and the many servers that copy it (Ollama, LM Studio, vLLM, OpenRouter …). */
    OPENAI_COMPATIBLE("openai", "https://api.openai.com/v1"),

    /** `POST {base}/v1/messages` with `x-api-key` and `anthropic-version`. */
    ANTHROPIC("anthropic", "https://api.anthropic.com");

    companion object {
        fun fromKey(key: String?): AiProviderKind = entries.firstOrNull { it.key == key } ?: OPENAI_COMPATIBLE
    }
}

/** One HTTP request, ready to send. [body] is exactly the bytes that go out, as text. */
data class AiHttpRequest(val url: String, val headers: Map<String, String>, val body: String)

/** What one call asks for: the provider, its base URL (normalised by [checkAiBaseUrl]), the model and the limits. */
data class AiCall(
    val provider: AiProviderKind,
    val baseUrl: String,
    val model: String,
    /** OpenAI-compatible only: ask for `response_format: json_object`. Some local servers reject it. */
    val jsonMode: Boolean = true,
    val maxTokens: Int = AI_MAX_TOKENS,
)

/**
 * The longest answer asked for, in tokens. A program of a few routines needs
 * far less, but Claude 5.x thinks first (adaptive thinking is on when
 * `thinking` is omitted) and the thinking counts against this limit: at 4096
 * a thinking block could use all of it and leave no text (issue #73).
 */
const val AI_MAX_TOKENS = 16000

/**
 * Anthropic model ids that take `output_config.effort` (every Claude 5.x);
 * older models such as Haiku 4.5 reject the field, so it is sent only to these.
 */
val ANTHROPIC_EFFORT_MODELS = Regex("^claude-(haiku|sonnet|opus|fable|mythos)-5")

/** The effort asked of a Claude 5.x model: the answers are short structured JSON, so little thinking is needed. */
const val ANTHROPIC_EFFORT = "low"

/** The Messages API version Tung is written against. */
const val ANTHROPIC_VERSION = "2023-06-01"

/** The header names that carry the key; a preview shows every header except these. */
val AI_SECRET_HEADERS: Set<String> = setOf("Authorization", "x-api-key")

/**
 * Checks the base URL the user typed: the same rule as a sync server
 * ([checkServerUrl]), so `https://` always and plain `http://` only to
 * localhost and private IP literals (Ollama on the home network), never a key
 * in the clear across the internet. A path is kept (`/v1`).
 */
fun checkAiBaseUrl(input: String): ServerUrlCheck = checkServerUrl(input)

/** The request for [prompt]; [apiKey] goes into the provider's header and nowhere else (none for a local server without one). */
fun aiHttpRequest(call: AiCall, prompt: AiPrompt, apiKey: String?): AiHttpRequest = when (call.provider) {
    AiProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatible.request(call, prompt, apiKey)
    AiProviderKind.ANTHROPIC -> Anthropic.request(call, prompt, apiKey)
}

/** What a successful (2xx) response holds: the model's text, or why there is none. */
sealed interface AiReply {
    /** The model's text. */
    data class Text(val text: String) : AiReply

    /** No text, and the provider says the token limit was reached (`stop_reason: max_tokens`, `finish_reason: length`): thinking used it all. */
    data object OutOfRoom : AiReply

    /** The model declined (`stop_reason: refusal`; OpenAI's `refusal` or `finish_reason: content_filter`). */
    data object Declined : AiReply

    /** No text, and no reason given. */
    data object Empty : AiReply
}

/** Reads a successful response [body]: the text, or why there is none ([AiReply]). */
fun aiReply(provider: AiProviderKind, body: String): AiReply = when (provider) {
    AiProviderKind.OPENAI_COMPATIBLE -> OpenAiCompatible.reply(body)
    AiProviderKind.ANTHROPIC -> Anthropic.reply(body)
}

/** The model's text in a successful response [body], or null when there is none. */
fun aiResponseText(provider: AiProviderKind, body: String): String? =
    (aiReply(provider, body) as? AiReply.Text)?.text

/** The provider's own error message in an error response [body] (both use `{"error": {"message": …}}`), or null. */
fun aiErrorMessage(body: String): String? {
    val root = parseObject(body) ?: return null
    val error = root["error"]
    return when (error) {
        is JsonObject -> (error["message"] as? JsonPrimitive)?.content
        is JsonPrimitive -> error.content
        else -> null
    }?.takeIf { it.isNotBlank() }
}

/** The OpenAI chat completions format. */
object OpenAiCompatible {
    fun request(call: AiCall, prompt: AiPrompt, apiKey: String?): AiHttpRequest {
        val body = buildJsonObject {
            put("model", call.model)
            put("max_tokens", call.maxTokens)
            if (call.jsonMode) put("response_format", buildJsonObject { put("type", "json_object") })
            put(
                "messages",
                buildJsonArray {
                    add(message("system", prompt.system))
                    add(message("user", prompt.user))
                },
            )
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return AiHttpRequest("${call.baseUrl.trimEnd('/')}/chat/completions", headers, WireJson.encodeToString(JsonObject.serializer(), body))
    }

    /** `choices[0].message.content`; with none, `finish_reason` (`length`, `content_filter`) or `message.refusal` says why. */
    fun reply(body: String): AiReply {
        val choice = (parseObject(body)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject ?: return AiReply.Empty
        val message = choice["message"] as? JsonObject
        val refusal = (message?.get("refusal") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!refusal.isNullOrBlank()) return AiReply.Declined
        val text = (message?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        if (text != null) return AiReply.Text(text)
        return when ((choice["finish_reason"] as? JsonPrimitive)?.content) {
            "length" -> AiReply.OutOfRoom
            "content_filter" -> AiReply.Declined
            else -> AiReply.Empty
        }
    }
}

/** The Anthropic Messages format. */
object Anthropic {
    fun request(call: AiCall, prompt: AiPrompt, apiKey: String?): AiHttpRequest {
        val body = buildJsonObject {
            put("model", call.model)
            put("max_tokens", call.maxTokens)
            // Never `thinking: disabled` (a 400 on several 5.x models); a low effort keeps the thinking short.
            if (ANTHROPIC_EFFORT_MODELS.containsMatchIn(call.model)) {
                put("output_config", buildJsonObject { put("effort", ANTHROPIC_EFFORT) })
            }
            put("system", prompt.system)
            put("messages", buildJsonArray { add(message("user", prompt.user)) })
        }
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("anthropic-version", ANTHROPIC_VERSION)
            apiKey?.takeIf { it.isNotBlank() }?.let { put("x-api-key", it) }
        }
        return AiHttpRequest("${call.baseUrl.trimEnd('/')}/v1/messages", headers, WireJson.encodeToString(JsonObject.serializer(), body))
    }

    /**
     * Every `text` block of `content`, joined; `thinking` blocks (empty text
     * on Claude 5.x) are skipped. `stop_reason: refusal` is [AiReply.Declined]
     * even with text; no text with `max_tokens` is [AiReply.OutOfRoom].
     */
    fun reply(body: String): AiReply {
        val root = parseObject(body) ?: return AiReply.Empty
        val stopReason = (root["stop_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (stopReason == "refusal") return AiReply.Declined
        val text = (root["content"] as? JsonArray).orEmpty()
            .mapNotNull { block -> (block as? JsonObject)?.takeIf { (it["type"] as? JsonPrimitive)?.content == "text" } }
            .mapNotNull { (it["text"] as? JsonPrimitive)?.content }
            .joinToString("")
            .takeIf { it.isNotBlank() }
        return when {
            text != null -> AiReply.Text(text)
            stopReason == "max_tokens" -> AiReply.OutOfRoom
            else -> AiReply.Empty
        }
    }
}

/** The body of a request pretty-printed, for "Show what is sent": the same JSON, only indented. */
fun prettyAiBody(body: String): String =
    parseObject(body)?.let { PrettyJson.encodeToString(JsonObject.serializer(), it) } ?: body

private fun message(role: String, content: String) = buildJsonObject {
    put("role", role)
    put("content", content)
}

private val WireJson = Json { encodeDefaults = true }

private val PrettyJson = Json { prettyPrint = true }

private fun parseObject(body: String): JsonObject? =
    runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()

