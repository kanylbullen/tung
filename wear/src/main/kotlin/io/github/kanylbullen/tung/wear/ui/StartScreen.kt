package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.kanylbullen.tung.core.wear.WatchRoutine
import io.github.kanylbullen.tung.wear.R

/**
 * No workout in progress: start an empty one or one of the routines, or open
 * Settings (the account, and linking the watch to a server). [standalone]:
 * the workout will live on the watch, not on the phone.
 */
@Composable
fun StartScreen(
    routines: List<WatchRoutine>,
    standalone: Boolean,
    neverHeardFromPhone: Boolean,
    phoneUnreachable: Boolean,
    onStartWorkout: () -> Unit,
    onStartRoutine: (String) -> Unit,
    onSettings: () -> Unit,
) {
    RoundList {
        if (neverHeardFromPhone) item { ListText(stringResource(R.string.open_phone_hint), style = MaterialTheme.typography.body2) }
        if (standalone) item { ListText(stringResource(R.string.mode_standalone), style = MaterialTheme.typography.caption2) }
        if (phoneUnreachable) item { UnreachableNote() }
        item {
            Chip(
                label = { Text(stringResource(R.string.start_workout)) },
                onClick = onStartWorkout,
                colors = accentChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (routines.isNotEmpty()) {
            item { ListHeader { Text(stringResource(R.string.routines_title), color = TungColors.muted) } }
            items(routines, key = { it.id }) { routine ->
                Chip(
                    label = { Text(routine.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text(pluralStringResource(R.plurals.exercise_count, routine.exerciseCount, routine.exerciseCount))
                    },
                    onClick = { onStartRoutine(routine.id) },
                    colors = raisedChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Chip(
                label = { Text(stringResource(R.string.settings_title)) },
                onClick = onSettings,
                colors = raisedChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun UnreachableNote() {
    ListText(stringResource(R.string.phone_unreachable), color = TungColors.accent)
}
