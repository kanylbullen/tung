package io.github.kanylbullen.tung.ui.workout

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.core.parseWeight
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.stepWeight
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

private enum class Field { WEIGHT, REPS }

private data class EditTarget(val setId: String, val field: Field)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    workoutId: String,
    onBack: () -> Unit,
    onAddExercise: () -> Unit,
    onFinished: (workoutId: String, saved: Boolean) -> Unit,
) {
    val container = appContainer()
    val vm: ActiveWorkoutViewModel = viewModel(
        factory = factoryOf { ActiveWorkoutViewModel(workoutId, container.workoutRepository) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val discardedMessage = stringResource(R.string.workout_discarded)
    val currentOnFinished by rememberUpdatedState(onFinished)

    // Between sets the phone lies on a bench; the screen must not sleep.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ActiveWorkoutEvent.Finished -> {
                    if (!event.saved) Toast.makeText(context, discardedMessage, Toast.LENGTH_LONG).show()
                    currentOnFinished(event.workoutId, event.saved)
                }
            }
        }
    }

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }

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
                title = {
                    if (!state.loading && !state.missing) {
                        val now = rememberNow()
                        val clock = clockText(now - state.startedAt)
                        val description = stringResource(R.string.workout_elapsed_description, clock)
                        Text(
                            clock,
                            style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.8f),
                            modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                        )
                    }
                },
                actions = {
                    if (!state.loading && !state.missing) {
                        TextButton(onClick = { confirmFinish = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(
                                stringResource(R.string.workout_finish),
                                style = MaterialTheme.typography.labelLarge,
                                color = Tung.colors.accentText,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding))
            state.missing -> Text(
                stringResource(R.string.workout_not_found),
                style = MaterialTheme.typography.bodyLarge,
                color = Tung.colors.muted,
                modifier = Modifier.padding(padding).padding(20.dp),
            )
            else -> WorkoutList(
                state = state,
                contentPadding = padding,
                onEdit = { setId, field -> editing = EditTarget(setId, field) },
                onToggle = { set ->
                    // Ticking a set without reps would just be removed on finish, so ask for reps first.
                    if (!set.completed && set.reps <= 0) editing = EditTarget(set.id, Field.REPS)
                    else vm.setCompleted(set.id, !set.completed)
                },
                onDeleteSet = vm::deleteSet,
                onAddSet = vm::addSet,
                onRemoveExercise = vm::removeExercise,
                onAddExercise = onAddExercise,
            )
        }
    }

    editing?.let { target ->
        val set = state.findSet(target.setId)
        if (set == null) {
            LaunchedEffect(target) { editing = null }
        } else {
            NumberInputSheet(
                spec = when (target.field) {
                    Field.WEIGHT -> weightSpec(localWeight(set.weightKg))
                    Field.REPS -> repsSpec(set.reps.toString())
                },
                onDismiss = { editing = null },
                onConfirm = { text ->
                    when (target.field) {
                        Field.WEIGHT -> parseWeight(text)?.let { vm.setWeight(set.id, it) }
                        Field.REPS -> parseReps(text)?.let { vm.setReps(set.id, it) }
                    }
                    editing = null
                },
            )
        }
    }

    if (confirmFinish) {
        val empty = state.loggedSetCount == 0
        AlertDialog(
            onDismissRequest = { confirmFinish = false },
            containerColor = Tung.colors.raised,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.workout_finish_title)) },
            text = {
                Text(
                    stringResource(if (empty) R.string.workout_finish_body_empty else R.string.workout_finish_body),
                    color = Tung.colors.muted,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmFinish = false; vm.finish() }) {
                    Text(stringResource(R.string.workout_finish), color = Tung.colors.accentText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmFinish = false }) {
                    Text(stringResource(R.string.workout_keep_training), color = Tung.colors.text)
                }
            },
        )
    }
}

@Composable
private fun WorkoutList(
    state: ActiveWorkoutUiState,
    contentPadding: PaddingValues,
    onEdit: (String, Field) -> Unit,
    onToggle: (ActiveSetUi) -> Unit,
    onDeleteSet: (String) -> Unit,
    onAddSet: (String) -> Unit,
    onRemoveExercise: (String) -> Unit,
    onAddExercise: () -> Unit,
) {
    LazyColumn(
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.exercises.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.workout_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
        state.exercises.forEach { exercise ->
            item(key = "we-${exercise.workoutExerciseId}") {
                ExerciseHeader(exerciseDisplayName(exercise.nameKey, exercise.name), onRemove = { onRemoveExercise(exercise.workoutExerciseId) })
            }
            items(exercise.sets, key = { "set-${it.id}" }) { set ->
                SwipeableSetRow(
                    set = set,
                    onEditWeight = { onEdit(set.id, Field.WEIGHT) },
                    onEditReps = { onEdit(set.id, Field.REPS) },
                    onToggle = { onToggle(set) },
                    onDelete = { onDeleteSet(set.id) },
                )
            }
            item(key = "add-${exercise.workoutExerciseId}") {
                val description = stringResource(R.string.workout_add_set_description, exercise.name)
                TextButton(
                    onClick = { onAddSet(exercise.workoutExerciseId) },
                    modifier = Modifier
                        .padding(start = 8.dp, bottom = 12.dp)
                        .heightIn(min = 48.dp)
                        .semantics { contentDescription = description },
                ) {
                    Text(
                        stringResource(R.string.workout_add_set),
                        style = MaterialTheme.typography.labelLarge,
                        color = Tung.colors.text,
                    )
                }
            }
        }
        item(key = "add-exercise") {
            val text = stringResource(R.string.workout_add_exercise)
            Box(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                if (state.exercises.isEmpty()) {
                    PrimaryButton(text = text, onClick = onAddExercise)
                } else {
                    SecondaryButton(text = text, onClick = onAddExercise)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ExerciseHeader(name: String, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            color = Tung.colors.text,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.workout_exercise_menu, name),
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
                    text = { Text(stringResource(R.string.workout_remove_exercise)) },
                    onClick = { menuOpen = false; onRemove() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableSetRow(
    set: ActiveSetUi,
    onEditWeight: () -> Unit,
    onEditReps: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                currentOnDelete()
                true
            } else {
                false
            }
        },
    )
    val deleteLabel = stringResource(R.string.workout_delete_set)
    // The accessible way to delete: a custom action on every part of the row a
    // screen reader can focus. The row container itself is never focused, so an
    // action there would be out of reach.
    val deleteAction = remember(deleteLabel) {
        listOf(CustomAccessibilityAction(deleteLabel) { currentOnDelete(); true })
    }
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            // Always composed under the row; hidden from screen readers until a swipe reveals it,
            // or TalkBack would read "Delete" on every set.
            val settled = dismissState.dismissDirection == SwipeToDismissBoxValue.Settled
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (settled) Modifier.clearAndSetSemantics {} else Modifier)
                    .background(Tung.colors.border)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(stringResource(R.string.workout_delete), color = Tung.colors.text)
            }
        },
    ) {
        SetRow(set, deleteAction, onEditWeight, onEditReps, onToggle)
    }
}

@Composable
private fun SetRow(
    set: ActiveSetUi,
    deleteAction: List<CustomAccessibilityAction>,
    onEditWeight: () -> Unit,
    onEditReps: () -> Unit,
    onToggle: () -> Unit,
) {
    val weight = localWeight(set.weightKg)
    val reps = if (set.reps > 0) set.reps.toString() else stringResource(R.string.workout_reps_empty)
    val setDescription = stringResource(R.string.workout_set_number_description, set.number)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tung.colors.background)
            .heightIn(min = 72.dp)
            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            set.number.toString(),
            style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.7f),
            color = Tung.colors.muted,
            modifier = Modifier
                .width(32.dp)
                .padding(start = 8.dp)
                .semantics {
                    contentDescription = setDescription
                    customActions = deleteAction
                },
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NumberCell(
                    value = weight,
                    unit = stringResource(R.string.workout_unit_kg),
                    description = stringResource(R.string.workout_edit_weight, weight),
                    actions = deleteAction,
                    onClick = onEditWeight,
                )
                Text(stringResource(R.string.times_sign), style = Tung.numbers.inline, color = Tung.colors.muted)
                NumberCell(
                    value = reps,
                    unit = stringResource(R.string.workout_unit_reps),
                    description = stringResource(R.string.workout_edit_reps, reps),
                    actions = deleteAction,
                    onClick = onEditReps,
                )
            }
            set.previous?.let { prev ->
                Text(
                    stringResource(R.string.workout_previous, localWeight(prev.weightKg), prev.reps),
                    style = Tung.numbers.inline,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        DoneCheck(number = set.number, completed = set.completed, actions = deleteAction, onToggle = onToggle)
    }
}

@Composable
private fun NumberCell(
    value: String,
    unit: String,
    description: String,
    actions: List<CustomAccessibilityAction>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TungCorner)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                customActions = actions
            }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(value, style = Tung.numbers.set, color = Tung.colors.text, modifier = Modifier.alignByBaseline())
        Text(unit, style = Tung.numbers.inline, color = Tung.colors.muted, modifier = Modifier.alignByBaseline())
    }
}

@Composable
private fun DoneCheck(
    number: Int,
    completed: Boolean,
    actions: List<CustomAccessibilityAction>,
    onToggle: () -> Unit,
) {
    val fill by animateColorAsState(if (completed) Tung.colors.accent else Color.Transparent, label = "check-fill")
    val ring by animateColorAsState(if (completed) Tung.colors.accent else Tung.colors.muted, label = "check-ring")
    val tint by animateColorAsState(if (completed) Tung.colors.onAccent else Color.Transparent, label = "check-tint")
    val description = stringResource(
        if (completed) R.string.workout_mark_undone else R.string.workout_mark_done,
        number,
    )
    Box(
        modifier = Modifier
            .size(56.dp)
            .toggleable(value = completed, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics {
                contentDescription = description
                customActions = actions
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(fill, CircleShape)
                .border(2.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        }
    }
}


@Composable
private fun weightSpec(initial: String) = NumberInputSpec(
    title = stringResource(R.string.input_weight_title),
    unit = stringResource(R.string.workout_unit_kg),
    initialText = initial,
    keyboardType = KeyboardType.Decimal,
    fieldDescription = stringResource(R.string.input_field_weight),
    minusDescription = stringResource(R.string.input_minus_weight),
    plusDescription = stringResource(R.string.input_plus_weight),
    hint = stringResource(R.string.input_hint),
    errorText = stringResource(R.string.input_error_weight),
    doneText = stringResource(R.string.input_done),
    minusText = stringResource(R.string.input_minus),
    plusText = stringResource(R.string.input_plus),
    isValid = { parseWeight(it) != null },
    step = { text, steps -> localWeight(stepWeight(parseWeight(text) ?: 0.0, steps)) },
    sanitize = { text -> text.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
)

@Composable
private fun repsSpec(initial: String) = NumberInputSpec(
    title = stringResource(R.string.input_reps_title),
    unit = stringResource(R.string.workout_unit_reps),
    initialText = initial,
    keyboardType = KeyboardType.Number,
    fieldDescription = stringResource(R.string.input_field_reps),
    minusDescription = stringResource(R.string.input_minus_reps),
    plusDescription = stringResource(R.string.input_plus_reps),
    hint = stringResource(R.string.input_hint),
    errorText = stringResource(R.string.input_error_reps),
    doneText = stringResource(R.string.input_done),
    minusText = stringResource(R.string.input_minus),
    plusText = stringResource(R.string.input_plus),
    isValid = { parseReps(it) != null },
    step = { text, steps -> stepReps(parseReps(text) ?: 0, steps).toString() },
    sanitize = { text -> text.filter { it.isDigit() }.take(3) },
)
