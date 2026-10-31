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
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.daysAgo
import io.github.kanylbullen.tung.core.formatWeight
import kotlinx.coroutines.delay
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlinx.datetime.TimeZone
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

fun localWeight(kg: Double): String =
    formatWeight(kg, DecimalFormatSymbols.getInstance().decimalSeparator)

/** Whole kilos with locale grouping: 12 450. */
fun localVolume(kg: Double): String = NumberFormat.getIntegerInstance().format(kg.roundToLong())

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

/** [epochMillis] in the system zone by [pattern], or [patternWithYear] when it is not this year, first letter capitalised. */
private fun dateText(epochMillis: Long, pattern: String, patternWithYear: String): String {
    val locale = Locale.getDefault()
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val chosen = if (date.year == java.time.ZonedDateTime.now(zone).year) pattern else patternWithYear
    val text = DateTimeFormatter.ofPattern(chosen, locale).format(date)
    return text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** "6 March 2023": a date with its year, for ranges such as an import's. */
fun localDateWithYear(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

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
        Equipment.EZ_BAR -> R.string.equipment_ez_bar
        Equipment.DUMBBELL -> R.string.equipment_dumbbell
        Equipment.KETTLEBELL -> R.string.equipment_kettlebell
        Equipment.MACHINE -> R.string.equipment_machine
        Equipment.CABLE -> R.string.equipment_cable
        Equipment.BAND -> R.string.equipment_band
        Equipment.BODYWEIGHT -> R.string.equipment_bodyweight
        Equipment.MEDICINE_BALL -> R.string.equipment_medicine_ball
        Equipment.EXERCISE_BALL -> R.string.equipment_exercise_ball
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
