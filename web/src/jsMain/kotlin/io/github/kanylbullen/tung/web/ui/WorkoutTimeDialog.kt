package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.WORKOUT_CLOCK_SKEW_MILLIS
import io.github.kanylbullen.tung.web.WebContainer
import kotlin.js.Date
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/** One step of a duration: a quarter of an hour, as on the phone. */
private const val STEP_MILLIS = 15L * 60 * 1000

private fun pad(n: Int): String = n.toString().padStart(2, '0')

/** "2026-10-30": [epochMillis]'s date in the browser's zone, as `<input type="date">` takes it. */
fun dateInputValue(epochMillis: Long): String {
    val d = Date(epochMillis.toDouble())
    return "${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}"
}

/** "18:00": [epochMillis]'s time of day in the browser's zone, as `<input type="time">` takes it. */
fun timeInputValue(epochMillis: Long): String {
    val d = Date(epochMillis.toDouble())
    return "${pad(d.getHours())}:${pad(d.getMinutes())}"
}

/** The moment a date input's and a time input's values name in the browser's zone; null when either does not parse. */
fun localMillis(date: String, time: String): Long? {
    val (y, m, d) = date.split('-').takeIf { it.size == 3 }?.map { it.toIntOrNull() ?: return null } ?: return null
    val (h, min) = time.split(':').takeIf { it.size >= 2 }?.take(2)?.map { it.toIntOrNull() ?: return null } ?: return null
    return Date(y, m - 1, d, h, min, 0, 0).getTime().toLong()
}

/** "Thu 30 Oct · 18:00": when a workout logged after the fact started. */
fun workoutStartText(epochMillis: Long, container: WebContainer, now: Long): String =
    shortDate(epochMillis, container.locale, now) + container.strings.separator + timeInputValue(epochMillis)

/** The start "Log past workout" offers: an hour before [now], down to the quarter hour. */
fun defaultPastStart(now: Long): Long {
    val d = Date((now - 60L * 60 * 1000).toDouble())
    return Date(d.getFullYear(), d.getMonth(), d.getDate(), d.getHours(), d.getMinutes() / 15 * 15, 0, 0).getTime().toLong()
}

/** "Duration  − 1 h 30 min +", in quarter hours between [min] and [max]. */
@Composable
fun DurationControl(container: WebContainer, durationMillis: Long, min: Long, max: Long, onChange: (Long) -> Unit) {
    val strings = container.strings
    Div({ classes("field", "dialog-field") }) {
        Span { Text(strings.duration) }
        Div({ classes("duration-stepper") }) {
            Button({
                classes("stepper-small")
                attr("aria-label", strings.durationShorter)
                if (durationMillis - STEP_MILLIS < min) disabled()
                onClick { onChange(durationMillis - STEP_MILLIS) }
            }) { Text("−") }
            Span({ classes("numbers", "duration-value"); attr("aria-live", "polite") }) { Text(durationText(durationMillis, strings)) }
            Button({
                classes("stepper-small")
                attr("aria-label", strings.durationLonger)
                if (durationMillis + STEP_MILLIS > max) disabled()
                onClick { onChange(durationMillis + STEP_MILLIS) }
            }) { Text("+") }
        }
    }
}

/**
 * A workout's date and start (`<input type="date">`, not after today, and
 * `<input type="time">`) and, when [initialDurationMillis] is given, its
 * duration; [confirm] is disabled while the start or the end lies ahead of
 * [now]. [onConfirm] gets the start and the duration (null when none was asked).
 */
@Composable
fun WorkoutTimeDialog(
    container: WebContainer,
    title: String,
    confirm: String,
    initialStart: Long,
    initialDurationMillis: Long?,
    now: Long,
    onCancel: () -> Unit,
    onConfirm: (startedAt: Long, durationMillis: Long?) -> Unit,
) {
    val strings = container.strings
    var date by remember { mutableStateOf(dateInputValue(initialStart)) }
    var time by remember { mutableStateOf(timeInputValue(initialStart)) }
    var duration by remember { mutableStateOf(initialDurationMillis ?: 0L) }
    // Unchanged inputs keep the exact start, seconds and all, so saving without a change writes nothing.
    val start = if (date == dateInputValue(initialStart) && time == timeInputValue(initialStart)) initialStart else localMillis(date, time)
    val latest = now + WORKOUT_CLOCK_SKEW_MILLIS
    val valid = start != null && start <= latest && start + duration <= latest
    Div({ classes("sheet-backdrop"); onClick { onCancel() } })
    Div({ classes("dialog"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", title) }) {
        H2 { Text(title) }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.date) }
            Input(InputType.Date) {
                value(date)
                attr("max", dateInputValue(now))
                attr("min", "2000-01-01")
                onInput { date = it.value }
            }
        }
        Label(attrs = { classes("field", "dialog-field") }) {
            Span { Text(strings.startTime) }
            Input(InputType.Time) {
                value(time)
                onInput { time = it.value }
            }
        }
        if (initialDurationMillis != null) {
            DurationControl(container, duration, min = minOf(STEP_MILLIS, initialDurationMillis), max = 24L * 60 * 60 * 1000) { duration = it }
        }
        Div({ classes("dialog-actions") }) {
            Button({ classes("secondary"); onClick { onCancel() } }) { Text(strings.cancel) }
            Button({
                classes("accent-button")
                if (!valid) disabled()
                onClick { if (start != null) onConfirm(start, initialDurationMillis?.let { duration }) }
            }) { Text(confirm) }
        }
    }
}
