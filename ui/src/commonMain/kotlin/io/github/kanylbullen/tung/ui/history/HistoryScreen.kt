package io.github.kanylbullen.tung.ui.history

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.ui.common.WorkoutRow
import io.github.kanylbullen.tung.ui.common.WorkoutTimeDialog
import io.github.kanylbullen.tung.ui.common.defaultPastStart
import io.github.kanylbullen.tung.ui.theme.Tung

/**
 * Finished workouts, newest first, and "Log past workout" in the top bar:
 * a date and a start, then the workout screen for a workout that started
 * then ([onOpenWorkout]). Beside a workout in progress it says to finish
 * that one first and offers to open it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(vm: HistoryViewModel, onOpenDetail: (String) -> Unit, onOpenWorkout: (String) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var logging by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                title = { Text(stringResource(Res.string.history_title)) },
                actions = {
                    if (!state.loading) {
                        IconButton(onClick = { logging = true }) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.past_log))
                        }
                    }
                },
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

    if (logging) {
        val active = state.activeWorkoutId
        if (active != null) {
            AlertDialog(
                onDismissRequest = { logging = false },
                containerColor = Tung.colors.raised,
                tonalElevation = 0.dp,
                title = { Text(stringResource(Res.string.past_log)) },
                text = { Text(stringResource(Res.string.past_busy), color = Tung.colors.muted) },
                confirmButton = {
                    TextButton(onClick = { logging = false; onOpenWorkout(active) }) {
                        Text(stringResource(Res.string.past_open_active), color = Tung.colors.accentText)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { logging = false }) {
                        Text(stringResource(Res.string.action_cancel), color = Tung.colors.text)
                    }
                },
            )
        } else {
            val now = vm.now()
            WorkoutTimeDialog(
                title = stringResource(Res.string.past_log),
                confirmLabel = stringResource(Res.string.past_log_confirm),
                initialStart = defaultPastStart(now),
                initialDurationMillis = null,
                now = now,
                onDismiss = { logging = false },
                onConfirm = { startedAt, _ ->
                    logging = false
                    vm.logPastWorkout(startedAt, onOpenWorkout)
                },
            )
        }
    }
}
