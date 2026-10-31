package io.github.kanylbullen.tung.ui.settings

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.data.EquipmentSettings
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.equipmentLabel
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.exercises.EquipmentSheet
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "My equipment" for Settings: the same DataStore value the Exercises tab and the picker read. */
class EquipmentSectionViewModel(private val settings: EquipmentSettings) : ViewModel() {
    val available: StateFlow<Set<Equipment>> =
        settings.available.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EquipmentFilter.ALL)

    fun set(value: Set<Equipment>) {
        if (value.isEmpty()) return
        viewModelScope.launch { settings.set(value) }
    }
}

/**
 * Settings' "My equipment" row: what is ticked ("Everything", up to three
 * names, or "5 of 11") and, on a tap, the same [EquipmentSheet] as the
 * Exercises tab. One call site in [SettingsScreen].
 */
@Composable
fun EquipmentSection() {
    val container = appContainer()
    val vm: EquipmentSectionViewModel = viewModel(factory = factoryOf { EquipmentSectionViewModel(container.equipmentSettings) })
    val available by vm.available.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = { open = true })
            .testTag(EQUIPMENT_SETTINGS_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.my_equipment_title),
            style = MaterialTheme.typography.titleMedium,
            color = Tung.colors.text,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        Text(equipmentSummary(available), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
    if (open) {
        EquipmentSheet(available, onChange = vm::set, onDismiss = { open = false })
    }
}

/** "Everything"; the names when three or fewer are ticked ("Bodyweight"); otherwise "5 of 11". */
@Composable
private fun equipmentSummary(available: Set<Equipment>): String = when {
    !EquipmentFilter.isNarrowed(available) -> stringResource(R.string.my_equipment_everything)
    available.size <= 3 -> Equipment.entries.filter { it in available }.map { equipmentLabel(it) }
        .joinToString(stringResource(Res.string.row_separator))
    else -> stringResource(R.string.my_equipment_some, available.size, Equipment.entries.size)
}

/** The semantics tag of Settings' "My equipment" row, for the flow tests. */
const val EQUIPMENT_SETTINGS_ROW_TAG = "settings-my-equipment"
