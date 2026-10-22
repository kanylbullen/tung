package io.github.kanylbullen.tung.ui.progress

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.progress.ExerciseProgress
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.ProgressRange
import io.github.kanylbullen.tung.core.progress.RecentRecord
import io.github.kanylbullen.tung.core.progress.change
import io.github.kanylbullen.tung.core.progress.within
import io.github.kanylbullen.tung.data.Exercise
import io.github.kanylbullen.tung.ui.common.Stat
import io.github.kanylbullen.tung.ui.common.StatRow
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.charts.BarChart
import io.github.kanylbullen.tung.ui.common.charts.Sparkline
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.theme.Tung
import kotlinx.datetime.LocalDate

/** The semantics tag of the tab's list, which the flow tests scroll. */
const val PROGRESS_LIST_TAG = "progress-list"

/**
 * The Progress tab: the last 12 weeks (workouts and volume per week, the
 * weekly streak), every exercise with history, most recently trained first,
 * with a sparkline of its estimated 1RM and the change over 3 months, and the
 * latest records. A row opens [onOpenExercise] with the exercise's id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onOpenExercise: (String) -> Unit) {
    val container = appContainer()
    val vm: ProgressViewModel = viewModel(
        factory = factoryOf { ProgressViewModel(container.workoutRepository, container.exerciseRepository, container.clock) },
    )
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                title = { Text(stringResource(R.string.progress_title)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag(PROGRESS_LIST_TAG)) {
            if (state.loading) return@LazyColumn
            val overview = state.overview
            if (overview.isEmpty) {
                item(key = "empty") { ProgressMuted(stringResource(R.string.progress_empty), Modifier.padding(vertical = 12.dp)) }
                return@LazyColumn
            }
            item(key = "overview") { Overview(state) }
            item(key = "exercises") { ProgressHeading(stringResource(R.string.progress_exercises)) }
            items(overview.exercises, key = { "ex-${it.exerciseId}" }) { exercise ->
                ExerciseRow(exercise, state.exercises[exercise.exerciseId], state.today) { onOpenExercise(exercise.exerciseId) }
            }
            if (overview.recentRecords.isNotEmpty()) {
                item(key = "records") { ProgressHeading(stringResource(R.string.progress_records_recent)) }
                items(overview.recentRecords, key = { "pr-${it.exerciseId}-${it.record.workoutId}" }) { record ->
                    RecentRecordRow(record, state.exercises[record.exerciseId]) { onOpenExercise(record.exerciseId) }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Overview(state: ProgressUiState) {
    val weeks = state.overview.weeks
    val totalWorkouts = weeks.sumOf { it.workouts }
    val streak = state.overview.streak
    Column {
        ProgressHeading(stringResource(R.string.progress_overview))
        StatRow {
            Stat(totalWorkouts.toString(), stringResource(R.string.progress_workouts_total), Modifier.weight(1f))
            Stat(streak.toString(), pluralStringResource(R.plurals.progress_streak_label, streak), Modifier.weight(1f))
        }
        val first = weeks.firstOrNull()?.weekStart?.let { shortDate(it) }.orEmpty()
        val last = weeks.lastOrNull()?.weekStart?.let { shortDate(it) }.orEmpty()
        ProgressMuted(stringResource(R.string.progress_workouts_per_week), Modifier.padding(top = 12.dp))
        BarChart(
            values = weeks.map { it.workouts.toDouble() },
            summary = stringResource(R.string.progress_workouts_summary, totalWorkouts, weeks.lastOrNull()?.workouts ?: 0),
            firstLabel = first,
            lastLabel = last,
            yLabel = { it.toLong().toString() },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            height = 120.dp,
        )
        ProgressMuted(stringResource(R.string.progress_volume_per_week), Modifier.padding(top = 8.dp))
        BarChart(
            values = weeks.map { it.volumeKg },
            summary = stringResource(
                R.string.progress_volume_summary,
                localVolume(weeks.sumOf { it.volumeKg }),
                localVolume(weeks.lastOrNull()?.volumeKg ?: 0.0),
            ),
            firstLabel = first,
            lastLabel = last,
            yLabel = { localVolume(it) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            height = 120.dp,
        )
    }
}

/** What a row says about the last 3 months: "+7 kg in 3 months", "No change in 3 months", or how many sessions there are. */
@Composable
private fun changeText(exercise: ExerciseProgress, today: LocalDate): String {
    val recent = exercise.sessions.within(ProgressRange.THREE_MONTHS, today)
    val change = change(recent, ProgressMetric.E1RM)
        ?: return pluralStringResource(R.plurals.progress_sessions, exercise.sessions.size, exercise.sessions.size)
    val signed = signedChange(ProgressMetric.E1RM, change) ?: return stringResource(R.string.progress_no_change_3m)
    return stringResource(R.string.progress_change_3m, signed)
}

@Composable
private fun ExerciseRow(exercise: ExerciseProgress, row: Exercise?, today: LocalDate, onClick: () -> Unit) {
    val name = row?.let { exerciseDisplayName(it.nameKey, it.name) }.orEmpty()
    val latest = exercise.sessions.lastOrNull { it.e1rmKg != null }?.e1rmKg
    val details = listOfNotNull(
        latest?.let { stringResource(R.string.progress_e1rm_value, metricText(ProgressMetric.E1RM, it)) },
        changeText(exercise, today),
    ).joinToString(stringResource(R.string.row_separator))
    val spark = exercise.sessions.within(ProgressRange.THREE_MONTHS, today).mapNotNull { it.e1rmKg }
    val open = stringResource(R.string.progress_open_exercise, name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = open, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(details, style = Tung.numbers.inline, color = Tung.colors.muted)
        }
        if (spark.size >= 2) {
            Spacer(Modifier.width(12.dp))
            Sparkline(
                spark,
                stringResource(
                    R.string.progress_sparkline_summary,
                    metricText(ProgressMetric.E1RM, spark.first()),
                    metricText(ProgressMetric.E1RM, spark.last()),
                ),
            )
        }
    }
}

@Composable
private fun RecentRecordRow(recent: RecentRecord, row: Exercise?, onClick: () -> Unit) {
    val name = row?.let { exerciseDisplayName(it.nameKey, it.name) }.orEmpty()
    val record = recent.record
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(
                listOf(recordLabel(record.kind), mediumDate(record.date)).joinToString(stringResource(R.string.row_separator)),
                style = Tung.numbers.inline,
                color = Tung.colors.muted,
            )
        }
        Text(recordValue(record), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Tung.colors.accentText)
    }
}
