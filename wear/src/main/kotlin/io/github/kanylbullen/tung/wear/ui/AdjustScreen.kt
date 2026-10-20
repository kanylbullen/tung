package io.github.kanylbullen.tung.wear.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import io.github.kanylbullen.tung.wear.AdjustField
import io.github.kanylbullen.tung.wear.R
import io.github.kanylbullen.tung.wear.RotaryStepper
import kotlin.math.roundToInt

/**
 * Weight or reps for one set. The bezel or crown steps the value (the weight
 * ruler's local step, or one rep) with a tick of haptics per step; −/+ do the
 * same. Done sends it to the phone; a swipe back leaves it unchanged.
 */
@Composable
fun AdjustScreen(field: AdjustField, initial: Double, onDone: (Double) -> Unit) {
    var value by rememberSaveable { mutableDoubleStateOf(initial) }
    val stepper = remember { RotaryStepper() }
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }

    fun step(steps: Int) {
        val next = field.step(value, steps)
        if (next != value) {
            value = next
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { event ->
                val steps = stepper.feed(event.verticalScrollPixels)
                if (steps != 0) step(steps)
                true
            }
            .focusRequester(focusRequester)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(if (field == AdjustField.Weight) R.string.input_weight_title else R.string.input_reps_title),
                color = TungColors.muted,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val minusDescription = when (field) {
                    AdjustField.Weight -> stepDescription(R.plurals.input_minus_weight, field.stepBelow(value))
                    AdjustField.Reps -> stringResource(R.string.input_minus_reps)
                }
                val plusDescription = when (field) {
                    AdjustField.Weight -> stepDescription(R.plurals.input_plus_weight, field.stepAbove(value))
                    AdjustField.Reps -> stringResource(R.string.input_plus_reps)
                }
                Button(
                    onClick = { step(-1) },
                    enabled = field.canStepDown(value),
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.semantics { contentDescription = minusDescription },
                ) { Text(stringResource(R.string.input_minus), style = numberStyle(24.sp)) }
                Text(
                    text = if (field == AdjustField.Weight) localWeight(value) else value.roundToInt().toString(),
                    style = numberStyle(40.sp),
                )
                Button(
                    onClick = { step(1) },
                    enabled = field.canStepUp(value),
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.semantics { contentDescription = plusDescription },
                ) { Text(stringResource(R.string.input_plus), style = numberStyle(24.sp)) }
            }
            Text(
                text = stringResource(if (field == AdjustField.Weight) R.string.unit_kg else R.string.unit_reps),
                color = TungColors.muted,
                style = MaterialTheme.typography.caption1,
            )
            Chip(
                label = { Text(stringResource(R.string.input_done)) },
                onClick = { onDone(value) },
                colors = accentChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
}

/** "Increase by 2.5 kilos": the step at the current value, as on the phone. */
@Composable
private fun stepDescription(plurals: Int, step: Double): String {
    val text = localWeight(step)
    return pluralStringResource(plurals, if (step == 1.0) 1 else 2, text)
}
