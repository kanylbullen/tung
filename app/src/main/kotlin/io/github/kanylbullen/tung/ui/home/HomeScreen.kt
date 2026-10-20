package io.github.kanylbullen.tung.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.WorkoutRow
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.durationText
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.routines.RoutineRow
import io.github.kanylbullen.tung.ui.theme.Tung

@Composable
fun HomeScreen(
    onOpenWorkout: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onShowHistory: () -> Unit,
    onShowRoutines: () -> Unit,
) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel(
        factory = factoryOf {
            HomeViewModel(
                container.workoutRepository,
                container.routineRepository,
                container.programRepository,
                container.commands,
                container.clock,
            )
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var choosingDay by rememberSaveable { mutableStateOf(false) }
    // "Last: 3 days ago" changes at most at midnight; a minute is plenty.
    val today = rememberNow(intervalMillis = 60_000L)

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    color = Tung.colors.text,
                )
                val active = state.activeWorkout
                if (active != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryButton(
                            text = stringResource(R.string.home_resume_workout),
                            onClick = { onOpenWorkout(active.id) },
                            modifier = Modifier.heightIn(min = 72.dp),
                        )
                        val now = rememberNow(intervalMillis = 15_000L)
                        Text(
                            stringResource(R.string.home_in_progress_since, durationText(now - active.startedAt)),
                            style = Tung.numbers.inline,
                            color = Tung.colors.muted,
                        )
                    }
                } else {
                    PrimaryButton(
                        text = stringResource(R.string.home_start_workout),
                        onClick = { vm.startOrResume(onOpenWorkout) },
                        enabled = !state.loading,
                        modifier = Modifier.heightIn(min = 72.dp),
                    )
                }
            }
        }

        state.today?.let { program ->
            item(key = "today") {
                TodaySection(
                    today = program,
                    startEnabled = !state.loading && state.activeWorkout == null,
                    onStart = { day -> vm.startDay(day, onOpenWorkout) },
                    onChooseAnother = { choosingDay = true },
                )
            }
        }

        if (state.routines.isNotEmpty()) {
            item(key = "routines-title") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.routines_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = Tung.colors.muted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onShowRoutines, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.routines_all), color = Tung.colors.text)
                    }
                }
            }
            items(state.routines, key = { "routine-${it.id}" }) { row ->
                RoutineRow(
                    row = row,
                    now = today,
                    startEnabled = !state.loading && state.activeWorkout == null,
                    onStart = { vm.startRoutine(row.id, onOpenWorkout) },
                )
            }
        }

        // "No workouts yet" would contradict "Resume workout" above it; the
        // in-progress line under the button says enough until the first one is finished.
        if (!state.loading && state.recent.isEmpty() && state.activeWorkout == null) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        } else if (state.recent.isNotEmpty()) {
            item(key = "recent-title") {
                Text(
                    stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleMedium,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 4.dp),
                )
            }
            items(state.recent, key = { it.id }) { row ->
                WorkoutRow(row, onClick = { onOpenDetail(row.id) })
            }
            if (state.hasMore) {
                item(key = "more") {
                    TextButton(
                        onClick = onShowHistory,
                        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.home_show_all), color = Tung.colors.text)
                    }
                }
            }
        }
    }

    val program = state.today
    if (choosingDay && program != null) {
        ChooseDaySheet(
            today = program,
            onDismiss = { choosingDay = false },
            onChoose = { day ->
                choosingDay = false
                vm.startDay(day, onOpenWorkout)
            },
        )
    }
}
