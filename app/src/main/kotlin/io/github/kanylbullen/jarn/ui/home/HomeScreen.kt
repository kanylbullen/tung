package io.github.kanylbullen.jarn.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.jarn.R
import io.github.kanylbullen.jarn.ui.common.PrimaryButton
import io.github.kanylbullen.jarn.ui.common.WorkoutRow
import io.github.kanylbullen.jarn.ui.common.appContainer
import io.github.kanylbullen.jarn.ui.common.durationText
import io.github.kanylbullen.jarn.ui.common.factoryOf
import io.github.kanylbullen.jarn.ui.common.rememberNow
import io.github.kanylbullen.jarn.ui.theme.Jarn

@Composable
fun HomeScreen(
    onOpenWorkout: (Long) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onShowHistory: () -> Unit,
) {
    val container = appContainer()
    val vm: HomeViewModel = viewModel(factory = factoryOf { HomeViewModel(container.workoutRepository) })
    val state by vm.state.collectAsStateWithLifecycle()

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
                    color = Jarn.colors.text,
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
                            style = Jarn.numbers.inline,
                            color = Jarn.colors.muted,
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

        if (!state.loading && state.recent.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Jarn.colors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                )
            }
        } else if (state.recent.isNotEmpty()) {
            item(key = "recent-title") {
                Text(
                    stringResource(R.string.home_recent),
                    style = MaterialTheme.typography.titleMedium,
                    color = Jarn.colors.muted,
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
                        Text(stringResource(R.string.home_show_all), color = Jarn.colors.text)
                    }
                }
            }
        }
    }
}
