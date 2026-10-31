package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.command.Command
import io.github.kanylbullen.tung.core.command.CommandResult
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.TREND_METRICS
import io.github.kanylbullen.tung.core.progress.TREND_RANGES
import io.github.kanylbullen.tung.core.progress.trendPoints
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.progress.exerciseHistory
import io.github.kanylbullen.tung.web.summary.workoutSummary
import kotlinx.coroutines.launch
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

/** `#/summary/<id>`: the screen after Finish. */
fun summaryHash(id: String): String = "#/summary/$id"

/**
 * The phone's workout summary: the stats, each exercise with its sets and
 * "New record", the muscles worked (core's sets per muscle on the body,
 * from 30 % of the accent for the least worked to full for the most, and a
 * row per muscle), and a chart per exercise with the compact trends'
 * choices. Save as routine and Done stay at the bottom.
 */
@Composable
fun SummaryScreen(container: WebContainer, id: String) {
    val strings = container.strings
    val locale = container.locale
    val version = container.store.version.collectAsState().value
    val catalogue = container.catalogue.collectAsState().value
    val zone = remember { TimeZone.currentSystemDefault() }
    val today = Clock.System.todayIn(zone)
    val displayName = { e: io.github.kanylbullen.tung.core.sync.SyncExercise -> exerciseDisplayName(e, container.catalogueNames, strings.tag) }
    val summary = remember(version, catalogue, id) { workoutSummary(container.store, id, catalogue.muscles, displayName) }
    var metric by remember { mutableStateOf(ProgressMetric.E1RM) }
    var range by remember { mutableStateOf(ProgressRange.THREE_MONTHS) }
    val scope = rememberCoroutineScope()
    var naming by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }

    Div({ classes("screen", "with-actions") }) {
        Div({ classes("topbar") }) { H1 { Text(strings.summaryTitle) } }
        if (summary == null) {
            P({ classes("empty") }) { Text(strings.notFound) }
            return@Div
        }
        val detail = summary.detail
        P({ classes("detail-date") }) {
            Text(listOfNotNull(detail.title, longDate(detail.startedAt, locale)).joinToString(strings.separator))
        }
        Div({ classes("stats") }) {
            Stat(durationText(detail.durationMillis, strings), strings.duration)
            Stat(detail.sets.toString(), strings.setsLabel)
            Stat(wholeNumber(detail.volumeKg, locale), strings.volumeKg)
        }

        H2({ classes("section") }) { Text(strings.exercises) }
        detail.exercises.forEachIndexed { index, exercise ->
            key(index) { DetailExerciseBlock(container, exercise, record = summary.records[index]) }
        }

        val muscles = summary.muscles
        if (!muscles.isEmpty || muscles.uncountedSets > 0) {
            H2({ classes("section") }) { Text(strings.musclesWorked) }
        }
        if (!muscles.isEmpty) {
            fun percent(p: Int) = if (p == 0) "<1%" else "$p%"
            MuscleMap(strings.musclesWorkedDescription(muscles.muscles.joinToString(", ") { strings.muscleName(it.muscle) + " " + percent(it.percent) })) { muscle ->
                muscles.intensity(muscle).takeIf { it > 0.0 }?.let { 0.3 + 0.7 * it }
            }
            Ul({ classes("muscle-rows") }) {
                muscles.muscles.forEach { work ->
                    Li({ classes("muscle-row") }) {
                        Span({ classes("muscle-name") }) { Text(strings.muscleName(work.muscle).replaceFirstChar { it.uppercase() }) }
                        Span({ classes("muscle-bar") }) {
                            Span({ attr("style", "width: ${(muscles.intensity(work.muscle) * 100).coerceIn(2.0, 100.0)}%") })
                        }
                        Span({ classes("numbers", "muted") }) { Text(percent(work.percent)) }
                    }
                }
            }
        }
        if (muscles.uncountedSets > 0) P({ classes("hint") }) { Text(strings.uncountedSets(muscles.uncountedSets)) }

        if (summary.chartExerciseIds.isNotEmpty()) {
            H2({ classes("section") }) { Text(strings.progress) }
            Chips(TREND_METRICS, metric, strings::metricLabel) { metric = it }
            Chips(TREND_RANGES, range, strings::rangeShort) { range = it }
            summary.chartExerciseIds.forEach { exerciseId ->
                key(exerciseId) {
                    val name = container.store.exercises.get(exerciseId)?.let(displayName) ?: exerciseId
                    val points = remember(version, exerciseId, metric, range) {
                        trendPoints(exerciseHistory(container.store, exerciseId, zone).sessions, metric, range, today)
                    }
                    Div({ classes("pad", "summary-chart") }) {
                        A(href = progressHash(exerciseId), attrs = { classes("chart-title") }) { Text(name) }
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
                }
            }
        }
        saved?.let { P({ classes("hint", "accent"); attr("role", "status") }) { Text(strings.savedAsRoutine(it)) } }
    }

    if (summary != null) {
        Div({ classes("bottom-actions") }) {
            Button({ classes("secondary"); onClick { naming = true } }) { Text(strings.saveAsRoutine) }
            Button({ classes("accent-button"); onClick { go(Route.HOME) } }) { Text(strings.done) }
        }
    }
    if (summary != null && naming) {
        NameDialog(strings.saveAsRoutine, strings.routineName, summary.detail.title ?: "", strings.save, strings.cancel, onCancel = { naming = false }) { name ->
            naming = false
            scope.launch {
                if (container.commands.execute(Command.SaveRoutine(summary.detail.id, name)) is CommandResult.Done) saved = name
            }
        }
    }
}
