package io.github.kanylbullen.tung.ui.exercises

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.catalog.EquipmentFilter
import io.github.kanylbullen.tung.ui.common.equipmentLabel
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** "Equipment: All", or "Equipment: 3" when narrowed to three; the chip that opens [EquipmentSheet]. */
@Composable
fun equipmentChipLabel(available: Set<Equipment>): String =
    if (EquipmentFilter.isNarrowed(available)) {
        stringResource(R.string.my_equipment_chip_count, available.size)
    } else {
        stringResource(R.string.my_equipment_chip_all)
    }

/**
 * The compact control under the Exercises tab's group chips and under the
 * picker's search: one chip naming how much of "My equipment" is ticked. It
 * reads as on (the accent's neighbour, chalk) while narrowed, so a short list
 * explains itself.
 */
@Composable
fun EquipmentChip(available: Set<Equipment>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val narrowed = EquipmentFilter.isNarrowed(available)
    FilterChip(
        selected = narrowed,
        onClick = onClick,
        label = { Text(equipmentChipLabel(available)) },
        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        modifier = modifier.testTag(EQUIPMENT_CHIP_TAG),
        shape = TungCorner,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Tung.colors.raised,
            labelColor = Tung.colors.text,
            iconColor = Tung.colors.muted,
            selectedContainerColor = Tung.colors.text,
            selectedLabelColor = Tung.colors.background,
            selectedTrailingIconColor = Tung.colors.background,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = narrowed,
            borderColor = Tung.colors.muted,
            selectedBorderColor = Tung.colors.text,
        ),
    )
}

/**
 * "My equipment": the two shortcuts ("Everything", "Bodyweight only") and a
 * checkbox per [Equipment]. Every change goes to [onChange] at once and is
 * saved there; there is nothing to confirm. The last ticked box cannot be
 * unticked, so the lists never go empty because of the filter alone. Shared
 * by the Exercises tab, the picker and Settings.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EquipmentSheet(available: Set<Equipment>, onChange: (Set<Equipment>) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TungCorner,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
                .verticalScroll(rememberScrollState())
                .testTag(EQUIPMENT_SHEET_TAG),
        ) {
            Text(
                stringResource(R.string.my_equipment_title),
                style = MaterialTheme.typography.titleMedium,
                color = Tung.colors.text,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(
                stringResource(R.string.my_equipment_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = Tung.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Choice(
                    stringResource(R.string.my_equipment_everything),
                    selected = !EquipmentFilter.isNarrowed(available),
                    Modifier.testTag(EQUIPMENT_EVERYTHING_TAG),
                ) { onChange(EquipmentFilter.ALL) }
                Choice(
                    stringResource(R.string.my_equipment_bodyweight_only),
                    selected = available == EquipmentFilter.BODYWEIGHT_ONLY,
                    Modifier.testTag(EQUIPMENT_BODYWEIGHT_ONLY_TAG),
                ) { onChange(EquipmentFilter.BODYWEIGHT_ONLY) }
            }
            Equipment.entries.forEach { item ->
                val checked = item in available
                val last = checked && available.size == 1
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .toggleable(
                            value = checked,
                            enabled = !last,
                            role = Role.Checkbox,
                            onValueChange = { on -> onChange(if (on) available + item else available - item) },
                        )
                        .padding(horizontal = 20.dp)
                        .testTag(equipmentRowTag(item)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(equipmentLabel(item), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text, modifier = Modifier.weight(1f))
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null,
                        enabled = !last,
                        colors = CheckboxDefaults.colors(
                            checkedColor = Tung.colors.accent,
                            checkmarkColor = Tung.colors.onAccent,
                            uncheckedColor = Tung.colors.muted,
                            disabledCheckedColor = Tung.colors.muted,
                        ),
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.my_equipment_done), color = Tung.colors.accentText)
            }
        }
    }
}

/** The semantics tags the flow tests find the chip, the sheet, its shortcuts and its rows by. */
const val EQUIPMENT_CHIP_TAG = "equipment-chip"
const val EQUIPMENT_SHEET_TAG = "equipment-sheet"
const val EQUIPMENT_EVERYTHING_TAG = "equipment-everything"
const val EQUIPMENT_BODYWEIGHT_ONLY_TAG = "equipment-bodyweight-only"

fun equipmentRowTag(equipment: Equipment): String = "equipment-${equipment.key}"
