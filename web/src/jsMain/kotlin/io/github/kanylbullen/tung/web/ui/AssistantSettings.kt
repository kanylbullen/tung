package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.ai.checkAiBaseUrl
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.ai.AiConfig
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * Settings → Assistant, the phone's `AiSection`: the provider, its base URL
 * (empty for the provider's own; http only on the local network), the
 * model, the key (kept in this browser, never shown again), JSON mode for
 * OpenAI-compatible servers, Save and Test.
 */
@Composable
fun AssistantSettings(container: WebContainer) {
    val strings = container.strings
    val settings = container.ai.settings
    val config = settings.config.collectAsState().value
    val scope = rememberCoroutineScope()
    var provider by remember(config) { mutableStateOf(config.provider) }
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl ?: "") }
    var model by remember(config) { mutableStateOf(config.model) }
    var jsonMode by remember(config) { mutableStateOf(config.jsonMode) }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(settings.hasKey) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun save(): Boolean {
        val url = baseUrl.trim()
        val normalised = if (url.isEmpty()) {
            null
        } else {
            when (val check = checkAiBaseUrl(url)) {
                is ServerUrlCheck.Valid -> check.url
                ServerUrlCheck.Malformed -> { error = strings.errorAiUrl; return false }
                ServerUrlCheck.NeedsHttps -> { error = strings.errorAiUrlHttps; return false }
            }
        }
        if (model.isBlank()) {
            error = strings.errorModel
            return false
        }
        error = null
        settings.save(AiConfig(provider, normalised, model.trim(), jsonMode), key.takeIf { it.isNotBlank() })
        key = ""
        hasKey = settings.hasKey
        return true
    }

    H2({ classes("section") }) { Text(strings.assistant) }
    Div({ classes("setting", "column") }) {
        P({ classes("hint", "flush") }) { Text(strings.aiSettingsIntro) }
        Div({ classes("mode-choice", "flush") }) {
            listOf(
                Triple(AiProviderKind.OPENAI_COMPATIBLE, strings.providerOpenAi, strings.providerOpenAiHint),
                Triple(AiProviderKind.ANTHROPIC, strings.providerAnthropic, strings.providerAnthropicHint),
            ).forEach { (kind, title, hint) ->
                Label(attrs = { classes("mode-row") }) {
                    Input(InputType.Radio) {
                        attr("name", "ai-provider")
                        checked(kind == provider)
                        onChange { provider = kind }
                    }
                    Span {
                        Span({ classes("mode-title") }) { Text(title) }
                        Span({ classes("mode-hint") }) { Text(hint) }
                    }
                }
            }
        }
        Field(strings.baseUrl, strings.baseUrlHint) {
            Input(InputType.Url) {
                value(baseUrl)
                attr("placeholder", provider.defaultBaseUrl)
                attr("spellcheck", "false")
                onInput { baseUrl = it.value }
            }
        }
        Field(strings.model, strings.modelHint) {
            Input(InputType.Text) {
                value(model)
                attr("spellcheck", "false")
                attr("autocomplete", "off")
                onInput { model = it.value }
            }
        }
        Field(strings.apiKey, if (hasKey) strings.keySaved else strings.apiKeyHint) {
            Input(InputType.Password) {
                value(key)
                attr("autocomplete", "off")
                onInput { key = it.value }
            }
        }
        if (provider == AiProviderKind.OPENAI_COMPATIBLE) {
            Label(attrs = { classes("check") }) {
                CheckboxInput(jsonMode) { onChange { jsonMode = !jsonMode } }
                Span { Text(strings.jsonMode) }
            }
            P({ classes("hint", "flush") }) { Text(strings.jsonModeHint) }
        }
        error?.let { P({ classes("error"); attr("role", "alert") }) { Text(it) } }
        message?.let { P({ classes("hint", "accent", "flush"); attr("role", "status") }) { Text(it) } }
        Div({ classes("setting-buttons") }) {
            Button({ classes("secondary"); onClick { if (save()) message = strings.saved } }) { Text(strings.save) }
            Button({
                classes("secondary")
                if (testing) disabled()
                onClick {
                    if (save()) {
                        testing = true
                        message = null
                        scope.launch {
                            val failed = container.ai.test()
                            testing = false
                            if (failed == null) {
                                message = strings.testOk
                            } else {
                                error = listOfNotNull(strings.aiFailure(failed.failure), failed.message?.let(strings::providerSays)).joinToString(" ")
                            }
                        }
                    }
                }
            }) { Text(if (testing) strings.testing else strings.test) }
            if (hasKey) {
                Button({
                    classes("secondary")
                    onClick {
                        settings.removeKey()
                        hasKey = false
                        message = strings.keyRemoved
                    }
                }) { Text(strings.removeKey) }
            }
        }
    }
}

@Composable
private fun Field(label: String, hint: String, input: @Composable () -> Unit) {
    Label(attrs = { classes("field") }) {
        Span { Text(label) }
        input()
        Span({ classes("field-hint") }) { Text(hint) }
    }
}
