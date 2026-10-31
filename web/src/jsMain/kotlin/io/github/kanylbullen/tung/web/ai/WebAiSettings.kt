package io.github.kanylbullen.tung.web.ai

import io.github.kanylbullen.tung.core.ai.AiProviderKind
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The assistant's settings, the phone's `AiConfig`. */
data class AiConfig(
    val provider: AiProviderKind = AiProviderKind.OPENAI_COMPATIBLE,
    /** What the user typed, normalised; null for the provider's own. */
    val baseUrl: String? = null,
    val model: String = "",
    val jsonMode: Boolean = true,
) {
    val effectiveBaseUrl: String get() = baseUrl ?: provider.defaultBaseUrl

    /** A model is named. A key is optional: a local server may need none. */
    val configured: Boolean get() = model.isNotBlank()
}

@Serializable
private data class StoredAi(
    val provider: String = AiProviderKind.OPENAI_COMPATIBLE.key,
    val baseUrl: String? = null,
    val model: String = "",
    val jsonMode: Boolean = true,
    val firstShown: Boolean = false,
    val alwaysShow: Boolean = false,
)

/**
 * The assistant's settings and key in this browser's localStorage, never
 * synced and never sent to tung-server. A browser has no Keystore: the key
 * is as safe as the token beside it (CLAUDE.md, "Web", "Account"), readable
 * by any script of this origin; the settings say so.
 */
class WebAiSettings {
    private val json = Json { ignoreUnknownKeys = true }
    private var stored = read()

    private val _config = MutableStateFlow(stored.toConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private val _alwaysShow = MutableStateFlow(stored.alwaysShow)

    /** "Show what is sent": every request is shown before it goes. */
    val alwaysShow: StateFlow<Boolean> = _alwaysShow.asStateFlow()

    /** Whether the very first request was shown; until then "What is sent" comes first whatever [alwaysShow] says. */
    val firstShown: Boolean get() = stored.firstShown

    fun key(): String? = get(KEY_KEY)?.takeIf { it.isNotEmpty() }

    val hasKey: Boolean get() = key() != null

    fun save(config: AiConfig, newKey: String?) {
        stored = stored.copy(provider = config.provider.key, baseUrl = config.baseUrl, model = config.model.trim(), jsonMode = config.jsonMode)
        write()
        if (!newKey.isNullOrBlank()) set(KEY_KEY, newKey.trim())
        _config.value = stored.toConfig()
    }

    fun removeKey() = remove(KEY_KEY)

    fun setAlwaysShow(on: Boolean) {
        stored = stored.copy(alwaysShow = on)
        write()
        _alwaysShow.value = on
    }

    fun markFirstShown() {
        if (stored.firstShown) return
        stored = stored.copy(firstShown = true)
        write()
    }

    private fun StoredAi.toConfig() = AiConfig(AiProviderKind.fromKey(provider), baseUrl, model, jsonMode)

    private fun read(): StoredAi = get(KEY_SETTINGS)?.let { runCatching { json.decodeFromString(StoredAi.serializer(), it) }.getOrNull() } ?: StoredAi()

    private fun write() = set(KEY_SETTINGS, json.encodeToString(StoredAi.serializer(), stored))

    private fun get(key: String): String? = try {
        window.localStorage.getItem(key)
    } catch (e: Throwable) {
        null
    }

    private fun set(key: String, value: String) {
        try {
            window.localStorage.setItem(key, value)
        } catch (e: Throwable) {
            // A private window: kept for this page only, through the state above.
        }
    }

    private fun remove(key: String) {
        try {
            window.localStorage.removeItem(key)
        } catch (e: Throwable) {
            // Nothing stored.
        }
    }

    private companion object {
        const val KEY_SETTINGS = "tung-ai"
        const val KEY_KEY = "tung-ai-key"
    }
}
