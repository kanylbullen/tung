package io.github.kanylbullen.tung.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import io.github.kanylbullen.tung.core.wear.WatchWorkoutState
import io.github.kanylbullen.tung.wear.R
import io.github.kanylbullen.tung.wear.currentExerciseIndex

/**
 * The workout's exercises with how many sets are ticked, to jump straight to
 * one: [onPick] gets its index in the pager. The exercise holding the current
 * set is in the accent; Add exercise follows the list when there is something
 * to add.
 */
@Composable
fun ExerciseListScreen(state: WatchWorkoutState, onPick: (Int) -> Unit, onAddExercise: (() -> Unit)?) {
    val current = state.currentExerciseIndex()
    RoundList {
        item { ListHeader { Text(stringResource(R.string.exercises_title), color = TungColors.muted) } }
        itemsIndexed(state.exercises, key = { _, e -> e.workoutExerciseId }) { index, exercise ->
            Chip(
                label = {
                    Text(
                        exercise.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = if (index == current) TungColors.accent else TungColors.text,
                    )
                },
                secondaryLabel = {
                    Text(stringResource(R.string.sets_done, exercise.sets.count { it.completed }, exercise.sets.size))
                },
                onClick = { onPick(index) },
                colors = raisedChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (onAddExercise != null) {
            item {
                Chip(
                    label = { Text(stringResource(R.string.add_exercise)) },
                    onClick = onAddExercise,
                    colors = accentChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
