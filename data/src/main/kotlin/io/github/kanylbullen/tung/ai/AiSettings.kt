package io.github.kanylbullen.tung.ai

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.sync.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** The in-app assistant's provider settings. The key is not here: [hasKey] only says whether one is stored. */
data class AiConfig(
    val provider: AiProviderKind = AiProviderKind.OPENAI_COMPATIBLE,
    /** What the user typed, normalised; null for the provider's own ([AiProviderKind.defaultBaseUrl]). */
    val baseUrl: String? = null,
    val model: String = "",
    /** OpenAI-compatible only: ask for JSON mode (`response_format`). */
    val jsonMode: Boolean = true,
    val hasKey: Boolean = false,
) {
    val effectiveBaseUrl: String get() = baseUrl ?: provider.defaultBaseUrl

    /** A model is named. A key is optional: a local server (Ollama) may need none. */
    val configured: Boolean get() = model.isNotBlank()
}

/**
 * The assistant's settings in the app's `settings` DataStore (provider, base
 * URL, model, JSON mode, and whether the user has seen what is sent), and its
 * API key in [keyStore]: EncryptedSharedPreferences under a Keystore key
 * (`ai_key`, excluded from backups like `sync_token`), never in DataStore,
 * never logged, never synced.
 */
class AiSettings(private val store: DataStore<Preferences>, private val keyStore: TokenStore) {

    /** Bumped on every key change, so [config] re-reads [TokenStore] (which has no Flow of its own). */
    private val keyVersion = MutableStateFlow(0)

    val config: Flow<AiConfig> = combine(store.data, keyVersion) { prefs, _ ->
        AiConfig(
            provider = AiProviderKind.fromKey(prefs[PROVIDER]),
            baseUrl = prefs[BASE_URL],
            model = prefs[MODEL].orEmpty(),
            jsonMode = prefs[JSON_MODE] ?: true,
            hasKey = withContext(Dispatchers.IO) { !keyStore.read().isNullOrBlank() },
        )
    }

    /** True once the user has seen a request before it was sent, the first time ever. */
    val payloadShown: Flow<Boolean> = store.data.map { it[PAYLOAD_SHOWN] ?: false }

    /** The menu's "Show what is sent": every request is shown before it is sent. */
    val alwaysShowPayload: Flow<Boolean> = store.data.map { it[ALWAYS_SHOW_PAYLOAD] ?: false }

    suspend fun config(): AiConfig = config.first()

    /** Stores everything but the key; [baseUrl] already checked (`core.ai.checkAiBaseUrl`), null for the provider's own. */
    suspend fun save(provider: AiProviderKind, baseUrl: String?, model: String, jsonMode: Boolean) {
        store.edit {
            it[PROVIDER] = provider.key
            if (baseUrl == null) it.remove(BASE_URL) else it[BASE_URL] = baseUrl
            it[MODEL] = model.trim()
            it[JSON_MODE] = jsonMode
        }
    }

    /** Stores [key], or forgets it for null or blank. */
    suspend fun setKey(key: String?) {
        withContext(Dispatchers.IO) { keyStore.write(key?.trim()?.takeIf { it.isNotEmpty() }) }
        keyVersion.value++
    }

    /** The API key; only `AiService` reads it, to put it in the provider's header. */
    internal suspend fun key(): String? = withContext(Dispatchers.IO) { keyStore.read() }?.takeIf { it.isNotBlank() }

    suspend fun markPayloadShown() {
        store.edit { it[PAYLOAD_SHOWN] = true }
    }

    suspend fun setAlwaysShowPayload(show: Boolean) {
        store.edit { it[ALWAYS_SHOW_PAYLOAD] = show }
    }

    companion object {
        /** The EncryptedSharedPreferences file of the key; the app's backup rules exclude `ai_key.xml`. */
        const val KEY_FILE = "ai_key"
        const val KEY_NAME = "api_key"

        private val PROVIDER = stringPreferencesKey("ai_provider")
        private val BASE_URL = stringPreferencesKey("ai_base_url")
        private val MODEL = stringPreferencesKey("ai_model")
        private val JSON_MODE = booleanPreferencesKey("ai_json_mode")
        private val PAYLOAD_SHOWN = booleanPreferencesKey("ai_payload_shown")
        private val ALWAYS_SHOW_PAYLOAD = booleanPreferencesKey("ai_always_show_payload")
    }
}
