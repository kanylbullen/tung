package io.github.kanylbullen.tung.ui.routines

import androidx.compose.runtime.Composable
import io.github.kanylbullen.tung.ui.common.WorkoutReport
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localWeekday
import io.github.kanylbullen.tung.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * The name a routine saved from [report] is offered: the weekday and the first
 * two exercises, "Monday: Bench press, Barbell row".
 */
@Composable
fun defaultRoutineName(report: WorkoutReport): String {
    val weekday = localWeekday(report.workout.startedAt)
    val names = report.exercises.take(2)
        .map { exerciseDisplayName(it.nameKey, it.name) }
        .joinToString(stringResource(Res.string.list_separator))
    return if (names.isEmpty()) weekday else stringResource(Res.string.routine_default_name, weekday, names)
}
