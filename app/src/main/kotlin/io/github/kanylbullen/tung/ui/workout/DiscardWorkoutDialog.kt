package io.github.kanylbullen.tung.ui.workout

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * "Discard this workout?" (issue #81), from the active workout's menu and from
 * the menu on Home's Resume card. Confirming runs `delete_workout`: the
 * workout, its exercises and its sets go, with a tombstone each, and nothing
 * is saved. Finishing is the other way out, and the only one that keeps sets.
 */
@Composable
fun DiscardWorkoutDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        shape = TungCorner,
        title = { Text(stringResource(R.string.workout_discard_title)) },
        text = { Text(stringResource(R.string.workout_discard_body), color = Tung.colors.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.workout_discard_confirm), color = Tung.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}
