package io.github.kanylbullen.jarn.ui.history

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.jarn.R
import io.github.kanylbullen.jarn.ui.common.WorkoutRow
import io.github.kanylbullen.jarn.ui.common.appContainer
import io.github.kanylbullen.jarn.ui.common.factoryOf
import io.github.kanylbullen.jarn.ui.theme.Jarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpenDetail: (Long) -> Unit) {
    val container = appContainer()
    val vm: HistoryViewModel = viewModel(factory = factoryOf { HistoryViewModel(container.workoutRepository) })
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Jarn.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Jarn.colors.background,
                    scrolledContainerColor = Jarn.colors.background,
                ),
                title = { Text(stringResource(R.string.history_title)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (!state.loading && state.workouts.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Jarn.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            items(state.workouts, key = { it.id }) { row ->
                WorkoutRow(row, onClick = { onOpenDetail(row.id) })
            }
        }
    }
}
