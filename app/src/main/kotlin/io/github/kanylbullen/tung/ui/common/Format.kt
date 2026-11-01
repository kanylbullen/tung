package io.github.kanylbullen.tung.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.formatWeight
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun localWeight(kg: Double): String =
    formatWeight(kg, DecimalFormatSymbols.getInstance().decimalSeparator)

/** "6 March 2023": a date with its year, for ranges such as an import's. */
fun localDateWithYear(epochMillis: Long): String =
    DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()).format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

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
