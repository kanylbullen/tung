package io.github.kanylbullen.tung.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.Stat
import io.github.kanylbullen.tung.ui.common.StatRow
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.durationText
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(workoutId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: WorkoutDetailViewModel = viewModel(
        factory = factoryOf { WorkoutDetailViewModel(workoutId, container.workoutRepository) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

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
                title = { state.report?.let { Text(localDate(it.workout.startedAt)) } },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = Tung.colors.raised,
                            shape = TungCorner,
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.detail_delete)) },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        val report = state.report
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (report != null && !deleting) {
                item(key = "stats") {
                    StatRow {
                        Stat(durationText(report.durationMillis), stringResource(R.string.stat_duration), Modifier.weight(1f))
                        Stat(localVolume(report.totalVolumeKg), stringResource(R.string.stat_volume), Modifier.weight(1f))
                    }
                }
                report.exercises.forEach { exercise ->
                    item(key = "we-${exercise.workoutExerciseId}") {
                        Text(
                            exerciseDisplayName(exercise.nameKey, exercise.name),
                            style = MaterialTheme.typography.titleLarge,
                            color = Tung.colors.text,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(exercise.sets, key = { i, _ -> "s-${exercise.workoutExerciseId}-$i" }) { i, set ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                            Text(
                                (i + 1).toString(),
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = Tung.colors.muted,
                                modifier = Modifier.width(32.dp),
                            )
                            Text(
                                stringResource(R.string.detail_set_line, localWeight(set.weightKg), set.reps),
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                color = Tung.colors.text,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = { Text(stringResource(R.string.detail_delete_body), color = Tung.colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    vm.delete(onBack)
                }) {
                    Text(stringResource(R.string.detail_delete_confirm), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = Tung.colors.text)
                }
            },
        )
    }
}
