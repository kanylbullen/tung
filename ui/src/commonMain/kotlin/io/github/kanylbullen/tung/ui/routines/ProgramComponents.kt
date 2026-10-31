package io.github.kanylbullen.tung.ui.routines

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.core.ISO_WEEKDAYS
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.ui.common.weekdayName
import io.github.kanylbullen.tung.ui.theme.Tung

/** "Mon" for ISO weekday 1, in the phone's language. */
fun weekdayShort(isoWeekday: Int): String = weekdayName(isoWeekday, short = true)

/** "Monday" for ISO weekday 1, in the phone's language. */
fun weekdayFull(isoWeekday: Int): String = weekdayName(isoWeekday, short = false)

/** "Fixed days" or "Rotating". */
@Composable
fun programModeLabel(mode: ProgramMode): String = stringResource(
    when (mode) {
        ProgramMode.FIXED -> Res.string.program_mode_fixed
        ProgramMode.ROTATING -> Res.string.program_mode_rotating
    },
)

/**
 * Where a day sits in its program: "Day 2 of 3" when rotating; the weekdays
 * when fixed, "Mon, Thu", or "No weekdays" when it has none.
 */
@Composable
fun programDayPlace(mode: ProgramMode, number: Int, count: Int, weekdays: Set<Int>): String = when (mode) {
    ProgramMode.ROTATING -> stringResource(Res.string.program_day_of, number, count)
    ProgramMode.FIXED ->
        if (weekdays.isEmpty()) {
            stringResource(Res.string.program_no_weekdays)
        } else {
            ISO_WEEKDAYS.filter { it in weekdays }.joinToString(stringResource(Res.string.list_separator)) { weekdayShort(it) }
        }
}

/** A program day as a tappable row: routine name on top, its place in the program below. */
@Composable
fun ProgramDayChoiceRow(routineName: String, place: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(routineName, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(place, style = Tung.numbers.inline, color = Tung.colors.muted)
        }
    }
}

/** Rotating and fixed as two radio rows with their hints: the new-program dialog and the program's own screen. */
@Composable
fun ProgramModeChoice(selected: ProgramMode, onSelect: (ProgramMode) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.selectableGroup()) {
        listOf(
            ProgramMode.ROTATING to Res.string.program_mode_rotating_hint,
            ProgramMode.FIXED to Res.string.program_mode_fixed_hint,
        ).forEach { (option, hint) ->
            ModeOption(
                label = programModeLabel(option),
                hint = stringResource(hint),
                selected = selected == option,
                onSelect = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun ModeOption(label: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
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
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
        }
    }
}
