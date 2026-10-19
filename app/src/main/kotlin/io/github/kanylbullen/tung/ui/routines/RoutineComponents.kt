package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.ui.common.ExerciseNames
import io.github.kanylbullen.tung.ui.common.WorkoutReport
import io.github.kanylbullen.tung.ui.common.lastUsedText
import io.github.kanylbullen.tung.ui.common.localWeekday
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * A routine as one list row: name on top, exercise count and when it was last
 * done below, and a Start button. [onClick] opens it for editing, where offered.
 */
@Composable
fun RoutineRow(
    row: RoutineSummaryRow,
    now: Long,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val details = listOf(
        pluralStringResource(R.plurals.exercise_count, row.exerciseCount, row.exerciseCount),
        lastUsedText(row.lastUsedAt, now),
    ).joinToString(stringResource(R.string.row_separator))
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(details, style = Tung.numbers.inline, color = Tung.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        val description = stringResource(R.string.routine_start_description, row.name)
        Button(
            onClick = onStart,
            enabled = startEnabled,
            shape = TungCorner,
            colors = ButtonDefaults.buttonColors(
                containerColor = Tung.colors.raised,
                contentColor = Tung.colors.text,
                disabledContainerColor = Tung.colors.background,
                disabledContentColor = Tung.colors.muted,
            ),
            elevation = null,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
        ) {
            Text(stringResource(R.string.routine_start), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The name a routine saved from [report] is offered: the weekday and the first
 * two exercises, "Monday: Bench press, Barbell row".
 */
@Composable
fun defaultRoutineName(report: WorkoutReport): String {
    val context = LocalContext.current
    val weekday = localWeekday(report.workout.startedAt)
    val names = report.exercises.take(2).joinToString(stringResource(R.string.list_separator)) {
        ExerciseNames.resolve(context, it.nameKey, it.name)
    }
    return if (names.isEmpty()) weekday else stringResource(R.string.routine_default_name, weekday, names)
}

/** Asks for a routine name; used both to save a workout as a routine and to rename one. */
@Composable
fun RoutineNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var showError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Width from Compose rather than the platform dialog theme. Looks the same
        // (AlertDialog keeps itself 280-560dp wide), but with the platform width a
        // dialog holding a text field never settles under Robolectric at the flow
        // tests' 411dp width, so no test could ever get past it.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        shape = TungCorner,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it; showError = false },
                label = { Text(stringResource(R.string.routine_name)) },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text(stringResource(R.string.routine_error_name)) }
                } else {
                    null
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = TungCorner,
                colors = tungFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isBlank()) showError = true else onConfirm(name.trim()) }) {
                Text(confirmLabel, color = Tung.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}
