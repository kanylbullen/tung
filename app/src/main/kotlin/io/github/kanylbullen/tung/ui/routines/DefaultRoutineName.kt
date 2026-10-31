package io.github.kanylbullen.tung.ui.routines

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.ExerciseNames
import io.github.kanylbullen.tung.ui.common.WorkoutReport
import io.github.kanylbullen.tung.ui.common.localWeekday

/**
 * The name a routine saved from [report] is offered: the weekday and the first
 * two exercises, "Monday: Bench press, Barbell row".
 */
@Composable
fun defaultRoutineName(report: WorkoutReport): String {
    val context = LocalContext.current
    val weekday = localWeekday(report.workout.startedAt)
    val names = report.exercises.take(2).joinToString(stringResource(Res.string.list_separator)) {
        ExerciseNames.resolve(context, it.nameKey, it.name)
    }
    return if (names.isEmpty()) weekday else stringResource(R.string.routine_default_name, weekday, names)
}
