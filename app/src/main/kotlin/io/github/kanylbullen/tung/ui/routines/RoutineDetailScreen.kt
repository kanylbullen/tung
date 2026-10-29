package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.MAX_TARGET_SETS
import io.github.kanylbullen.tung.core.MIN_TARGET_SETS
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import io.github.kanylbullen.tung.ui.workout.NumberInputSheet
import io.github.kanylbullen.tung.ui.workout.repsSpec

/**
 * One routine: start it ([onStart] gets the workout, issue #81), rename or
 * delete it; add exercises ([onAddExercise] opens the picker), remove and
 * reorder them, and change each one's target sets and reps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineDetailScreen(
    routineId: String,
    onBack: () -> Unit,
    onAddExercise: () -> Unit,
    onStart: (workoutId: String) -> Unit = {},
) {
    val container = appContainer()
    val vm: RoutineDetailViewModel = viewModel(
        factory = factoryOf {
            RoutineDetailViewModel(routineId, container.routineRepository, container.workoutRepository, container.commands)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    /** The routine exercise whose target reps the sheet is editing. */
    var editingReps by rememberSaveable { mutableStateOf<String?>(null) }

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
                title = { if (!state.missing) Text(state.name) },
                actions = {
                    if (!state.loading && !state.missing) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = Tung.colors.raised,
                                shape = TungCorner,
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.routine_rename)) },
                                    onClick = { menuOpen = false; renaming = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.routine_delete)) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            if (state.missing && !deleting) {
                item(key = "missing") {
                    Text(
                        stringResource(R.string.routine_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            if (!state.loading && !state.missing && !deleting) {
                item(key = "start") {
                    // Start, like the row's on Home and the Routines tab: disabled while a
                    // workout is in progress, and for a routine with nothing to start.
                    val description = stringResource(R.string.routine_start_description, state.name)
                    PrimaryButton(
                        text = stringResource(R.string.routine_start),
                        onClick = { vm.start(onStart) },
                        enabled = state.canStart && state.exercises.isNotEmpty(),
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                            .semantics { contentDescription = description },
                    )
                }
            }
            if (!deleting) {
                itemsIndexed(state.exercises, key = { _, e -> e.id }) { index, exercise ->
                    RoutineExerciseRow(
                        exercise = exercise,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.exercises.lastIndex,
                        onMove = { delta -> vm.move(exercise.id, delta) },
                        onChangeSets = { delta -> vm.changeSets(exercise.id, delta) },
                        onEditReps = { editingReps = exercise.id },
                        onClearReps = { vm.setTargetReps(exercise.id, null) },
                        onRemove = { vm.remove(exercise.id) },
                    )
                }
            }
            if (!state.loading && !state.missing && !deleting && state.exercises.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.routine_empty_hint),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
                    )
                }
            }
            if (!state.loading && !state.missing && !deleting) {
                item(key = "add") {
                    SecondaryButton(
                        text = stringResource(R.string.routine_add_exercise),
                        onClick = onAddExercise,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    val repsOf = state.exercises.firstOrNull { it.id == editingReps }
    if (repsOf != null) {
        NumberInputSheet(
            spec = repsSpec(
                initial = (repsOf.targetReps ?: DEFAULT_TARGET_REPS).toString(),
                title = stringResource(R.string.routine_target_reps_title, exerciseDisplayName(repsOf.nameKey, repsOf.name)),
                minimum = 1,
                errorText = stringResource(R.string.routine_error_target_reps),
            ),
            onDismiss = { editingReps = null },
            onConfirm = { text, _ ->
                editingReps = null
                parseReps(text)?.let { vm.setTargetReps(repsOf.id, it) }
            },
        )
    }

    if (renaming) {
        RoutineNameDialog(
            title = stringResource(R.string.routine_rename_title),
            initialName = state.name,
            confirmLabel = stringResource(R.string.routine_rename),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                vm.rename(name)
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            shape = TungCorner,
            title = { Text(stringResource(R.string.routine_delete_title)) },
            text = { Text(stringResource(R.string.routine_delete_body), color = Tung.colors.muted) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    deleting = true
                    vm.delete(onBack)
                }) {
                    Text(stringResource(R.string.routine_delete_confirm), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel), color = Tung.colors.text)
                }
            },
        )
    }
}

@Composable
private fun RoutineExerciseRow(
    exercise: RoutineExerciseUi,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onChangeSets: (Int) -> Unit,
    onEditReps: () -> Unit,
    onClearReps: () -> Unit,
    onRemove: () -> Unit,
) {
    val name = exerciseDisplayName(exercise.nameKey, exercise.name)
    val editRepsDescription = stringResource(R.string.routine_edit_target_reps, name)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            // Tapping the target reps opens the same reps sheet as a set.
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clickable(role = Role.Button, onClickLabel = editRepsDescription, onClick = onEditReps)
                    .semantics { contentDescription = editRepsDescription },
            ) {
                Text(
                    exercise.targetReps?.let { pluralStringResource(R.plurals.routine_target_reps, it, it) }
                        ?: stringResource(R.string.routine_no_target_reps),
                    style = Tung.numbers.inline,
                    color = Tung.colors.muted,
                )
            }
        }
        StepButton(
            text = stringResource(R.string.input_minus),
            description = stringResource(R.string.routine_fewer_sets, name),
            enabled = exercise.targetSets > MIN_TARGET_SETS,
            onClick = { onChangeSets(-1) },
        )
        Column(
            modifier = Modifier.widthIn(min = 56.dp).semantics(mergeDescendants = true) {},
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                exercise.targetSets.toString(),
                style = Tung.numbers.set,
                color = Tung.colors.text,
                textAlign = TextAlign.Center,
            )
            Text(stringResource(R.string.routine_sets_label), style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
        }
        StepButton(
            text = stringResource(R.string.input_plus),
            description = stringResource(R.string.routine_more_sets, name),
            enabled = exercise.targetSets < MAX_TARGET_SETS,
            onClick = { onChangeSets(1) },
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.routine_exercise_menu, name))
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = Tung.colors.raised,
                shape = TungCorner,
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routine_move_up)) },
                    enabled = canMoveUp,
                    onClick = { menuOpen = false; onMove(-1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routine_move_down)) },
                    enabled = canMoveDown,
                    onClick = { menuOpen = false; onMove(1) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routine_clear_target_reps)) },
                    enabled = exercise.targetReps != null,
                    onClick = { menuOpen = false; onClearReps() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.routine_remove_exercise)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

/** What the reps sheet starts at for an exercise with no target yet. */
private const val DEFAULT_TARGET_REPS = 8

/** A square −/+ on the raised surface, thumb-sized. */
@Composable
private fun StepButton(text: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = TungCorner,
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Tung.colors.raised,
            contentColor = Tung.colors.text,
            disabledContainerColor = Tung.colors.background,
            disabledContentColor = Tung.colors.border,
        ),
        elevation = null,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
    ) {
        Text(text, style = Tung.numbers.set)
    }
}
