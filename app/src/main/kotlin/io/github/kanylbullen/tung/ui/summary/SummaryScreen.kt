package io.github.kanylbullen.tung.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.ExerciseReport
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.Stat
import io.github.kanylbullen.tung.ui.common.StatRow
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.durationText
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.theme.Tung

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(workoutId: Long, onDone: () -> Unit) {
    val container = appContainer()
    val vm: SummaryViewModel = viewModel(factory = factoryOf { SummaryViewModel(workoutId, container.workoutRepository) })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
                title = { Text(stringResource(R.string.summary_title)) },
            )
        },
    ) { padding ->
        val report = state.report
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (report != null) {
                item(key = "stats") {
                    StatRow {
                        Stat(durationText(report.durationMillis), stringResource(R.string.stat_duration), Modifier.weight(1.3f))
                        Stat(report.setCount.toString(), stringResource(R.string.stat_sets), Modifier.weight(0.7f))
                        Stat(localVolume(report.totalVolumeKg), stringResource(R.string.stat_volume), Modifier.weight(1.2f))
                    }
                }
                item(key = "best-title") {
                    Text(
                        stringResource(R.string.summary_best_set),
                        style = MaterialTheme.typography.titleMedium,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp),
                    )
                }
                items(report.exercises, key = { it.workoutExerciseId }) { exercise ->
                    BestSetRow(exercise)
                }
                item(key = "done") {
                    Box(Modifier.padding(20.dp)) {
                        PrimaryButton(text = stringResource(R.string.summary_done), onClick = onDone)
                    }
                }
            }
        }
    }
}

@Composable
private fun BestSetRow(exercise: ExerciseReport) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(exerciseDisplayName(exercise.nameKey, exercise.name), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            if (exercise.isRecord) {
                Text(
                    stringResource(R.string.summary_record),
                    style = MaterialTheme.typography.labelLarge,
                    color = Tung.colors.accentText,
                )
            }
        }
        exercise.best?.let { best ->
            Text(
                stringResource(R.string.set_value, localWeight(best.weightKg), best.reps),
                style = Tung.numbers.set,
                color = if (exercise.isRecord) Tung.colors.accentText else Tung.colors.text,
            )
        }
    }
}
