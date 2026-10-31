package io.github.kanylbullen.tung.ui.workout

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.stepRestSeconds
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** "1:30", or "Off" for 0. */
@Composable
fun restSecondsText(seconds: Int): String =
    if (seconds <= 0) stringResource(R.string.rest_off) else clockText(seconds * 1000L)

/**
 * The rest timer of one exercise, from its menu in the active workout: the
 * value as a big number and −/+ in 30 s steps from off (0) to 5:00. The same
 * shape as the number sheet, without the typing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestTimerSheet(
    exerciseName: String,
    initialSeconds: Int,
    onDismiss: () -> Unit,
    onConfirm: (seconds: Int) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var seconds by remember { mutableIntStateOf(initialSeconds) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TungCorner,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.rest_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
            Text(
                stringResource(R.string.rest_between_sets, exerciseName),
                style = MaterialTheme.typography.bodyMedium,
                color = Tung.colors.muted,
                textAlign = TextAlign.Center,
            )
            Text(
                restSecondsText(seconds),
                style = Tung.numbers.huge,
                color = Tung.colors.text,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.rest_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Tung.colors.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepButton(stringResource(R.string.rest_minus), stringResource(R.string.rest_shorter), Modifier.weight(1f), 32.sp) {
                    seconds = stepRestSeconds(seconds, -1)
                }
                StepButton(stringResource(R.string.rest_plus), stringResource(R.string.rest_longer), Modifier.weight(1f), 32.sp) {
                    seconds = stepRestSeconds(seconds, 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton(text = stringResource(Res.string.input_done), onClick = { onConfirm(seconds) })
        }
    }
}
