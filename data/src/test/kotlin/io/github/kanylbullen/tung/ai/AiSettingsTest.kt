package io.github.kanylbullen.tung.ai

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.sync.MemoryTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The assistant's settings: everything but the key in DataStore, the key only in its [MemoryTokenStore] stand-in. */
class AiSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val file by lazy { File(tmp.root, "settings.preferences_pb") }
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { file } }
    private val keys = MemoryTokenStore()
    private val settings by lazy { AiSettings(store, keys) }

    @After
    fun stop() = scope.cancel()

    @Test
    fun nothingIsSetUpAtFirst() = runBlocking {
        val config = settings.config()
        assertFalse(config.configured)
        assertFalse(config.hasKey)
        assertEquals(AiProviderKind.OPENAI_COMPATIBLE, config.provider)
        assertEquals("https://api.openai.com/v1", config.effectiveBaseUrl)
        assertFalse(settings.payloadShown.first())
        assertFalse(settings.alwaysShowPayload.first())
    }

    @Test
    fun theKeyGoesToTheKeyStoreAndNeverToDataStore() = runBlocking {
        settings.save(AiProviderKind.ANTHROPIC, baseUrl = null, model = " claude-test-model ", jsonMode = false)
        settings.setKey("  sk-ant-very-secret-key  ")

        val config = settings.config()
        assertEquals(AiProviderKind.ANTHROPIC, config.provider)
        assertEquals("https://api.anthropic.com", config.effectiveBaseUrl)
        assertEquals("claude-test-model", config.model)
        assertTrue(config.configured)
        assertTrue(config.hasKey)
        assertEquals("sk-ant-very-secret-key", keys.token)
        assertEquals("sk-ant-very-secret-key", settings.key())

        val written = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue(written.contains("claude-test-model"))
        assertFalse("the key is in DataStore", written.contains("very-secret"))

        settings.setKey(null)
        assertNull(keys.token)
        assertFalse(settings.config().hasKey)
        settings.setKey("   ")
        assertNull(keys.token)
    }

    @Test
    fun aBaseUrlIsKeptUntilClearedAndThePayloadFlagsAreRemembered() = runBlocking {
        settings.save(AiProviderKind.OPENAI_COMPATIBLE, "http://192.168.1.20:11434/v1", "llama3.1", jsonMode = true)
        assertEquals("http://192.168.1.20:11434/v1", settings.config().effectiveBaseUrl)
        settings.save(AiProviderKind.OPENAI_COMPATIBLE, null, "llama3.1", jsonMode = true)
        assertEquals("https://api.openai.com/v1", settings.config().effectiveBaseUrl)

        settings.markPayloadShown()
        settings.setAlwaysShowPayload(true)
        assertTrue(settings.payloadShown.first())
        assertTrue(settings.alwaysShowPayload.first())
    }
}
