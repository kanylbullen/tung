package io.github.kanylbullen.tung.ui.history

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.ui.common.WorkoutRow
import io.github.kanylbullen.tung.ui.theme.Tung

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpenDetail: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                title = { Text(stringResource(Res.string.history_title)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (!state.loading && state.workouts.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(Res.string.history_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
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
