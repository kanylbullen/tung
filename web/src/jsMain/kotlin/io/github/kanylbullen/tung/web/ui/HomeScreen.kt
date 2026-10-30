package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.progress.ThisWeek
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.home.HomeModel
import io.github.kanylbullen.tung.web.home.HomeRoutine
import io.github.kanylbullen.tung.web.home.homeModel
import io.github.kanylbullen.tung.web.sync.Account
import kotlin.js.Date
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/**
 * Home, as the phone's: the wordmark, "This week" (the week's volume as the
 * hero number, workouts and sets, last week or "Best of the last 12 weeks",
 * the seven days as bars) once there is a finished workout, the workout in
 * progress, the three most recently used routines, and the recent workouts.
 * Everything is computed from the store in memory by core's rules.
 */
@Composable
fun HomeScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val version = container.store.version.collectAsState().value
    val zone = remember { TimeZone.currentSystemDefault() }
    val model: HomeModel = remember(version) {
        homeModel(container.store, Clock.System.todayIn(zone), zone) { exerciseDisplayName(it, container.catalogueNames, strings.tag) }
    }
    val now = Date.now().toLong()
    val scope = rememberCoroutineScope()

    Div({ classes("screen") }) {
        TopBar(container, account) { Div({ classes("wordmark", "small") }) { Text(strings.appName) } }
        model.thisWeek?.let { ThisWeekSection(it, container) }
        val inProgress = model.inProgress
        if (inProgress != null) {
            A(href = Route.WORKOUT.hash, attrs = { classes("card", "card-link") }) {
                Div({ classes("card-title") }) { Text(inProgress.routineName ?: strings.workoutUntitled) }
                Div({ classes("details", "numbers") }) { Text(strings.inProgressFor(durationText(now - inProgress.startedAt, strings))) }
                P({ classes("hint") }) { Text(strings.inProgressHint) }
                Div({ classes("card-action") }) { Text(strings.resumeWorkout) }
            }
        } else {
            Button({
                classes("primary")
                onClick { scope.launch { if (container.run(Command.StartWorkout()) is CommandResult.Done) go(Route.WORKOUT) } }
            }) { Text(strings.startWorkout) }
        }
        H2({ classes("section") }) { Text(strings.routines) }
        if (model.routines.isEmpty()) {
            P({ classes("hint") }) { Text(strings.routinesEmpty) }
        } else {
            Ul({ classes("rows") }) {
                model.routines.forEach { routine ->
                    key(routine.id) {
                        Li({ classes("row") }) {
                            RoutineRow(routine, container)
                            Button({
                                classes("secondary", "start")
                                if (inProgress != null || routine.exerciseCount == 0) disabled()
                                onClick {
                                    scope.launch {
                                        if (container.run(Command.StartRoutine(routine.id)) is CommandResult.Done) go(Route.WORKOUT)
                                    }
                                }
                            }) { Text(strings.start) }
                        }
                    }
                }
            }
        }
        H2({ classes("section") }) { Text(strings.recent) }
        if (model.recent.isEmpty()) {
            P({ classes("hint") }) { Text(strings.homeEmpty) }
        } else {
            Ul({ classes("rows") }) {
                model.recent.forEach { row ->
                    key(row.id) { Li { A(href = workoutDetailHash(row.id), attrs = { classes("row", "row-link") }) { WorkoutRow(row, container, now) } } }
                }
            }
            A(href = "#/history", attrs = { classes("text-button", "show-all") }) { Text(strings.showAll) }
        }
    }
}

@Composable
private fun RoutineRow(routine: HomeRoutine, container: WebContainer) {
    Div({ classes("main") }) {
        Div({ classes("title") }) { RoutineTitle(routine.name) }
        Div({ classes("details", "numbers") }) { Text(container.strings.exerciseCount(routine.exerciseCount)) }
    }
}

/** "This week": the hero number, the counts, last week or the record line, and the days. */
@Composable
private fun ThisWeekSection(week: ThisWeek, container: WebContainer) {
    val strings = container.strings
    val locale = container.locale
    Div({ classes("this-week") }) {
        H2({ classes("section") }) { Text(strings.thisWeek) }
        Div({ classes("hero", "numbers") }) {
            Text(wholeNumber(week.volumeKg, locale))
            Span({ classes("unit") }) { Text(strings.kg) }
        }
        Div({ classes("details", "numbers") }) {
            Text(
                if (week.workouts == 0) {
                    strings.weekNone + strings.separator + strings.weekLast(wholeNumber(week.lastWeek.volumeKg, locale))
                } else {
                    strings.weekWorkouts(week.workouts) + strings.separator + strings.weekSets(week.sets)
                },
            )
        }
        if (week.bestOfRecentWeeks) Div({ classes("accent", "best") }) { Text(strings.weekBest) }
        WeekStrip(week, container)
    }
}

/** The seven days, Monday first: a trained day a bar in the accent scaled to the week's biggest day, an empty day a short mark, today outlined; one description for a screen reader. */
@Composable
private fun WeekStrip(week: ThisWeek, container: WebContainer) {
    val strings = container.strings
    val biggest = week.days.maxOf { it.volumeKg }.takeIf { it > 0.0 } ?: 1.0
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    val trained = week.days.filter { it.volumeKg > 0.0 }
    val description = strings.weekDaysDescription(
        if (trained.isEmpty()) strings.weekDaysNone
        else trained.joinToString(", ") { weekdayName(it.date, container.locale) + " " + wholeNumber(it.volumeKg, container.locale) + " " + strings.kg },
    )
    Div({ classes("week-strip"); attr("role", "img"); attr("aria-label", description) }) {
        week.days.forEach { day ->
            Div({ classes("day"); if (day.date == today) classes("today") }) {
                Div({ classes("bar-area") }) {
                    val height = if (day.volumeKg > 0.0) (12 + 52 * day.volumeKg / biggest).toInt() else 3
                    Div({
                        classes("bar")
                        if (day.volumeKg > 0.0) classes("trained")
                        style { property("height", "${height}px") }
                    })
                }
                Div({ classes("label"); attr("aria-hidden", "true") }) { Text(weekdayName(day.date, container.locale).take(1).uppercase()) }
            }
        }
    }
}
