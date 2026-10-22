package io.github.kanylbullen.tung.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

/** A row of single-choice chips: the metric or the range of a chart. It wraps when the chips do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceChips(options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val on = option == selected
            FilterChip(
                selected = on,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
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
