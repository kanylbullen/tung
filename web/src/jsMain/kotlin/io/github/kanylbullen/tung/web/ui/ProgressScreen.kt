package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.progress.PersonalRecord
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.RecordKind
import io.github.kanylbullen.tung.core.progress.trend
import io.github.kanylbullen.tung.core.progress.trendPoints
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.progress.exerciseHistory
import io.github.kanylbullen.tung.web.progress.overview
import io.github.kanylbullen.tung.web.sync.Account
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

/** `#/progress/<exerciseId>`: one exercise's progress. */
fun progressHash(exerciseId: String): String = "#/progress/$exerciseId"

/**
 * The Progress tab, the phone's: the last twelve weeks (workouts, the
 * streak, volume per week as bars), every exercise trained with its
 * estimated 1RM over three months and a sparkline, and the recent records.
 * All of it is core's progress maths over the store.
 */
@Composable
fun ProgressScreen(container: WebContainer, account: Account) {
    val strings = container.strings
    val locale = container.locale
    val version = container.store.version.collectAsState().value
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val model = remember(version) { overview(container.store, today, zone) }
    fun name(id: String) = container.store.exercises.get(id)?.let { exerciseDisplayName(it, container.catalogueNames, strings.tag) } ?: id

    Div({ classes("screen") }) {
        TopBar(container, account) { H1 { Text(strings.progress) } }
        if (model.isEmpty) {
            P({ classes("empty") }) { Text(strings.progressEmpty) }
            return@Div
        }
        H2({ classes("section") }) { Text(strings.last12Weeks) }
        Div({ classes("details", "numbers", "pad") }) {
            Text(strings.weekWorkouts(model.weeks.sumOf { it.workouts }) + strings.separator + strings.streak(model.streak))
        }
        Div({ classes("pad") }) {
            BarChart(model.weeks.map { it.volumeKg }, strings.weeksSummary(model.weeks.joinToString(", ") { wholeNumber(it.volumeKg, locale) }))
        }

        H2({ classes("section") }) { Text(strings.exercises) }
        Ul({ classes("rows") }) {
            model.exercises.sortedByDescending { it.lastAt }.forEach { exercise ->
                key(exercise.exerciseId) {
                    val range = ProgressRange.THREE_MONTHS
                    val trend = exercise.trend(ProgressMetric.E1RM, range, today)
                    val line = buildString {
                        append(strings.metricLabel(ProgressMetric.E1RM))
                        trend.latest?.let { append(" ").append(wholeNumber(it, locale)).append(" kg") }
                        append(strings.separator)
                        val change = trend.change
                        append(
                            when {
                                trend.values.size < 2 -> strings.sessionsIn(trend.values.size, strings.rangeLong(range))
                                change == null || kotlin.math.round(change.delta) == 0.0 -> strings.noChangeIn(strings.rangeLong(range))
                                else -> strings.changeIn(signedWhole(change.delta, locale) + " kg", strings.rangeLong(range))
                            },
                        )
                    }
                    Li {
                        A(href = progressHash(exercise.exerciseId), attrs = { classes("row", "row-link") }) {
                            Div({ classes("main") }) {
                                Div({ classes("title") }) { Span({ classes("plain") }) { Text(name(exercise.exerciseId)) } }
                                Div({ classes("details", "numbers") }) { Text(line) }
                            }
                            Sparkline(trend.values, line)
                        }
                    }
                }
            }
        }

        if (model.recentRecords.isNotEmpty()) {
            H2({ classes("section") }) { Text(strings.recentRecords) }
            Ul({ classes("rows") }) {
                model.recentRecords.forEach { recent ->
                    Li({ classes("row") }) {
                        Div({ classes("main") }) {
                            Div({ classes("title") }) { Span({ classes("plain") }) { Text(name(recent.exerciseId)) } }
                            Div({ classes("details") }) { Text(strings.recordLabel(recent.record.kind) + strings.separator + shortMonthDay(recent.record.date, locale)) }
                        }
                        Div({ classes("volume", "numbers") }) { Text(recordValue(recent.record, container)) }
                    }
                }
            }
        }
    }
}

/** A record's number as written: kilos, reps, or a set. */
private fun recordValue(record: PersonalRecord, container: WebContainer): String {
    val locale = container.locale
    return when (record.kind) {
        RecordKind.MOST_REPS -> record.set?.let { weightText(it.weightKg, locale) + " × " + it.reps } ?: record.value.toInt().toString()
        RecordKind.HEAVIEST_WEIGHT -> record.set?.let { weightText(it.weightKg, locale) + " × " + it.reps } ?: (weightText(record.value, locale) + " kg")
        else -> wholeNumber(record.value, locale) + " kg"
    }
}

/**
 * One exercise's progress: the metric (Est. 1RM, Top set, Session volume,
 * Weekly volume) and the range (3M, 1Y, All) as chips, a line chart, the
 * records with their dates, and the last five sessions' sets. Its choices
 * start at estimated 1RM over three months, as on the phone.
 */
@Composable
fun ExerciseProgressScreen(container: WebContainer, exerciseId: String) {
    val strings = container.strings
    val locale = container.locale
    val version = container.store.version.collectAsState().value
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val history = remember(version, exerciseId) { exerciseHistory(container.store, exerciseId, zone) }
    var metric by remember { mutableStateOf(ProgressMetric.E1RM) }
    var range by remember { mutableStateOf(ProgressRange.THREE_MONTHS) }
    val name = container.store.exercises.get(exerciseId)?.let { exerciseDisplayName(it, container.catalogueNames, strings.tag) } ?: exerciseId

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.PROGRESS.hash, attrs = { classes("text-button"); attr("aria-label", strings.progress) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(name) }
        }
        Chips(ProgressMetric.entries, metric, strings::metricLabel) { metric = it }
        val points = trendPoints(history.sessions, metric, range, today)
        Div({ classes("pad") }) {
            if (points.size < 2) {
                P({ classes("hint") }) { Text(strings.chartEmpty) }
            } else {
                LineChart(
                    points = points,
                    summary = strings.chartSummary(strings.metricLabel(metric), strings.rangeLong(range), wholeNumber(points.first().value, locale), wholeNumber(points.last().value, locale)),
                    yLabel = { wholeNumber(it, locale) },
                    dateLabel = { shortMonthDay(it.date, locale) },
                )
            }
        }
        Chips(ProgressRange.entries, range, strings::rangeShort) { range = it }

        if (!history.records.isEmpty) {
            H2({ classes("section") }) { Text(strings.records) }
            Ul({ classes("rows") }) {
                history.records.all.forEach { record ->
                    Li({ classes("row") }) {
                        Div({ classes("main") }) {
                            Div({ classes("title") }) { Span({ classes("plain") }) { Text(strings.recordLabel(record.kind)) } }
                            Div({ classes("details") }) { Text(shortMonthDay(record.date, locale)) }
                        }
                        Div({ classes("volume", "numbers") }) { Text(recordValue(record, container)) }
                    }
                }
            }
        }
        if (history.sessions.isNotEmpty()) {
            H2({ classes("section") }) { Text(strings.lastSessions) }
            Ul({ classes("rows") }) {
                history.sessions.takeLast(5).reversed().forEach { session ->
                    Li({ classes("row") }) {
                        Div({ classes("main") }) {
                            Div({ classes("title") }) { Span({ classes("plain") }) { Text(shortMonthDay(session.date, locale)) } }
                            Div({ classes("details", "numbers") }) { Text(setsText(session.sets, locale)) }
                        }
                    }
                }
            }
        }
    }
}

/** One line of choices, the chosen one in the accent; the phone's `ChoiceChips`. */
@Composable
private fun <T> Chips(options: List<T>, chosen: T, label: (T) -> String, onChoose: (T) -> Unit) {
    Div({ classes("chips"); attr("role", "radiogroup") }) {
        options.forEach { option ->
            Button({
                classes("chip")
                if (option == chosen) classes("on")
                attr("role", "radio")
                attr("aria-checked", (option == chosen).toString())
                onClick { onChoose(option) }
            }) { Text(label(option)) }
        }
    }
}
