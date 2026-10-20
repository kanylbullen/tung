package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung

/** Every routine; tap one to edit it, or start it. Programs are one step further, from the row on top. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    onBack: () -> Unit,
    onOpenRoutine: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
    onShowPrograms: () -> Unit,
) {
    val container = appContainer()
    val vm: RoutinesViewModel = viewModel(
        factory = factoryOf {
            RoutinesViewModel(container.routineRepository, container.workoutRepository, container.programRepository, container.commands)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberNow(intervalMillis = 60_000L)

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
                title = { Text(stringResource(R.string.routines_title)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item(key = "programs") {
                ProgramsEntry(activeProgram = state.activeProgram, onClick = onShowPrograms)
            }
            if (!state.loading && state.routines.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.routines_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            items(state.routines, key = { it.id }) { row ->
                RoutineRow(
                    row = row,
                    now = now,
                    startEnabled = state.canStart,
                    onStart = { vm.start(row.id, onOpenWorkout) },
                    onClick = { onOpenRoutine(row.id) },
                )
            }
        }
    }
}

/** The way to Programs: a plain row with the active program's name, or what programs are for. */
@Composable
private fun ProgramsEntry(activeProgram: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.programs_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
        Text(
            activeProgram?.let { stringResource(R.string.programs_entry_active, it) }
                ?: stringResource(R.string.programs_entry_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Tung.colors.muted,
        )
    }
}
