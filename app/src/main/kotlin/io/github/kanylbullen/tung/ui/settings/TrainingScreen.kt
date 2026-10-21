package io.github.kanylbullen.tung.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.MAX_REST_SECONDS
import io.github.kanylbullen.tung.core.stepRestSeconds
import io.github.kanylbullen.tung.timer.RestTimerController
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings' Training: the default rest (`rest_default_seconds`, through [RestTimerController]). */
class TrainingSettingsViewModel(private val restTimer: RestTimerController) : ViewModel() {
    private val rest = MutableStateFlow<Int?>(null)

    /**
     * Null until read. The screen's own value, written through at every
     * press: quick presses each count, without waiting for DataStore.
     */
    val restDefaultSeconds: StateFlow<Int?> = rest.asStateFlow()

    init {
        viewModelScope.launch { rest.value = restTimer.defaultSeconds.first() }
    }

    /** −/+ 30 s, from off (0) to 5:00, like an exercise's own rest. */
    fun stepRest(steps: Int) {
        val next = stepRestSeconds(rest.value ?: return, steps)
        rest.value = next
        viewModelScope.launch { restTimer.setDefaultSeconds(next) }
    }
}

/** "1:30", or "Off" for 0. */
@Composable
internal fun restText(seconds: Int): String =
    if (seconds <= 0) stringResource(R.string.rest_off) else clockText(seconds * 1000L)

/**
 * Settings' Training: the default rest between sets for exercises without
 * their own (0–300 s in 30 s steps, like the exercise's rest sheet), the
 * unit, and the slot for "My equipment".
 */
@Composable
fun TrainingScreen(onBack: () -> Unit) {
    val container = appContainer()
    val vm: TrainingSettingsViewModel = viewModel(factory = factoryOf { TrainingSettingsViewModel(container.restTimer) })
    val rest by vm.restDefaultSeconds.collectAsStateWithLifecycle()

    SettingsPage(title = stringResource(R.string.settings_group_training), onBack = onBack) {
        Text(stringResource(R.string.settings_rest_default), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
        Text(stringResource(R.string.settings_rest_default_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        rest?.let { seconds ->
            Text(restText(seconds), style = Tung.numbers.stat, color = Tung.colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                val shorter = stringResource(R.string.rest_shorter)
                val longer = stringResource(R.string.rest_longer)
                SecondaryButton(
                    text = stringResource(R.string.rest_minus),
                    onClick = { vm.stepRest(-1) },
                    enabled = seconds > 0,
                    modifier = Modifier.weight(1f).semantics { contentDescription = shorter },
                )
                SecondaryButton(
                    text = stringResource(R.string.rest_plus),
                    onClick = { vm.stepRest(1) },
                    enabled = seconds < MAX_REST_SECONDS,
                    modifier = Modifier.weight(1f).semantics { contentDescription = longer },
                )
            }
        }
        MyEquipmentSlot()
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
            Text(stringResource(R.string.settings_units_kg), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        }
    }
}

/**
 * Where "My equipment" (issue #65, its `EquipmentSettings` in `data/`) goes:
 * a row in Training, under the default rest. Empty until that lands; the
 * merge replaces this body with #65's row (or its call).
 */
@Composable
private fun ColumnScope.MyEquipmentSlot() = Unit
