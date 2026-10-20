package io.github.kanylbullen.tung.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ai.AiService
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import io.github.kanylbullen.tung.core.ai.checkAiBaseUrl
import io.github.kanylbullen.tung.core.sync.ServerUrlCheck
import io.github.kanylbullen.tung.ui.ai.aiFailureText
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The Assistant section's form. [key] is what is being typed; a stored key is never read back into it. */
data class AiForm(
    val loaded: Boolean = false,
    val provider: AiProviderKind = AiProviderKind.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val model: String = "",
    val key: String = "",
    val jsonMode: Boolean = true,
    val hasKey: Boolean = false,
    val busy: Boolean = false,
    /** The last result line: saved, the test passed, or what is wrong. */
    @StringRes val message: Int? = null,
    val messageIsError: Boolean = false,
    /** The provider's own words after a failed test. */
    val detail: String? = null,
)

/**
 * Settings, section "Assistant" (CLAUDE.md, "In-app AI (BYOK)"): the provider,
 * its base URL (https, or http on the local network), the model and the key.
 * "Test" saves and sends one request for one token, with no training data.
 */
class AiSettingsViewModel(private val settings: AiSettings, private val ai: AiService) : ViewModel() {

    private val form = MutableStateFlow(AiForm())
    val state: StateFlow<AiForm> = form.asStateFlow()

    init {
        viewModelScope.launch {
            val config = settings.config.first()
            form.value = form.value.copy(
                loaded = true,
                provider = config.provider,
                baseUrl = config.baseUrl.orEmpty(),
                model = config.model,
                jsonMode = config.jsonMode,
                hasKey = config.hasKey,
            )
        }
    }

    private fun edit(change: AiForm.() -> AiForm) {
        form.value = form.value.change().copy(message = null, detail = null)
    }

    fun onProvider(provider: AiProviderKind) = edit { copy(provider = provider) }
    fun onBaseUrl(value: String) = edit { copy(baseUrl = value) }
    fun onModel(value: String) = edit { copy(model = value) }
    fun onKey(value: String) = edit { copy(key = value) }
    fun onJsonMode(value: Boolean) = edit { copy(jsonMode = value) }

    fun removeKey() {
        viewModelScope.launch {
            settings.setKey(null)
            form.value = form.value.copy(hasKey = false, key = "", message = R.string.ai_key_removed, messageIsError = false)
        }
    }

    fun save() = submit(test = false)

    fun test() = submit(test = true)

    private fun submit(test: Boolean) {
        val f = form.value
        if (f.busy) return
        val baseUrl = when (val check = f.baseUrl.trim().takeIf { it.isNotEmpty() }?.let(::checkAiBaseUrl)) {
            null -> null
            is ServerUrlCheck.Valid -> check.url
            ServerUrlCheck.NeedsHttps -> return error(R.string.ai_error_url_https)
            ServerUrlCheck.Malformed -> return error(R.string.ai_error_url)
        }
        if (f.model.isBlank()) return error(R.string.ai_error_model)
        form.value = f.copy(busy = true, message = null, detail = null)
        viewModelScope.launch {
            settings.save(f.provider, baseUrl, f.model, f.jsonMode)
            if (f.key.isNotBlank()) settings.setKey(f.key)
            val hasKey = settings.config().hasKey
            val saved = form.value.copy(busy = false, key = "", hasKey = hasKey, baseUrl = baseUrl.orEmpty(), model = f.model.trim())
            if (!test) {
                form.value = saved.copy(message = R.string.ai_saved, messageIsError = false)
                return@launch
            }
            form.value = saved.copy(busy = true)
            val failure = ai.test()
            form.value = if (failure == null) {
                saved.copy(message = R.string.ai_test_ok, messageIsError = false)
            } else {
                saved.copy(message = aiFailureText(failure.failure), messageIsError = true, detail = failure.message)
            }
        }
    }

    private fun error(@StringRes message: Int) {
        form.value = form.value.copy(message = message, messageIsError = true, detail = null)
    }
}

/** Settings' "Assistant" section; its items go into the settings column. */
@Composable
fun AiSection() {
    val container = appContainer()
    val vm: AiSettingsViewModel = viewModel(factory = factoryOf { AiSettingsViewModel(container.aiSettings, container.ai) })
    val form by vm.state.collectAsStateWithLifecycle()

    Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
    Text(stringResource(R.string.ai_settings_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    if (!form.loaded) return

    Column(Modifier.selectableGroup()) {
        ProviderOption(R.string.ai_provider_openai, R.string.ai_provider_openai_hint, form.provider == AiProviderKind.OPENAI_COMPATIBLE) {
            vm.onProvider(AiProviderKind.OPENAI_COMPATIBLE)
        }
        ProviderOption(R.string.ai_provider_anthropic, R.string.ai_provider_anthropic_hint, form.provider == AiProviderKind.ANTHROPIC) {
            vm.onProvider(AiProviderKind.ANTHROPIC)
        }
    }
    OutlinedTextField(
        value = form.baseUrl,
        onValueChange = vm::onBaseUrl,
        label = { Text(stringResource(R.string.ai_base_url)) },
        placeholder = { Text(form.provider.defaultBaseUrl) },
        supportingText = { Text(stringResource(R.string.ai_base_url_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.model,
        onValueChange = vm::onModel,
        label = { Text(stringResource(R.string.ai_model)) },
        placeholder = { Text(stringResource(R.string.ai_model_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.key,
        onValueChange = vm::onKey,
        label = { Text(stringResource(R.string.ai_key)) },
        supportingText = { Text(stringResource(if (form.hasKey) R.string.ai_key_saved else R.string.ai_key_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        shape = TungCorner,
        colors = tungFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    if (form.provider == AiProviderKind.OPENAI_COMPATIBLE) {
        JsonModeRow(form.jsonMode, vm::onJsonMode)
    }
    form.message?.let {
        Column {
            Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = if (form.messageIsError) Tung.colors.accentText else Tung.colors.text)
            form.detail?.let { detail ->
                Text(stringResource(R.string.ai_error_provider_says, detail), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
            }
        }
    }
    SecondaryButton(text = stringResource(R.string.ai_save), onClick = vm::save, enabled = !form.busy)
    SecondaryButton(text = stringResource(if (form.busy) R.string.ai_testing else R.string.ai_test), onClick = vm::test, enabled = !form.busy)
    if (form.hasKey) {
        SecondaryButton(text = stringResource(R.string.ai_key_remove), onClick = vm::removeKey, enabled = !form.busy)
    }
}

@Composable
private fun ProviderOption(@StringRes label: Int, @StringRes hint: Int, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Tung.colors.accent, unselectedColor = Tung.colors.muted),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
        }
    }
}

@Composable
private fun JsonModeRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.ai_json_mode), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
            Text(stringResource(R.string.ai_json_mode_hint), style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tung.colors.onAccent,
                checkedTrackColor = Tung.colors.accent,
                checkedBorderColor = Tung.colors.accent,
                uncheckedThumbColor = Tung.colors.muted,
                uncheckedTrackColor = Tung.colors.background,
                uncheckedBorderColor = Tung.colors.muted,
            ),
        )
    }
}
