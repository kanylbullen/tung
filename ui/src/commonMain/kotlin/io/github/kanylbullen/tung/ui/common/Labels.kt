package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.daysAgo
import io.github.kanylbullen.tung.core.formatWeight
import io.github.kanylbullen.tung.core.progress.dateOf
import io.github.kanylbullen.tung.data.systemMillis
import io.github.kanylbullen.tung.ui.resources.*
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The labels the shared screens write. JVM class LabelsKt: app/'s Format.kt keeps the rest.

/** Current time, refreshed every [intervalMillis]. */
@Composable
fun rememberNow(intervalMillis: Long = 1_000L): Long {
    var now by remember { mutableLongStateOf(systemMillis()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            now = systemMillis()
            delay(intervalMillis)
        }
    }
    return now
}

/** A muscle's name in lower case, for use inside a sentence ("Pairs with triceps"). */
@Composable
fun muscleLabel(muscle: Muscle): String = stringResource(
    when (muscle) {
        Muscle.CHEST -> Res.string.muscle_name_chest
        Muscle.LATS -> Res.string.muscle_name_lats
        Muscle.UPPER_BACK -> Res.string.muscle_name_upper_back
        Muscle.LOWER_BACK -> Res.string.muscle_name_lower_back
        Muscle.TRAPS -> Res.string.muscle_name_traps
        Muscle.FRONT_DELTS -> Res.string.muscle_name_front_delts
        Muscle.SIDE_DELTS -> Res.string.muscle_name_side_delts
        Muscle.REAR_DELTS -> Res.string.muscle_name_rear_delts
        Muscle.BICEPS -> Res.string.muscle_name_biceps
        Muscle.TRICEPS -> Res.string.muscle_name_triceps
        Muscle.FOREARMS -> Res.string.muscle_name_forearms
        Muscle.QUADS -> Res.string.muscle_name_quads
        Muscle.HAMSTRINGS -> Res.string.muscle_name_hamstrings
        Muscle.GLUTES -> Res.string.muscle_name_glutes
        Muscle.CALVES -> Res.string.muscle_name_calves
        Muscle.ADDUCTORS -> Res.string.muscle_name_adductors
        Muscle.ABDUCTORS -> Res.string.muscle_name_abductors
        Muscle.ABS -> Res.string.muscle_name_abs
        Muscle.OBLIQUES -> Res.string.muscle_name_obliques
        Muscle.HIP_FLEXORS -> Res.string.muscle_name_hip_flexors
        Muscle.NECK -> Res.string.muscle_name_neck
    }
)

/** "Last: 3 days ago", "Last: yesterday", "Last: today", or "Never" for a routine never started. */
@Composable
fun lastUsedText(lastUsedAt: Long?, now: Long): String {
    if (lastUsedAt == null) return stringResource(Res.string.routine_never)
    val ago = when (val days = daysAgo(lastUsedAt, now, TimeZone.currentSystemDefault()).toInt()) {
        0 -> stringResource(Res.string.routine_today)
        1 -> stringResource(Res.string.routine_yesterday)
        else -> pluralStringResource(Res.plurals.routine_days_ago, days, days)
    }
    return stringResource(Res.string.routine_last, ago)
}

/** "50%" as the locale writes it, or "<1%" for a muscle with a sliver of the work (the summary's and Home's muscle lines). */
@Composable
fun percentText(percent: Int): String =
    if (percent > 0) localPercent(percent / 100.0) else stringResource(Res.string.summary_muscle_less_than, localPercent(0.01))

/** [fraction] as a whole percentage the way the locale writes it: "50%", "50 %" in Swedish. */
expect fun localPercent(fraction: Double): String

/**
 * "Monday 19 October" ("Måndag 19 oktober" in Swedish) — first letter
 * capitalised, the rest as the locale writes it — with the year added when it
 * is not this year ("Monday 6 March 2023"), which imported history often is.
 */
fun localDate(epochMillis: Long): String = dateText(epochMillis, "EEEE d MMMM", "EEEE d MMMM yyyy")

/**
 * "Thu 22 Oct" ("tors 22 okt" in Swedish), the year added when it is not this
 * year: [localDate] for a list row, where the long weekday left no room for
 * the rest of the line.
 */
fun shortLocalDate(epochMillis: Long): String = dateText(epochMillis, "EEE d MMM", "EEE d MMM yyyy")

/** "1 h 12 min" or "42 min". */
@Composable
fun durationText(millis: Long): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        stringResource(Res.string.duration_hours_minutes, hours, minutes)
    } else {
        stringResource(Res.string.duration_minutes, minutes)
    }
}

/** [kg] as the locale writes a weight: "82,5" in Swedish. */
fun localWeight(kg: Double): String = formatWeight(kg, decimalSeparator())

/** "Monday" for the day [epochMillis] falls on in the device's zone, capitalised. */
fun localWeekday(epochMillis: Long): String =
    weekdayName(dateOf(epochMillis, TimeZone.currentSystemDefault()).dayOfWeek.isoDayNumber, WeekdayStyle.FULL)

/** A running clock: "4:07" or "1:12:30". */
fun clockText(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

