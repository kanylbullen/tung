package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.graphics.Color
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kanylbullen.tung.ui.resources.*
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.ui.common.lastUsedText
import io.github.kanylbullen.tung.ui.common.RoutineName
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.ui.common.muscleLabel
import io.github.kanylbullen.tung.ui.exercises.MuscleHeatMap

/**
 * A routine as one list row: name on top, exercise count and when it was last
 * done below, and a Start button. [onClick] opens it for editing, where offered.
 * Start is disabled for a routine with no exercises, whatever [startEnabled] says.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoutineRow(
    row: RoutineSummaryRow,
    now: Long,
    startEnabled: Boolean,
    onStart: () -> Unit,
    onClick: (() -> Unit)? = null,
    /** Where offered (the Routines tab): a long press, or the screen reader's "Select" action, starts selection mode. */
    onLongClick: (() -> Unit)? = null,
    /** Non-null in selection mode: whether this row is selected. A tap then toggles it ([onClick]) and Start is hidden. */
    selected: Boolean? = null,
    /** Home's routine group: on the raised surface, with an outlined 40 dp Start instead of the raised one. */
    compact: Boolean = false,
    /** The muscles the routine works ([io.github.kanylbullen.tung.data.routineMuscles]): drawn as a small body before the name. */
    muscles: MuscleBreakdown? = null,
) {
    val details = listOf(
        pluralStringResource(Res.plurals.exercise_count, row.exerciseCount, row.exerciseCount),
        lastUsedText(row.lastUsedAt, now),
    ).joinToString(stringResource(Res.string.row_separator))
    val base = Modifier.fillMaxWidth()
    val selectLabel = stringResource(Res.string.routines_select)
    val interaction = when {
        selected != null && onClick != null -> base.selectable(selected = selected, role = Role.Checkbox, onClick = onClick)
        onClick != null && onLongClick != null -> base.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel)
        onClick != null -> base.clickable(onClick = onClick)
        else -> base
    }
    Row(
        modifier = interaction
            .heightIn(min = if (compact) 56.dp else 64.dp)
            .padding(start = if (compact) 16.dp else 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (muscles != null && !muscles.isEmpty) {
            // What kind of day it is, at a glance: the most worked muscles named for a screen reader.
            val named = muscles.muscles.take(ROW_MUSCLES).map { muscleLabel(it.muscle) }.joinToString(stringResource(Res.string.list_separator))
            MuscleHeatMap(
                intensity = muscles::intensity,
                description = stringResource(Res.string.routine_muscles_description, named),
                height = ROW_BODY_HEIGHT,
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RoutineName(row.name)
            Text(details, style = Tung.numbers.inline, color = Tung.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        if (selected != null) {
            // The row carries the selected state for a screen reader; the box only shows it.
            Checkbox(
                checked = selected,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    checkedColor = Tung.colors.accent,
                    checkmarkColor = Tung.colors.onAccent,
                    uncheckedColor = Tung.colors.muted,
                ),
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            val description = stringResource(Res.string.routine_start_description, row.name)
            val enabled = startEnabled && row.exerciseCount > 0
            if (compact) {
                Button(
                    onClick = onStart,
                    enabled = enabled,
                    shape = TungCorner,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = Tung.colors.text,
                        disabledContainerColor = Color.Transparent,
                        disabledContentColor = Tung.colors.muted,
                    ),
                    border = BorderStroke(1.dp, Tung.colors.border),
                    elevation = null,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = description },
                ) {
                    Text(stringResource(Res.string.routine_start), style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Button(
                    onClick = onStart,
                    enabled = enabled,
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
                    Text(stringResource(Res.string.routine_start), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}


/** The small body in a routine row: both figures, as tall as the row's two lines. */
private val ROW_BODY_HEIGHT = 44.dp

/** How many muscles a row's body names for a screen reader. */
private const val ROW_MUSCLES = 3

/**
 * Asks for a name; used to save a workout as a routine, to create an empty
 * routine and to rename a routine or a program. [errorText] is shown when Save is tapped with no name.
 */
@Composable
fun RoutineNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    errorText: String = stringResource(Res.string.routine_error_name),
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
                label = { Text(stringResource(Res.string.routine_name)) },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text(errorText) }
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
                Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}
