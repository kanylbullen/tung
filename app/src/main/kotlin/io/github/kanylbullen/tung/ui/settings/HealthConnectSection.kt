package io.github.kanylbullen.tung.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.health.HealthAccess
import io.github.kanylbullen.tung.health.HealthAvailability
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.theme.Tung

/**
 * Settings' "Health Connect" section (CLAUDE.md, "Health Connect"): two
 * opt-ins, each asking for its permissions through Health Connect's own
 * sheet. Where Health Connect is not available the switches are hidden and a
 * hint says why. One call site, at the bottom of [SettingsScreen].
 */
@Composable
fun HealthConnectSection() {
    val container = appContainer()
    val gateway = container.healthConnect
    val vm: HealthConnectViewModel = viewModel(factory = factoryOf { HealthConnectViewModel(gateway, container.healthConnectSettings) })
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Text(stringResource(R.string.health_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
    if (gateway == null || state.availability != HealthAvailability.AVAILABLE) {
        Text(
            stringResource(if (state.availability == HealthAvailability.NEEDS_UPDATE) R.string.health_needs_update else R.string.health_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = Tung.colors.muted,
        )
        return
    }

    var asking by rememberSaveable { mutableStateOf<HealthAccess?>(null) }
    val sheet = rememberLauncherForActivityResult(gateway.permissionRequest()) { granted ->
        asking?.let { vm.onPermissionResult(it, granted) }
        asking = null
    }
    val toggle = { access: HealthAccess, on: Boolean ->
        if (vm.onToggle(access, on)) {
            asking = access
            sheet.launch(vm.permissions(access))
        }
    }

    HealthSwitch(stringResource(R.string.health_write), stringResource(R.string.health_write_hint), state.write) { toggle(HealthAccess.WRITE, it) }
    HealthSwitch(stringResource(R.string.health_read), stringResource(R.string.health_read_hint), state.read) { toggle(HealthAccess.READ, it) }
    if (state.denied != null) {
        Text(stringResource(R.string.health_denied), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.text)
    }
}

@Composable
private fun HealthSwitch(label: String, hint: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text, modifier = Modifier.weight(1f).padding(end = 16.dp))
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
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
}
