package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.daysAgo
import io.github.kanylbullen.tung.core.formatWeight
import kotlinx.coroutines.delay
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

fun localWeight(kg: Double): String =
    formatWeight(kg, DecimalFormatSymbols.getInstance().decimalSeparator)

/** Whole kilos with locale grouping: 12 450. */
fun localVolume(kg: Double): String = NumberFormat.getIntegerInstance().format(kg.roundToLong())

/** "Monday 19 October" ("Måndag 19 oktober" in Swedish) — first letter capitalised, the rest as the locale writes it. */
fun localDate(epochMillis: Long): String {
    val locale = Locale.getDefault()
    val text = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

@Composable
fun durationText(millis: Long): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) {
        stringResource(R.string.duration_hours_minutes, hours, minutes)
    } else {
        stringResource(R.string.duration_minutes, minutes)
    }
}

/** A running clock: "4:07" or "1:12:30". */
fun clockText(millis: Long): String {
    val totalSeconds = millis.coerceAtLeast(0L) / 1000L
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Current time, refreshed every [intervalMillis]. */
@Composable
fun rememberNow(intervalMillis: Long = 1_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(intervalMillis) {
        while (true) {
            now = System.currentTimeMillis()
            delay(intervalMillis)
        }
    }
    return now
}

@Composable
fun muscleGroupLabel(group: MuscleGroup): String = stringResource(
    when (group) {
        MuscleGroup.CHEST -> R.string.muscle_chest
        MuscleGroup.BACK -> R.string.muscle_back
        MuscleGroup.SHOULDERS -> R.string.muscle_shoulders
        MuscleGroup.BICEPS -> R.string.muscle_biceps
        MuscleGroup.TRICEPS -> R.string.muscle_triceps
        MuscleGroup.LEGS -> R.string.muscle_legs
        MuscleGroup.GLUTES -> R.string.muscle_glutes
        MuscleGroup.CORE -> R.string.muscle_core
        MuscleGroup.FULLBODY -> R.string.muscle_fullbody
    }
)

@Composable
fun equipmentLabel(equipment: Equipment): String = stringResource(
    when (equipment) {
        Equipment.BARBELL -> R.string.equipment_barbell
        Equipment.DUMBBELL -> R.string.equipment_dumbbell
        Equipment.MACHINE -> R.string.equipment_machine
        Equipment.CABLE -> R.string.equipment_cable
        Equipment.BODYWEIGHT -> R.string.equipment_bodyweight
        Equipment.OTHER -> R.string.equipment_other
    }
)

/** "Monday" ("Måndag" in Swedish), first letter capitalised. */
fun localWeekday(epochMillis: Long): String {
    val locale = Locale.getDefault()
    val text = DateTimeFormatter.ofPattern("EEEE", locale)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** "Last: 3 days ago", "Last: yesterday", "Last: today", or "Never" for a routine never started. */
@Composable
fun lastUsedText(lastUsedAt: Long?, now: Long): String {
    if (lastUsedAt == null) return stringResource(R.string.routine_never)
    val ago = when (val days = daysAgo(lastUsedAt, now, ZoneId.systemDefault()).toInt()) {
        0 -> stringResource(R.string.routine_today)
        1 -> stringResource(R.string.routine_yesterday)
        else -> pluralStringResource(R.plurals.routine_days_ago, days, days)
    }
    return stringResource(R.string.routine_last, ago)
}
