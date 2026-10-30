package io.github.kanylbullen.tung.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.WorkoutRow
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.durationText
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.common.muscleLabel
import io.github.kanylbullen.tung.ui.common.percentText
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.exercises.MuscleHeatMap
import io.github.kanylbullen.tung.ui.routines.RoutineRow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.ui.workout.DiscardWorkoutDialog
import java.time.LocalDate
import kotlinx.datetime.toKotlinLocalDate

@Composable
fun HomeScreen(
    onOpenWorkout: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onShowHistory: () -> Unit,
    onShowRoutines: () -> Unit,
    onOpenRoutine: (String) -> Unit = {},
    onShowSettings: () -> Unit,
    onShowAssistant: () -> Unit,
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
                container.restTimer,
                container.catalogueMuscles,
            )
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var choosingDay by rememberSaveable { mutableStateOf(false) }
    // "Last: 3 days ago" changes at most at midnight; a minute is plenty.
    val today = rememberNow(intervalMillis = 60_000L)
    val date = remember(today) { LocalDate.now(container.clock) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
    ) {
        item(key = "header") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The wordmark: small, wide and heavy. The screen's content is the week below it.
                    Text(
                        stringResource(R.string.app_name),
                        style = Tung.numbers.wordmark,
                        color = Tung.colors.text,
                        modifier = Modifier.weight(1f),
                    )
                    // Quiet on purpose: settings are visited once, training every day.
                    IconButton(onClick = onShowSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                            tint = Tung.colors.muted,
                        )
                    }
                }
                // "This week" once there is any history: the numbers are the content, so they come first.
                val week = state.week
                if (week != null && state.recent.isNotEmpty()) {
                    ThisWeekSection(week, date)
                }
                val active = state.activeWorkout
                if (active != null) {
                    var menuOpen by remember { mutableStateOf(false) }
                    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PrimaryButton(
                                text = stringResource(R.string.home_resume_workout),
                                onClick = { onOpenWorkout(active.id) },
                                modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                            )
                            // The same "Discard workout…" as the workout's own menu (issue #81):
                            // a workout started by mistake can be abandoned without opening it.
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = stringResource(R.string.home_workout_menu),
                                        tint = Tung.colors.muted,
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                    containerColor = Tung.colors.raised,
                                    shape = TungCorner,
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.workout_discard)) },
                                        onClick = { menuOpen = false; confirmDiscard = true },
                                    )
                                }
                            }
                        }
                        val now = rememberNow(intervalMillis = 15_000L)
                        Text(
                            stringResource(R.string.home_in_progress_since, durationText(now - active.startedAt)),
                            style = Tung.numbers.inline,
                            color = Tung.colors.muted,
                        )
                    }
                    if (confirmDiscard) {
                        DiscardWorkoutDialog(
                            onConfirm = { confirmDiscard = false; vm.discard(active.id) },
                            onDismiss = { confirmDiscard = false },
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
            // The few routines as one raised group: what is for today, apart from the archive below.
            item(key = "routines") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp)
                        .background(Tung.colors.raised, TungCorner)
                        .padding(bottom = 4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.routines_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Tung.colors.muted,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onShowRoutines, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.routines_all), color = Tung.colors.accentText)
                        }
                    }
                    state.routines.forEach { row ->
                        RoutineRow(
                            row = row,
                            now = today,
                            startEnabled = !state.loading && state.activeWorkout == null,
                            onStart = { vm.startRoutine(row.id, onOpenWorkout) },
                            // A tap opens the routine, so its exercises can be seen before Start.
                            onClick = { onOpenRoutine(row.id) },
                            compact = true,
                            muscles = state.routineMuscles[row.id],
                        )
                    }
                }
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

        // Last and quiet: the assistant is a second opinion, not the way to train.
        item(key = "assistant") {
            AssistantEntry(onClick = onShowAssistant)
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

/** The way to the in-app assistant: a plain row, like Programs on the Routines screen. */
/**
 * "This week": the week's volume as the hero number with its counts, the
 * muscles it worked as the small body, and the seven days as bars. The hero
 * block is one semantics node ("This week, 9 870 kg, 3 workouts · 41 sets").
 */
@Composable
private fun ThisWeekSection(week: WeekUi, today: LocalDate) {
    val totals = week.week
    val counts = listOf(
        pluralStringResource(R.plurals.home_week_workouts, totals.workouts, totals.workouts),
        pluralStringResource(R.plurals.home_week_sets, totals.sets, totals.sets),
    ).joinToString(stringResource(R.string.row_separator))
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {},
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.home_this_week), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                // A week is four digits most of the time; a big one must still fit beside the body
                // at the owner's font scale, so the hero shrinks with its length rather than ellipsise.
                val volumeText = localVolume(totals.volumeKg)
                val heroSize = when {
                    volumeText.length <= 4 -> 64.sp
                    volumeText.length <= 6 -> 52.sp
                    else -> 40.sp
                }
                Row {
                    Text(
                        volumeText,
                        style = Tung.numbers.hero.copy(fontSize = heroSize, lineHeight = heroSize),
                        color = Tung.colors.text,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.workout_unit_kg),
                        style = MaterialTheme.typography.titleLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
                Text(
                    if (totals.workouts == 0) stringResource(R.string.home_week_none) else counts,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tung.colors.muted,
                )
                // An empty week says what last week was, so "0 kg" has something to stand next to; its own
                // line, since with the counts' line it wrapped at a large font scale.
                val lastWeek = totals.lastWeek.takeIf { totals.workouts == 0 && it.workouts > 0 }
                if (lastWeek != null) {
                    Text(
                        stringResource(R.string.home_week_last, localVolume(lastWeek.volumeKg)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                    )
                }
                if (totals.bestOfRecentWeeks) {
                    Text(stringResource(R.string.home_week_best), style = MaterialTheme.typography.labelLarge, color = Tung.colors.accentText)
                }
            }
            if (!week.muscles.isEmpty) {
                Spacer(Modifier.width(12.dp))
                MuscleHeatMap(
                    intensity = week.muscles::intensity,
                    description = stringResource(
                        R.string.home_muscles_description,
                        week.muscles.muscles.map { stringResource(R.string.summary_muscle_item, muscleLabel(it.muscle), percentText(it.percent)) }
                            .joinToString(stringResource(R.string.list_separator)),
                    ),
                    height = 112.dp,
                )
            }
        }
        WeekStrip(totals.days, today.toKotlinLocalDate())
    }
}

@Composable
private fun AssistantEntry(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
        Text(stringResource(R.string.ai_entry_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
}
