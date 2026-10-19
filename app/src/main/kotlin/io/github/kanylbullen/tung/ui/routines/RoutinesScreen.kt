package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.layout.fillMaxSize
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

/** Every routine; tap one to edit it, or start it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    onBack: () -> Unit,
    onOpenRoutine: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
) {
    val container = appContainer()
    val vm: RoutinesViewModel = viewModel(
        factory = factoryOf { RoutinesViewModel(container.routineRepository, container.workoutRepository) }
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
