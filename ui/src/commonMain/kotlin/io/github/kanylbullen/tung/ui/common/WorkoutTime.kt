// kotlinx-datetime's Instant conversions are opt-in where the iOS klibs resolve a newer kotlinx-datetime.
@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.kanylbullen.tung.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.kanylbullen.tung.core.EARLIEST_WORKOUT_MILLIS
import io.github.kanylbullen.tung.core.WORKOUT_CLOCK_SKEW_MILLIS
import io.github.kanylbullen.tung.ui.resources.*
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

/** One step of a workout's start or duration in the time dialogs: a quarter of an hour. */
const val WORKOUT_TIME_STEP_MILLIS: Long = 15L * 60 * 1000

private fun zone(): TimeZone = TimeZone.currentSystemDefault()

private fun localOf(epochMillis: Long): LocalDateTime = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone())

private fun millisOf(local: LocalDateTime): Long = local.toInstant(zone()).toEpochMilliseconds()

/** "18:00": the time of day of [epochMillis] in the device's zone, on the 24-hour clock. */
fun timeOfDayText(epochMillis: Long): String {
    val t = localOf(epochMillis)
    return t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')
}

/** "Thu 30 Oct · 18:00": when a workout logged after the fact started. */
@Composable
fun workoutStartText(epochMillis: Long): String =
    shortLocalDate(epochMillis) + stringResource(Res.string.row_separator) + timeOfDayText(epochMillis)

/**
 * The start a workout logged after the fact is offered first: an hour before
 * [now], down to the quarter hour.
 */
fun defaultPastStart(now: Long): Long {
    val start = now - 60L * 60 * 1000
    val local = localOf(start)
    return millisOf(LocalDateTime(local.date, LocalTime(local.hour, local.minute / 15 * 15)))
}

/** [epochMillis] moved by [days] calendar days, the same time of day. */
fun shiftDays(epochMillis: Long, days: Int): Long {
    val local = localOf(epochMillis)
    val date = if (days >= 0) local.date.plus(DatePeriod(days = days)) else local.date.minus(DatePeriod(days = -days))
    return millisOf(LocalDateTime(date, local.time))
}

/**
 * A workout's date and start, and, when [initialDurationMillis] is given,
 * its duration, as −/+ steps (a day, a quarter of an hour) for the thumb,
 * with the date and the time behind a tap for Material's pickers. Nothing
 * may lie ahead of [now]: the steps and [confirmLabel] are disabled there.
 * [onConfirm] gets the start and the duration (null when none was asked).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutTimeDialog(
    title: String,
    confirmLabel: String,
    initialStart: Long,
    initialDurationMillis: Long?,
    now: Long,
    onDismiss: () -> Unit,
    onConfirm: (startedAt: Long, durationMillis: Long?) -> Unit,
    /** The longest duration the steps reach. */
    maxDurationMillis: Long = 24L * 60 * 60 * 1000,
    /** Why the last confirm was refused, shown under the fields; null for nothing. */
    error: String? = null,
) {
    var start by rememberSaveable { mutableLongStateOf(initialStart) }
    var duration by rememberSaveable { mutableLongStateOf(initialDurationMillis ?: 0L) }
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    var pickingTime by rememberSaveable { mutableStateOf(false) }
    val latest = now + WORKOUT_CLOCK_SKEW_MILLIS
    val withDuration = initialDurationMillis != null
    val minDuration = minOf(WORKOUT_TIME_STEP_MILLIS, initialDurationMillis ?: WORKOUT_TIME_STEP_MILLIS)
    val end = start + if (withDuration) duration else 0L
    val valid = start in EARLIEST_WORKOUT_MILLIS..latest && end <= latest

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val dateText = shortLocalDate(start)
                StepRow(
                    label = stringResource(Res.string.time_date),
                    value = dateText,
                    valueDescription = stringResource(Res.string.time_pick_date, dateText),
                    onValueClick = { pickingDate = true },
                    lessDescription = stringResource(Res.string.time_day_earlier),
                    moreDescription = stringResource(Res.string.time_day_later),
                    canLess = shiftDays(start, -1) >= EARLIEST_WORKOUT_MILLIS,
                    canMore = shiftDays(start, 1) + (if (withDuration) duration else 0L) <= latest,
                    onLess = { start = shiftDays(start, -1) },
                    onMore = { start = shiftDays(start, 1) },
                )
                val timeText = timeOfDayText(start)
                StepRow(
                    label = stringResource(Res.string.time_start),
                    value = timeText,
                    valueDescription = stringResource(Res.string.time_pick_start, timeText),
                    onValueClick = { pickingTime = true },
                    lessDescription = stringResource(Res.string.time_start_earlier),
                    moreDescription = stringResource(Res.string.time_start_later),
                    canLess = start - WORKOUT_TIME_STEP_MILLIS >= EARLIEST_WORKOUT_MILLIS,
                    canMore = end + WORKOUT_TIME_STEP_MILLIS <= latest,
                    onLess = { start -= WORKOUT_TIME_STEP_MILLIS },
                    onMore = { start += WORKOUT_TIME_STEP_MILLIS },
                )
                if (withDuration) {
                    DurationStepper(
                        durationMillis = duration,
                        canLess = duration - WORKOUT_TIME_STEP_MILLIS >= minDuration,
                        canMore = duration + WORKOUT_TIME_STEP_MILLIS <= maxDurationMillis && end + WORKOUT_TIME_STEP_MILLIS <= latest,
                        onLess = { duration -= WORKOUT_TIME_STEP_MILLIS },
                        onMore = { duration += WORKOUT_TIME_STEP_MILLIS },
                    )
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(start, if (withDuration) duration else null) }, enabled = valid) {
                Text(confirmLabel, color = if (valid) Tung.colors.accentText else Tung.colors.muted)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
            }
        },
    )

    if (pickingDate) {
        val today = localOf(now).date
        val utcToday = today.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = localOf(start).date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds(),
            yearRange = 2000..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= utcToday
                override fun isSelectableYear(year: Int): Boolean = year <= today.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utc ->
                        val date = Instant.fromEpochMilliseconds(utc).toLocalDateTime(TimeZone.UTC).date
                        start = millisOf(LocalDateTime(date, localOf(start).time))
                    }
                    pickingDate = false
                }) { Text(stringResource(Res.string.time_choose), color = Tung.colors.accentText) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(Res.string.action_cancel), color = Tung.colors.text) }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (pickingTime) {
        val local = localOf(start)
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    start = millisOf(local.date.atTime(state.hour, state.minute))
                    pickingTime = false
                }) { Text(stringResource(Res.string.time_choose), color = Tung.colors.accentText) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = false }) { Text(stringResource(Res.string.action_cancel), color = Tung.colors.text) }
            },
        )
    }
}

/** "Duration  − 1 h 30 min +", in quarter hours. */
@Composable
fun DurationStepper(durationMillis: Long, canLess: Boolean, canMore: Boolean, onLess: () -> Unit, onMore: () -> Unit) {
    val text = durationText(durationMillis)
    StepRow(
        label = stringResource(Res.string.stat_duration),
        value = text,
        valueDescription = null,
        onValueClick = null,
        lessDescription = stringResource(Res.string.time_duration_shorter),
        moreDescription = stringResource(Res.string.time_duration_longer),
        canLess = canLess,
        canMore = canMore,
        onLess = onLess,
        onMore = onMore,
    )
}

@Composable
private fun StepRow(
    label: String,
    value: String,
    valueDescription: String?,
    onValueClick: (() -> Unit)?,
    lessDescription: String,
    moreDescription: String,
    canLess: Boolean,
    canMore: Boolean,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SquareStep(stringResource(Res.string.input_minus), lessDescription, canLess, onLess)
            if (onValueClick != null) {
                TextButton(
                    onClick = onValueClick,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { valueDescription?.let { contentDescription = it } },
                ) {
                    Text(value, style = Tung.numbers.inline, color = Tung.colors.text, textAlign = TextAlign.Center)
                }
            } else {
                Text(value, style = Tung.numbers.inline, color = Tung.colors.text, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
            SquareStep(stringResource(Res.string.input_plus), moreDescription, canMore, onMore)
        }
    }
}

/** A square −/+ on the raised surface, thumb-sized, like the routine page's. */
@Composable
private fun SquareStep(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = TungCorner,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tung.colors.background,
            contentColor = Tung.colors.text,
            disabledContainerColor = Tung.colors.raised,
            disabledContentColor = Tung.colors.border,
        ),
        elevation = null,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Text(text, style = Tung.numbers.set)
    }
}
