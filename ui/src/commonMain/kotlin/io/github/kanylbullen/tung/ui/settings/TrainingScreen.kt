package io.github.kanylbullen.tung.ui.settings

import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.kanylbullen.tung.ui.ExactAlarms
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.core.MAX_REST_SECONDS
import io.github.kanylbullen.tung.core.stepRestSeconds
import io.github.kanylbullen.tung.timer.RestTimerStore
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Settings' Training: the default rest (`rest_default_seconds`, through [RestTimerStore]). */
class TrainingSettingsViewModel(private val restTimer: RestTimerStore) : ViewModel() {
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
    if (seconds <= 0) stringResource(Res.string.rest_off) else clockText(seconds * 1000L)

/**
 * Settings' Training: the default rest between sets for exercises without
 * their own (0–300 s in 30 s steps, like the exercise's rest sheet), the
 * unit, and "My equipment" (issue #65).
 */
@Composable
fun TrainingScreen(vm: TrainingSettingsViewModel, equipment: EquipmentSectionViewModel, exactAlarms: ExactAlarms? = null, onBack: () -> Unit) {
    val rest by vm.restDefaultSeconds.collectAsStateWithLifecycle()
    // Read again when the user comes back from the system's page.
    var alarmsAllowed by remember { mutableStateOf(exactAlarms?.allowed() ?: true) }
    LifecycleResumeEffect(exactAlarms) {
        alarmsAllowed = exactAlarms?.allowed() ?: true
        onPauseOrDispose { }
    }

    SettingsPage(title = stringResource(Res.string.settings_group_training), onBack = onBack) {
        Text(stringResource(Res.string.settings_rest_default), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
        Text(stringResource(Res.string.settings_rest_default_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
        rest?.let { seconds ->
            Text(restText(seconds), style = Tung.numbers.stat, color = Tung.colors.text)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                val shorter = stringResource(Res.string.rest_shorter)
                val longer = stringResource(Res.string.rest_longer)
                SecondaryButton(
                    text = stringResource(Res.string.rest_minus),
                    onClick = { vm.stepRest(-1) },
                    enabled = seconds > 0,
                    modifier = Modifier.weight(1f).semantics { contentDescription = shorter },
                )
                SecondaryButton(
                    text = stringResource(Res.string.rest_plus),
                    onClick = { vm.stepRest(1) },
                    enabled = seconds < MAX_REST_SECONDS,
                    modifier = Modifier.weight(1f).semantics { contentDescription = longer },
                )
            }
        }
        if (exactAlarms != null && !alarmsAllowed) {
            // Issue #215: without it the rest's alarm may come minutes late with the app in the background.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.settings_exact_alarms_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
                Text(stringResource(Res.string.settings_exact_alarms_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                SecondaryButton(
                    text = stringResource(Res.string.settings_exact_alarms_allow),
                    onClick = exactAlarms::openSettings,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        EquipmentSection(equipment)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.settings_units), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
            Text(stringResource(Res.string.settings_units_kg), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        }
    }
}
