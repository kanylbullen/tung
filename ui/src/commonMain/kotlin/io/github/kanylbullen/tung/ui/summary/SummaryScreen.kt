package io.github.kanylbullen.tung.ui.summary

import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.core.muscles.MuscleBreakdown
import io.github.kanylbullen.tung.core.muscles.MuscleWork
import io.github.kanylbullen.tung.core.progress.ProgressMetric
import io.github.kanylbullen.tung.core.progress.TREND_METRICS
import io.github.kanylbullen.tung.core.progress.TREND_RANGES
import io.github.kanylbullen.tung.ui.common.ExerciseReport
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.Stat
import io.github.kanylbullen.tung.ui.common.StatRow
import io.github.kanylbullen.tung.ui.common.durationText
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localVolume
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.common.muscleLabel
import io.github.kanylbullen.tung.ui.common.percentText
import io.github.kanylbullen.tung.ui.exercises.MuscleHeatMap
import io.github.kanylbullen.tung.ui.progress.ChoiceChips
import io.github.kanylbullen.tung.ui.progress.ProgressChart
import io.github.kanylbullen.tung.ui.progress.ProgressHeading
import io.github.kanylbullen.tung.ui.progress.ProgressMuted
import io.github.kanylbullen.tung.ui.progress.metricLabel
import io.github.kanylbullen.tung.ui.progress.metricText
import io.github.kanylbullen.tung.ui.progress.trendRangeLabel
import io.github.kanylbullen.tung.ui.progress.trendRangeLongLabel
import io.github.kanylbullen.tung.ui.routines.RoutineNameDialog
import io.github.kanylbullen.tung.ui.routines.defaultRoutineName
import io.github.kanylbullen.tung.ui.theme.Tung

/** The semantics tag of the summary's list, which the flow tests scroll. */
const val SUMMARY_LIST_TAG = "summary-list"

/**
 * The summary after Finish: duration, sets and volume; each exercise with
 * its sets, volume, best estimated 1RM and best set; the muscles the workout
 * worked, on the body and as percentages of its sets; and each exercise's
 * progress chart, this workout the last point, with the Progress tab's
 * metric and span. Done and Save as routine stay at the bottom. A chart's
 * exercise opens its progress ([onOpenProgress]); a saved routine is
 * reported to [onRoutineSaved] (the phone shows a toast).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(vm: SummaryViewModel, onDone: () -> Unit, onOpenProgress: (String) -> Unit, onRoutineSaved: (name: String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    var savingRoutine by rememberSaveable { mutableStateOf(false) }

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
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.action_close))
                    }
                },
                title = { Text(stringResource(Res.string.summary_title)) },
            )
        },
        bottomBar = {
            if (state.report != null) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Tung.colors.background)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecondaryButton(stringResource(Res.string.routine_save_as), onClick = { savingRoutine = true }, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(Res.string.summary_done), onClick = onDone, modifier = Modifier.weight(1f))
                }
            }
        },
    ) { padding ->
        val report = state.report
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag(SUMMARY_LIST_TAG)) {
            if (report == null) return@LazyColumn
            item(key = "stats") {
                StatRow {
                    Stat(durationText(report.durationMillis), stringResource(Res.string.stat_duration), Modifier.weight(1.3f))
                    Stat(report.setCount.toString(), stringResource(Res.string.stat_sets), Modifier.weight(0.7f))
                    Stat(localVolume(report.totalVolumeKg), stringResource(Res.string.stat_volume), Modifier.weight(1.2f))
                }
            }
            item(key = "exercises") { ProgressHeading(stringResource(Res.string.summary_exercises)) }
            items(report.exercises, key = { it.workoutExerciseId }) { exercise -> ExerciseRow(exercise) }

            val muscles = state.muscles
            if (!muscles.isEmpty || muscles.uncountedSets > 0) {
                item(key = "muscles") { ProgressHeading(stringResource(Res.string.summary_muscles)) }
            }
            if (!muscles.isEmpty) {
                item(key = "muscle-map") {
                    MuscleHeatMap(
                        intensity = muscles::intensity,
                        description = stringResource(
                            Res.string.summary_muscles_description,
                            muscles.muscles.map { muscleItem(it) }.joinToString(", "),
                        ),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        height = 180.dp,
                    )
                }
                items(muscles.muscles, key = { "m-${it.muscle.key}" }) { work -> MuscleRow(work, muscles) }
            }
            if (muscles.uncountedSets > 0) {
                item(key = "uncounted") {
                    ProgressMuted(
                        pluralStringResource(Res.plurals.summary_muscles_uncounted, muscles.uncountedSets, muscles.uncountedSets),
                        Modifier.padding(vertical = 4.dp),
                    )
                }
            }

            if (state.progress.isNotEmpty()) {
                item(key = "progress") { ProgressHeading(stringResource(Res.string.progress_title)) }
                item(key = "progress-metric") { ChoiceChips(TREND_METRICS, state.trendMetric, { metricLabel(it) }, vm::setTrendMetric) }
                item(key = "progress-range") { ChoiceChips(TREND_RANGES, state.trendRange, { trendRangeLabel(it) }, vm::setTrendRange) }
                items(state.progress, key = { "p-${it.exerciseId}" }) { exercise ->
                    val name = exerciseDisplayName(exercise.nameKey, exercise.name)
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Tung.colors.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClickLabel = stringResource(Res.string.progress_open_exercise, name)) { onOpenProgress(exercise.exerciseId) }
                            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                    )
                    ProgressChart(
                        exercise.sessions,
                        state.trendMetric,
                        state.trendRange,
                        state.today,
                        height = 140.dp,
                        span = trendRangeLongLabel(state.trendRange),
                    )
                }
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }

    val report = state.report
    if (savingRoutine && report != null) {
        RoutineNameDialog(
            title = stringResource(Res.string.routine_save_as),
            initialName = defaultRoutineName(report),
            confirmLabel = stringResource(Res.string.routine_save),
            onDismiss = { savingRoutine = false },
            onConfirm = { name ->
                savingRoutine = false
                vm.saveAsRoutine(name) { saved -> if (saved) onRoutineSaved(name) }
            },
        )
    }
}

/** An exercise: its name, "2 sets · 960 kg · Est. 1RM 112 kg", "New record" when it is one, and the best set on the right. */
@Composable
private fun ExerciseRow(exercise: ExerciseReport) {
    val details = listOfNotNull(
        pluralStringResource(Res.plurals.summary_sets, exercise.sets.size, exercise.sets.size),
        stringResource(Res.string.volume_kg, localVolume(exercise.volumeKg)),
        exercise.e1rmKg?.let { stringResource(Res.string.progress_e1rm_value, metricText(ProgressMetric.E1RM, it)) },
    ).joinToString(stringResource(Res.string.row_separator))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(exerciseDisplayName(exercise.nameKey, exercise.name), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(details, style = Tung.numbers.inline, color = Tung.colors.muted)
            if (exercise.isRecord) {
                Text(
                    stringResource(Res.string.summary_record),
                    style = MaterialTheme.typography.labelLarge,
                    color = Tung.colors.accentText,
                )
            }
        }
        exercise.best?.let { best ->
            Text(
                stringResource(Res.string.set_value, localWeight(best.weightKg), best.reps),
                style = Tung.numbers.set,
                color = if (exercise.isRecord) Tung.colors.accentText else Tung.colors.text,
            )
        }
    }
}

/** "Chest 50%", for the map's description. */
@Composable
private fun muscleItem(work: MuscleWork): String = stringResource(Res.string.summary_muscle_item, muscleLabel(work.muscle), percentText(work.percent))

/** A muscle: its name (capitalised: the labels are written for inside a sentence) and percentage, and under them a bar as long as its work against the most worked muscle's. */
@Composable
private fun MuscleRow(work: MuscleWork, breakdown: MuscleBreakdown) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                muscleLabel(work.muscle).replaceFirstChar { it.titlecase() },
                style = MaterialTheme.typography.bodyLarge,
                color = Tung.colors.text,
                modifier = Modifier.weight(1f),
            )
            Text(percentText(work.percent), style = Tung.numbers.inline, color = Tung.colors.text)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Tung.colors.border, RoundedCornerShape(2.dp))) {
            Box(
                Modifier
                    .fillMaxWidth(breakdown.intensity(work.muscle).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Tung.colors.accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}
