package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.kanylbullen.tung.core.wear.WatchChoice
import io.github.kanylbullen.tung.wear.R

/** The exercises the workout can take next, most recently trained first; a tap adds one and goes back to the workout. */
@Composable
fun AddExerciseScreen(choices: List<WatchChoice>, onPick: (String) -> Unit) {
    RoundList {
        item { ListHeader { Text(stringResource(R.string.add_exercise_title), color = TungColors.muted) } }
        if (choices.isEmpty()) {
            item { ListText(stringResource(R.string.add_exercise_none), style = MaterialTheme.typography.body2) }
        }
        items(choices, key = { it.exerciseId }) { choice ->
            Chip(
                label = { Text(choice.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                onClick = { onPick(choice.exerciseId) },
                colors = raisedChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
