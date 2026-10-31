package io.github.kanylbullen.tung.ui.exercises

import org.jetbrains.compose.resources.stringResource
import io.github.kanylbullen.tung.ui.resources.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.Stat
import io.github.kanylbullen.tung.ui.common.StatRow
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.displayName
import io.github.kanylbullen.tung.ui.common.equipmentLabel
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.lastUsedText
import io.github.kanylbullen.tung.ui.common.localDate
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.common.muscleGroupLabel
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import kotlinx.coroutines.launch

/**
 * One exercise: name, equipment and group, the muscle map, the two actions,
 * the user's history (when it has a Room row) and the instructions. [ref] is
 * the route's `{ref}` (`core.catalog.ExercisePageRef.route`). After "Add to
 * workout" the page calls [onOpenWorkout] with the workout in progress;
 * "Progress" under the history calls [onOpenProgress] with the exercise's id.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseDetailScreen(
    ref: ExercisePageRef,
    onBack: () -> Unit,
    onOpenWorkout: (String) -> Unit,
    onOpenProgress: (String) -> Unit = {},
) {
    val container = appContainer()
    val appContext = LocalContext.current.applicationContext
    val vm: ExerciseDetailViewModel = viewModel(
        factory = factoryOf {
            ExerciseDetailViewModel(
                ref,
                container.exerciseRepository,
                container.workoutRepository,
                container.routineRepository,
                container.catalogueDetails,
                container.commands,
            ) { it.displayName(appContext) }
        }
    )
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(locale) { vm.setLocale(locale) }
    val state by vm.state.collectAsStateWithLifecycle()
    var choosingRoutine by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        containerColor = Tung.colors.background,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, shape = TungCorner, containerColor = Tung.colors.raised, contentColor = Tung.colors.text)
            }
        },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
                    }
                },
                title = { Text(state.name, maxLines = 1) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag(EXERCISE_PAGE_TAG)) {
            if (!state.loading && !state.found) {
                item(key = "not-found") { Muted(stringResource(R.string.exercise_not_found)) }
                return@LazyColumn
            }
            if (state.loading) return@LazyColumn
            item(key = "kind") {
                val kind = listOfNotNull(
                    state.equipment?.let { equipmentLabel(it) },
                    state.muscleGroup?.let { muscleGroupLabel(it) },
                    if (state.isCustom) stringResource(R.string.exercises_custom) else null,
                ).joinToString(stringResource(Res.string.row_separator))
                Muted(kind)
            }
            if (state.primary.isNotEmpty()) {
                item(key = "map") {
                    MuscleMap(
                        primary = state.primary.toSet(),
                        secondary = state.secondary.toSet(),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    )
                }
            }
            item(key = "actions") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.workoutInProgress != null) {
                        PrimaryButton(
                            text = stringResource(R.string.exercise_add_to_workout),
                            onClick = { vm.addToWorkout(onOpenWorkout) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    SecondaryButton(
                        text = stringResource(R.string.exercise_add_to_routine),
                        onClick = { choosingRoutine = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.inLibrary) {
                item(key = "history") { Heading(stringResource(R.string.exercise_history)) }
                val history = state.history
                if (history.isEmpty) {
                    item(key = "not-logged") { Muted(stringResource(R.string.exercise_not_logged)) }
                } else {
                    item(key = "stats") {
                        StatRow {
                            history.best?.let { best ->
                                Stat(
                                    stringResource(R.string.exercise_set_value, localWeight(best.weightKg), best.reps),
                                    stringResource(R.string.exercise_best_set),
                                    Modifier.weight(1f),
                                )
                            }
                            Stat(
                                history.sessions.toString(),
                                pluralStringResource(R.plurals.exercise_sessions, history.sessions),
                                Modifier.weight(1f),
                            )
                        }
                    }
                    history.lastSessionAt?.let { at ->
                        item(key = "last-time") { Muted(stringResource(R.string.exercise_last_time, localDate(at))) }
                    }
                    itemsIndexed(history.lastSession, key = { i, _ -> "last-$i" }) { i, set ->
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
                    item(key = "progress") {
                        val exerciseId = when (ref) {
                            is ExercisePageRef.Library -> ref.exerciseId
                            is ExercisePageRef.Extended -> ref.exerciseId
                        }
                        SecondaryButton(
                            text = stringResource(R.string.exercise_progress),
                            onClick = { onOpenProgress(exerciseId) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (state.instructions.isNotEmpty()) {
                item(key = "instructions") { Heading(stringResource(R.string.exercise_instructions)) }
                itemsIndexed(state.instructions, key = { i, _ -> "step-$i" }) { i, step ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(
                            (i + 1).toString(),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            color = Tung.colors.muted,
                            modifier = Modifier.width(32.dp),
                        )
                        Text(step, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.muted)
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (choosingRoutine) {
        RoutineChoiceSheet(
            routines = state.routines,
            now = container.clock.millis(),
            onDismiss = { choosingRoutine = false },
            onPick = { routine ->
                choosingRoutine = false
                vm.addToRoutine(routine.id) { added ->
                    if (added) {
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.exercise_added_to_routine, routine.name))
                        }
                    }
                }
            },
        )
    }
}

/** The semantics tag of the page's list, which the flow tests scroll. */
const val EXERCISE_PAGE_TAG = "exercise-page"

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Tung.colors.muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
    )
}

@Composable
private fun Muted(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Tung.colors.muted,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
}

/** The user's routines, most recently used first; a tap adds the exercise to that one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutineChoiceSheet(
    routines: List<RoutineSummaryRow>,
    now: Long,
    onDismiss: () -> Unit,
    onPick: (RoutineSummaryRow) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = TungCorner,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.exercise_choose_routine),
                style = MaterialTheme.typography.titleMedium,
                color = Tung.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (routines.isEmpty()) {
                Text(
                    stringResource(R.string.exercise_no_routines),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            routines.forEach { routine ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(routine) }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(routine.name, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text, modifier = Modifier.weight(1f))
                    Text(lastUsedText(routine.lastUsedAt, now), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                }
            }
        }
    }
}
