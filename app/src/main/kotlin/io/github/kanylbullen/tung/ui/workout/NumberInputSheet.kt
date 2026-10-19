package io.github.kanylbullen.tung.ui.workout

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.RulerScale
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** Everything that differs between editing a weight and editing reps. */
data class NumberInputSpec(
    val title: String,
    val unit: String,
    val initialText: String,
    val keyboardType: KeyboardType,
    val fieldDescription: String,
    val minusDescription: String,
    val plusDescription: String,
    val hint: String,
    val errorText: String,
    val doneText: String,
    val minusText: String,
    val plusText: String,
    /** Is the text a valid value? */
    val isValid: (String) -> Boolean,
    /** Text after one −/+ press (steps = -1 or 1). */
    val step: (text: String, steps: Int) -> String,
    /** Strips characters that can never be part of a value. */
    val sanitize: (String) -> String,
    /** The ruler under the number: its steps and range. */
    val ruler: RulerScale,
    /** The text as a number for the ruler, or null when it is not one. */
    val toValue: (String) -> Double?,
    /** A value on the ruler as text: what a drag writes into the field, and the tick labels. */
    val fromValue: (Double) -> String,
    val rulerDescription: String,
    /** What the ruler points at, for screen readers: "82.5 kilos". */
    val rulerState: (Double) -> String,
)

/**
 * The signature of the app: one enormous number, a ruler to drag it, two
 * thumb-sized buttons and a "done". The number itself is the text field — tap
 * it to type.
 *
 * [showApplyToAll] adds the "Apply to all sets" switch; it starts off every
 * time the sheet opens, and its state is handed to [onConfirm].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NumberInputSheet(
    spec: NumberInputSpec,
    onDismiss: () -> Unit,
    onConfirm: (text: String, applyToAll: Boolean) -> Unit,
    showApplyToAll: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var field by remember {
        mutableStateOf(TextFieldValue(spec.initialText, TextRange(spec.initialText.length)))
    }
    // Tapping the number selects all of it, so typing replaces the value instead of
    // appending to it (#7). The text field places the caret itself after a tap, so
    // the tap is noted here and that caret move is turned into a select-all.
    var focused by remember { mutableStateOf(false) }
    var tapped by remember { mutableStateOf(false) }
    var applyToAll by remember { mutableStateOf(false) }
    val valid = spec.isValid(field.text)
    val confirm = { if (valid) onConfirm(field.text, applyToAll) }

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
                .imePadding()
                .padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(spec.title, style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted)
            BasicTextField(
                value = field,
                onValueChange = { new ->
                    field = if (tapped && new.text == field.text) {
                        new.selectAll()
                    } else {
                        new.copy(text = spec.sanitize(new.text))
                    }
                    tapped = false
                },
                singleLine = true,
                textStyle = Tung.numbers.huge.copy(color = Tung.colors.text, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Tung.colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = spec.keyboardType, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .onFocusChanged { state ->
                        if (state.hasFocus && !focused) field = field.selectAll()
                        focused = state.hasFocus
                    }
                    .pointerInput(Unit) {
                        // Initial pass: seen before the text field handles the same tap.
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (waitForUpOrCancellation(PointerEventPass.Initial) != null) tapped = true
                        }
                    }
                    .semantics { contentDescription = spec.fieldDescription },
            )
            Text(spec.unit, style = MaterialTheme.typography.titleLarge, color = Tung.colors.muted)
            Spacer(Modifier.height(8.dp))
            RulerPicker(
                scale = spec.ruler,
                value = spec.toValue(field.text),
                onValueChange = { value ->
                    tapped = false
                    field = spec.fromValue(value).let { TextFieldValue(it, TextRange(it.length)) }
                },
                tickLabel = spec.fromValue,
                description = spec.rulerDescription,
                stateDescriptionOf = spec.rulerState,
                modifier = Modifier.fullBleed(20.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (valid) spec.hint else spec.errorText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (valid) Tung.colors.muted else Tung.colors.text,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepButton(spec.minusText, spec.minusDescription, Modifier.weight(1f)) {
                    tapped = false
                    field = spec.step(field.text, -1).let { TextFieldValue(it, TextRange(it.length)) }
                }
                StepButton(spec.plusText, spec.plusDescription, Modifier.weight(1f)) {
                    tapped = false
                    field = spec.step(field.text, 1).let { TextFieldValue(it, TextRange(it.length)) }
                }
            }
            if (showApplyToAll) {
                Spacer(Modifier.height(8.dp))
                ApplyToAllRow(checked = applyToAll, onCheckedChange = { applyToAll = it })
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(16.dp))
            }
            PrimaryButton(text = spec.doneText, onClick = confirm, enabled = valid)
        }
    }
}

private fun TextFieldValue.selectAll() = copy(selection = TextRange(0, text.length))

/** Widens a child of a column padded by [padding] on each side back to the full width of the sheet. */
private fun Modifier.fullBleed(padding: Dp) = layout { measurable, constraints ->
    val extra = (padding * 2).roundToPx()
    val width = constraints.maxWidth + extra
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun ApplyToAllRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.input_apply_all),
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.text,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Tung.colors.onAccent,
                checkedTrackColor = Tung.colors.accent,
                checkedBorderColor = Tung.colors.accent,
                uncheckedThumbColor = Tung.colors.muted,
                uncheckedTrackColor = Tung.colors.background,
                uncheckedBorderColor = Tung.colors.muted,
            ),
        )
    }
}

@Composable
private fun StepButton(symbol: String, description: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TungCorner,
        color = Tung.colors.background,
        contentColor = Tung.colors.text,
        modifier = modifier
            .height(96.dp)
            .semantics { contentDescription = description },
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(symbol, fontSize = 56.sp, fontWeight = FontWeight.Bold, style = Tung.numbers.set)
        }
    }
}
