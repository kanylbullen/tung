package io.github.kanylbullen.tung.ui.progress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.progress.PersonalRecord
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.SessionPoint
import io.github.kanylbullen.tung.core.progress.within
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.charts.LineChart
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.theme.Tung
import java.time.LocalDate

/** The semantics tag of the exercise progress list, which the flow tests scroll. */
const val EXERCISE_PROGRESS_TAG = "exercise-progress"

/** Sessions the exercise progress screen lists under "Last sessions". */
private const val LAST_SESSIONS = 5

@Composable
internal fun exerciseProgressViewModel(exerciseId: String): ExerciseProgressViewModel {
    val container = appContainer()
    return viewModel(
        key = "progress-$exerciseId",
        factory = factoryOf {
            ExerciseProgressViewModel(exerciseId, container.workoutRepository, container.exerciseRepository, container.clock)
        },
    )
}

/**
 * One exercise's progress (route `progress/{exerciseId}`): a chart with a
 * metric (estimated 1RM, top set, session volume) and a range (3 months, 1
 * year, all), the records with their dates, and the last sessions' sets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseProgressScreen(exerciseId: String, onBack: () -> Unit) {
    val vm = exerciseProgressViewModel(exerciseId)
    val state by vm.state.collectAsStateWithLifecycle()
    val name = state.exercise?.let { exerciseDisplayName(it.nameKey, it.name) }.orEmpty()

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(name, maxLines = 1) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag(EXERCISE_PROGRESS_TAG)) {
            if (state.loading) return@LazyColumn
            item(key = "metric") {
                ChoiceChips(ProgressMetric.entries, state.metric, { metricLabel(it) }, vm::setMetric)
            }
            item(key = "chart") {
                ProgressChart(state.sessions, state.metric, state.range, state.today, height = 220.dp)
            }
            item(key = "range") {
                ChoiceChips(ProgressRange.entries, state.range, { rangeLabel(it) }, vm::setRange)
            }
            val records = state.records.all
            if (records.isNotEmpty()) {
                item(key = "records") { ProgressHeading(stringResource(R.string.progress_records)) }
                items(records, key = { "pr-${it.kind}" }) { RecordRow(it) }
            }
            if (state.sessions.isNotEmpty()) {
                item(key = "sessions") { ProgressHeading(stringResource(R.string.progress_last_sessions)) }
                items(state.sessions.takeLast(LAST_SESSIONS).reversed(), key = { "s-${it.workoutId}" }) { session ->
                    SessionLine(mediumDate(session.date), session.sets)
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/**
 * [metric] over [range] of [sessions] as a line chart, or a muted line saying
 * two sessions are needed; either way the summary describes it.
 */
@Composable
internal fun ProgressChart(
    sessions: List<SessionPoint>,
    metric: ProgressMetric,
    range: ProgressRange,
    today: LocalDate,
    height: Dp,
) {
    val points = chartPoints(sessions.within(range, today), metric)
    val summary = chartSummary(metric, range, points)
    if (points.size < 2) {
        // One node, like a drawn chart: the summary says why there is none.
        Column(
            Modifier.fillMaxWidth().heightIn(min = height / 2).clearAndSetSemantics { contentDescription = summary },
            verticalArrangement = Arrangement.Center,
        ) {
            ProgressMuted(stringResource(R.string.progress_chart_empty))
        }
        return
    }
    LineChart(
        points = points,
        summary = summary,
        xLabel = { shortDate(LocalDate.ofEpochDay(it)) },
        yLabel = { metricText(metric, it) },
        modifier = Modifier.padding(start = 12.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        height = height,
    )
}

@Composable
private fun RecordRow(record: PersonalRecord) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(recordLabel(record.kind), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(mediumDate(record.date), style = Tung.numbers.inline, color = Tung.colors.muted)
        }
        Text(recordValue(record), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Tung.colors.text)
    }
}
