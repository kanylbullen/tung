package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.progress.ThisWeek
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.home.HomeModel
import io.github.kanylbullen.tung.web.home.HomeRoutine
import io.github.kanylbullen.tung.web.home.homeModel
import io.github.kanylbullen.tung.web.programs.ProgramDayView
import io.github.kanylbullen.tung.web.programs.TodayView
import io.github.kanylbullen.tung.web.programs.todayView
import io.github.kanylbullen.tung.web.routines.routineMuscles
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
    val catalogue = container.catalogue.collectAsState().value
    val routineMuscles = remember(version, catalogue) { routineMuscles(container.store, catalogue.muscles) }

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
        val today = remember(version) { todayView(container.store, Clock.System.todayIn(zone)) }
        if (today != null) TodayCard(container, today, busy = inProgress != null)
        H2({ classes("section") }) { Text(strings.routines) }
        if (model.routines.isEmpty()) {
            P({ classes("hint") }) { Text(strings.routinesEmpty) }
        } else {
            Ul({ classes("rows") }) {
                model.routines.forEach { routine ->
                    key(routine.id) {
                        Li({ classes("row") }) {
                            RoutineMuscles(routineMuscles[routine.id], strings)
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
        // The Assistant's row, last on Home as on the phone.
        Ul({ classes("rows") }) {
            Li {
                A(href = Route.ASSISTANT.hash, attrs = { classes("row", "row-link") }) {
                    Div({ classes("main") }) {
                        Div({ classes("title") }) { Span({ classes("plain") }) { Text(strings.assistant) } }
                        Div({ classes("details") }) { Text(strings.aiEntryHint) }
                    }
                }
            }
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

/**
 * The active program's day, the phone's "Today": "Today: Push" (fixed) or
 * "Day 2 of 3: Push" (rotating) with Start, "Rest day" with no Start, and
 * "Choose another" for any other day of the program. Starting records the
 * day, so a rotating program goes on from it; a day removed elsewhere
 * meanwhile starts nothing and says so.
 */
@Composable
private fun TodayCard(container: WebContainer, today: TodayView, busy: Boolean) {
    val strings = container.strings
    val scope = rememberCoroutineScope()
    var choosing by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(false) }
    fun start(day: ProgramDayView) {
        scope.launch {
            when (container.run(Command.StartRoutine(day.routineId, day.id))) {
                is CommandResult.Done -> go(Route.WORKOUT)
                else -> gone = true
            }
        }
    }
    fun title(day: ProgramDayView): String = when (today.mode) {
        ProgramMode.FIXED -> strings.todayFixed(day.routineName)
        ProgramMode.ROTATING -> dayLabel(day, today.mode, today.days.size, today.days.indexOf(day), container) + ": " + day.routineName
    }
    Div({ classes("card", "today") }) {
        Div({ classes("card-title") }) { Text(today.day?.let(::title) ?: strings.restDay) }
        P({ classes("hint") }) { Text(today.programName) }
        if (gone) P({ classes("hint", "accent"); attr("role", "status") }) { Text(strings.dayGone) }
        Div({ classes("action-row") }) {
            today.day?.let { day ->
                Button({
                    classes("accent-button")
                    attr("aria-label", strings.start + ", " + day.routineName)
                    if (busy) disabled()
                    onClick { start(day) }
                }) { Text(strings.start) }
            }
            Button({ classes("secondary"); if (busy) disabled(); onClick { choosing = true } }) { Text(strings.chooseAnother) }
        }
    }
    if (choosing) {
        Div({ classes("sheet-backdrop"); onClick { choosing = false } })
        Div({ classes("sheet"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.chooseDay) }) {
            Div({ classes("sheet-head") }) {
                H2 { Text(strings.chooseDay) }
                Button({ classes("text-button"); onClick { choosing = false } }) { Text(strings.close) }
            }
            Ul({ classes("picker") }) {
                today.days.forEachIndexed { index, day ->
                    key(day.id) {
                        Li {
                            Button({
                                classes("picker-row")
                                onClick {
                                    choosing = false
                                    start(day)
                                }
                            }) { Text(dayLabel(day, today.mode, today.days.size, index, container) + strings.separator + day.routineName) }
                        }
                    }
                }
            }
        }
    }
}
