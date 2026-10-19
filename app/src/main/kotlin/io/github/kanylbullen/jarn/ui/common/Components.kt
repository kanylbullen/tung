package io.github.kanylbullen.jarn.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.jarn.ui.theme.Jarn
import io.github.kanylbullen.jarn.ui.theme.JarnCorner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kanylbullen.jarn.R
import io.github.kanylbullen.jarn.data.WorkoutSummaryRow

/** The one loud button. Accent fill, 12dp corners, no elevation. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = JarnCorner,
        colors = ButtonDefaults.buttonColors(
            containerColor = Jarn.colors.accent,
            contentColor = Jarn.colors.onAccent,
            disabledContainerColor = Jarn.colors.border,
            disabledContentColor = Jarn.colors.muted,
        ),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A quieter full-width button on the raised surface. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = JarnCorner,
        colors = ButtonDefaults.buttonColors(
            containerColor = Jarn.colors.raised,
            contentColor = Jarn.colors.text,
        ),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A finished workout as one list row: date on top, the numbers below. */
@Composable
fun WorkoutRow(row: WorkoutSummaryRow, onClick: () -> Unit) {
    val sep = stringResource(R.string.row_separator)
    val details = listOf(
        durationText((row.endedAt ?: row.startedAt) - row.startedAt),
        pluralStringResource(R.plurals.exercise_count, row.exerciseCount, row.exerciseCount),
        stringResource(R.string.volume_kg, localVolume(row.volumeKg)),
    ).joinToString(sep)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(localDate(row.startedAt), style = MaterialTheme.typography.titleMedium, color = Jarn.colors.text)
        Text(details, style = Jarn.numbers.inline, color = Jarn.colors.muted)
    }
}

/** A labelled big number, used in summary and detail. */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(value, style = Jarn.numbers.stat, color = Jarn.colors.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Jarn.colors.muted)
    }
}

@Composable
fun StatRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}
