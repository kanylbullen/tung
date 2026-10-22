package io.github.kanylbullen.tung.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import androidx.compose.ui.res.stringResource

@Composable
internal fun ProgressHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Tung.colors.muted,
        modifier = modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
internal fun ProgressMuted(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Tung.colors.muted,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

private enum class ChoiceSlot { PROBE, CHIPS, MENU }

/**
 * Single-choice chips on one line: the metric or the range of a chart. When
 * they do not fit the width (the exercise screen's four metrics on a phone,
 * or longer words in another language), the line is one chip naming the
 * choice, with an arrow, which opens a menu of all of them ([ChoiceMenu]).
 * Whether they fit is measured on the chips themselves, so it holds for any
 * width, font size and language.
 */
@Composable
internal fun <T> ChoiceChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    SubcomposeLayout(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) { constraints ->
        // Measured, never placed, and without semantics, so neither a screen reader nor a test sees it.
        val probe = subcompose(ChoiceSlot.PROBE) {
            Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { ChoiceChip(label(it), it == selected) {} }
            }
        }.single().measure(Constraints())
        val fits = !constraints.hasBoundedWidth || probe.width <= constraints.maxWidth
        val shown = subcompose(if (fits) ChoiceSlot.CHIPS else ChoiceSlot.MENU) {
            if (fits) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { option -> ChoiceChip(label(option), option == selected) { onSelect(option) } }
                }
            } else {
                ChoiceMenu(options, selected, label, onSelect)
            }
        }.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else probe.width
        layout(width, shown.maxOfOrNull { it.height } ?: 0) { shown.forEach { it.placeRelative(0, 0) } }
    }
}

@Composable
private fun ChoiceChip(text: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(text) },
        shape = TungCorner,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Tung.colors.raised,
            labelColor = Tung.colors.text,
            selectedContainerColor = Tung.colors.text,
            selectedLabelColor = Tung.colors.background,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = on,
            borderColor = Tung.colors.muted,
            selectedBorderColor = Tung.colors.text,
        ),
    )
}

/**
 * [ChoiceChips] when the chips do not fit: the choice as a selected chip
 * with an arrow ("Show all choices"), and a menu of every option, the
 * chosen one ticked.
 */
@Composable
private fun <T> ChoiceMenu(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label(selected)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.progress_show_choices)) },
            shape = TungCorner,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = Tung.colors.text,
                labelColor = Tung.colors.background,
                trailingIconContentColor = Tung.colors.background,
            ),
            border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = Tung.colors.text),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = Tung.colors.raised,
            shape = TungCorner,
        ) {
            options.forEach { option ->
                val on = option == selected
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = { open = false; onSelect(option) },
                    trailingIcon = if (on) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { this.selected = on },
                )
            }
        }
    }
}

/** One session: its date, muted, then the sets as "80 × 8 · 80 × 8 · 85 × 5". */
@Composable
internal fun SessionLine(date: String, sets: List<LoggedSet>) {
    val sep = stringResource(R.string.row_separator)
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}) {
        Text(date, style = Tung.numbers.inline, color = Tung.colors.muted, maxLines = 1, modifier = Modifier.width(112.dp))
        Text(
            sets.map { stringResource(R.string.exercise_set_value, localWeight(it.weightKg), it.reps) }.joinToString(sep),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            color = Tung.colors.text,
        )
    }
}
