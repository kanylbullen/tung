package io.github.kanylbullen.tung.ui.workout

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.KeyboardType
import io.github.kanylbullen.tung.core.REPS_RULER
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The reps sheet. A set takes 0 and up; a routine's target reps ([minimum] 1,
 * with its own [title] and [errorText]) use the same sheet.
 */
@Composable
fun repsSpec(
    initial: String,
    title: String = stringResource(Res.string.input_reps_title),
    minimum: Int = 0,
    errorText: String = stringResource(Res.string.input_error_reps),
): NumberInputSpec {
    val minusReps = stringResource(Res.string.input_minus_reps)
    val plusReps = stringResource(Res.string.input_plus_reps)
    return NumberInputSpec(
        title = title,
        unit = stringResource(Res.string.workout_unit_reps),
        initialText = initial,
        keyboardType = KeyboardType.Number,
        fieldDescription = stringResource(Res.string.input_field_reps),
        minusDescription = { _ -> minusReps },
        plusDescription = { _ -> plusReps },
        hint = stringResource(Res.string.input_hint),
        errorText = errorText,
        doneText = stringResource(Res.string.input_done),
        minusText = stringResource(Res.string.input_minus),
        plusText = stringResource(Res.string.input_plus),
        isValid = { text -> parseReps(text)?.let { it >= minimum } == true },
        step = { text, steps -> stepReps(parseReps(text) ?: 0, steps).coerceAtLeast(minimum).toString() },
        sanitize = { text -> text.filter { it.isDigit() }.take(3) },
        ruler = REPS_RULER,
        toValue = { parseReps(it)?.toDouble() },
        fromValue = { it.roundToInt().toString() },
        rulerDescription = stringResource(Res.string.input_ruler_reps),
        rulerState = { it.roundToInt().let { n -> pluralStringResource(Res.plurals.input_ruler_reps_state, n, n) } },
    )
}
