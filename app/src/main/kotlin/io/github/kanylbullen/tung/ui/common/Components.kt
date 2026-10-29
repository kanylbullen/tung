package io.github.kanylbullen.tung.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.WorkoutSummaryRow

/** The one loud button. Accent fill, 12dp corners, no elevation. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = TungCorner,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tung.colors.accent,
            contentColor = Tung.colors.onAccent,
            disabledContainerColor = Tung.colors.border,
            disabledContentColor = Tung.colors.muted,
        ),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** A quieter full-width button on the raised surface. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = TungCorner,
        colors = ButtonDefaults.buttonColors(
            containerColor = Tung.colors.raised,
            contentColor = Tung.colors.text,
            disabledContainerColor = Tung.colors.raised,
            disabledContentColor = Tung.colors.muted,
        ),
        elevation = null,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * A finished workout as one list row (Home's Recent, History): titled with
 * the routine it was started from, else its first two exercises, else
 * "Workout"; the date, duration and exercise count muted under it; the
 * volume in kg on the right. The date is its own text node, so a flow test
 * finds a workout by its date as before.
 */
@Composable
fun WorkoutRow(row: WorkoutSummaryRow, onClick: () -> Unit) {
    val sep = stringResource(R.string.row_separator)
    val details = listOf(
        durationText((row.endedAt ?: row.startedAt) - row.startedAt),
        pluralStringResource(R.plurals.exercise_count, row.exerciseCount, row.exerciseCount),
    ).joinToString(sep)
    val volume = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Tung.colors.text)) { append(localVolume(row.volumeKg)) }
        withStyle(SpanStyle(fontSize = 12.sp, color = Tung.colors.muted)) { append(" "); append(stringResource(R.string.workout_unit_kg)) }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                workoutRowTitle(row),
                style = MaterialTheme.typography.titleMedium,
                color = Tung.colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(localDate(row.startedAt), style = Tung.numbers.inline, color = Tung.colors.muted, maxLines = 1)
                Text(sep + details, style = Tung.numbers.inline, color = Tung.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(volume, style = Tung.numbers.inline, maxLines = 1)
    }
}

/** The row's title: the routine's name, else the first two exercises as displayed, else "Workout". */
@Composable
fun workoutRowTitle(row: WorkoutSummaryRow): String {
    row.routineName?.let { return it }
    val names = listOfNotNull(
        row.firstExerciseName?.let { exerciseDisplayName(row.firstExerciseKey, it) },
        row.secondExerciseName?.let { exerciseDisplayName(row.secondExerciseKey, it) },
    )
    return if (names.isEmpty()) stringResource(R.string.workout_row_untitled) else names.joinToString(stringResource(R.string.list_separator))
}

/**
 * A labelled big number, used in summary and detail. Value and label are one
 * semantics node, so a screen reader says "640, Volume, kg" in one go.
 */
@Composable
fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.semantics(mergeDescendants = true) {}) {
        Text(value, style = Tung.numbers.stat, color = Tung.colors.text, maxLines = 1)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
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

/** Text field colours on the raised surface, shared by every dialog with a text field. */
@Composable
fun tungFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Tung.colors.raised,
    unfocusedContainerColor = Tung.colors.raised,
    focusedBorderColor = Tung.colors.muted,
    unfocusedBorderColor = Tung.colors.border,
    focusedTextColor = Tung.colors.text,
    unfocusedTextColor = Tung.colors.text,
    cursorColor = Tung.colors.accent,
    focusedLeadingIconColor = Tung.colors.muted,
    unfocusedLeadingIconColor = Tung.colors.muted,
    focusedTrailingIconColor = Tung.colors.muted,
    unfocusedTrailingIconColor = Tung.colors.muted,
    focusedPlaceholderColor = Tung.colors.muted,
    unfocusedPlaceholderColor = Tung.colors.muted,
    focusedLabelColor = Tung.colors.muted,
    unfocusedLabelColor = Tung.colors.muted,
)
