package io.github.kanylbullen.tung.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.command.DEFAULT_ROUTINE_TARGET_SETS
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** The set counts the first-time reps sheet offers: what most people do. */
val FIRST_TIME_SET_CHOICES = listOf(3, 4, 5, 6, 8)

/** The count chosen until the user picks another: the same three sets a routine exercise starts with. */
const val FIRST_TIME_DEFAULT_SETS = DEFAULT_ROUTINE_TARGET_SETS

/**
 * "Sets: 3 · 4 · 5 · 6 · 8" above the reps sheet's Done, the first time an
 * exercise is added: how many sets Done creates. Calm, one accent for the
 * selected chip; each chip is a radio button described as "4 sets".
 */
@Composable
fun SetCountChips(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.workout_first_sets_label),
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.muted,
            modifier = Modifier.padding(end = 4.dp),
        )
        FIRST_TIME_SET_CHOICES.forEach { count ->
            val isSelected = count == selected
            val description = context.resources.getQuantityString(R.plurals.workout_first_sets_choice, count, count)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(TungCorner)
                    .background(if (isSelected) Tung.colors.accent else Tung.colors.background)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(count) })
                    .semantics { contentDescription = description },
            ) {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isSelected) Tung.colors.onAccent else Tung.colors.text,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}
