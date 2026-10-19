package io.github.kanylbullen.tung.ui.workout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.REPS_RULER
import io.github.kanylbullen.tung.core.RestTimer
import io.github.kanylbullen.tung.core.WEIGHT_RULER
import io.github.kanylbullen.tung.core.parseReps
import io.github.kanylbullen.tung.core.parseWeight
import io.github.kanylbullen.tung.core.stepReps
import io.github.kanylbullen.tung.core.stepWeight
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.clockText
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import kotlin.math.roundToInt

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
        factory = viewModelFactory {
            initializer {
                ActiveWorkoutViewModel(
                    workoutId = workoutId,
                    repo = container.workoutRepository,
                    exerciseRepo = container.exerciseRepository,
                    restTimer = container.restTimer,
                    savedState = createSavedStateHandle(),
                )
            }
        }
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

    // Asked once, when the first rest timer starts. Denied is fine: the countdown and the buzz still work.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is ActiveWorkoutEvent.Finished -> {
                    if (!event.saved) Toast.makeText(context, discardedMessage, Toast.LENGTH_LONG).show()
                    currentOnFinished(event.workoutId, event.saved)
                }
                ActiveWorkoutEvent.AskForNotifications -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
        }
    }

    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var confirmFinish by remember { mutableStateOf(false) }
    var restControlsOpen by remember { mutableStateOf(false) }
    var restSheetFor by remember { mutableStateOf<ActiveExerciseUi?>(null) }
    // The set that was just logged; once the current set has moved on from it, that set is scrolled into view.
    var scrollAfterLogging by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    // The clock, not a counter: what is left of the rest is always computed from it.
    val now = rememberNow()
    val rest = state.rest?.takeIf { it.isRunningAt(now) }
    state.rest?.let { timer ->
        if (timer.isFinishedAt(now)) LaunchedEffect(timer) { vm.onRestOver(timer) }
    }
    LaunchedEffect(rest == null) { if (rest == null) restControlsOpen = false }

    val current = state.current
    LaunchedEffect(current?.set?.id, scrollAfterLogging) {
        val logged = scrollAfterLogging ?: return@LaunchedEffect
        if (current?.set?.id == logged) return@LaunchedEffect
        scrollAfterLogging = null
        current?.let { listState.scrollIntoView(itemIndexOfSet(state, it.set.id)) }
    }

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            Column {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val clock = clockText(now - state.startedAt)
                            val description = stringResource(R.string.workout_elapsed_description, clock)
                            Text(
                                clock,
                                style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.8f),
                                modifier = Modifier.clearAndSetSemantics { contentDescription = description },
                            )
                            if (rest != null) {
                                Spacer(Modifier.width(12.dp))
                                RestCountdown(rest, now, onClick = { restControlsOpen = !restControlsOpen })
                            }
                        }
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
            if (rest != null && restControlsOpen) {
                RestControls(onSkip = vm::skipRest, onAdd = vm::addRest)
            }
            }
        },
        bottomBar = {
            if (!state.loading && !state.missing && current != null) {
                LogSetBar(
                    set = current.set,
                    onClick = {
                        // Logging a set without reps would just be removed on finish, so ask for reps first.
                        if (current.set.reps <= 0) {
                            editing = EditTarget(current.set.id, Field.REPS)
                        } else {
                            scrollAfterLogging = current.set.id
                            vm.logSet(current.exercise, current.set)
                        }
                    },
                )
            }
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
                listState = listState,
                contentPadding = padding,
                onEdit = { setId, field -> editing = EditTarget(setId, field) },
                onToggle = { exercise, set ->
                    // Ticking a set without reps would just be removed on finish, so ask for reps first.
                    if (!set.completed && set.reps <= 0) editing = EditTarget(set.id, Field.REPS)
                    else vm.setCompleted(exercise, set.id, !set.completed)
                },
                onDeleteSet = vm::deleteSet,
                onAddSet = vm::addSet,
                onRemoveExercise = vm::removeExercise,
                onRestTimer = { restSheetFor = it },
                onAddExercise = onAddExercise,
            )
        }
    }

    editing?.let { target ->
        val exercise = state.findExerciseOf(target.setId)
        val set = exercise?.sets?.firstOrNull { it.id == target.setId }
        if (exercise == null || set == null) {
            LaunchedEffect(target) { editing = null }
        } else {
            NumberInputSheet(
                spec = when (target.field) {
                    Field.WEIGHT -> weightSpec(localWeight(set.weightKg))
                    Field.REPS -> repsSpec(set.reps.toString())
                },
                // Only offered when another un-ticked set would take the value.
                showApplyToAll = exercise.sets.any { it.id != set.id && !it.completed },
                onDismiss = { editing = null },
                onConfirm = { text, applyToAll ->
                    val weId = exercise.workoutExerciseId
                    when (target.field) {
                        Field.WEIGHT -> parseWeight(text)?.let {
                            if (applyToAll) vm.setWeightForOpenSets(weId, set.id, it) else vm.setWeight(weId, set.id, it)
                        }
                        Field.REPS -> parseReps(text)?.let {
                            if (applyToAll) vm.setRepsForOpenSets(weId, set.id, it) else vm.setReps(weId, set.id, it)
                        }
                    }
                    editing = null
                },
            )
        }
    }

    restSheetFor?.let { exercise ->
        RestTimerSheet(
            exerciseName = exerciseDisplayName(exercise.nameKey, exercise.name),
            initialSeconds = exercise.restSeconds,
            onDismiss = { restSheetFor = null },
            onConfirm = { seconds ->
                vm.setRestSeconds(exercise.exerciseId, seconds)
                restSheetFor = null
            },
        )
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

/**
 * The big button above the system navigation: always under the thumb, wherever
 * the list is scrolled, which a button at the end of the list would not be.
 */
@Composable
private fun LogSetBar(set: ActiveSetUi, onClick: () -> Unit) {
    val reps = if (set.reps > 0) set.reps.toString() else stringResource(R.string.workout_reps_empty)
    Box(
        Modifier
            .fillMaxWidth()
            .background(Tung.colors.background)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        PrimaryButton(text = stringResource(R.string.workout_log_set, localWeight(set.weightKg), reps), onClick = onClick)
    }
}

/** "1:30" counting down next to the elapsed time; a tap shows Skip and +30 s. */
@Composable
private fun RestCountdown(timer: RestTimer, now: Long, onClick: () -> Unit) {
    // Rounded up, so 89.4 s left reads 1:30 and 0:00 never shows; capped, so a clock a tick behind never shows 1:31.
    val left = clockText(minOf(timer.remainingAt(now) + 999, timer.durationMillis))
    val title = stringResource(R.string.rest_title)
    val remaining = stringResource(R.string.rest_remaining, left)
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(TungCorner)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = title
                stateDescription = remaining
                role = Role.Button
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            left,
            style = Tung.numbers.set.copy(fontSize = Tung.numbers.set.fontSize * 0.8f),
            color = Tung.colors.accentText,
        )
    }
}

@Composable
private fun RestControls(onSkip: () -> Unit, onAdd: () -> Unit) {
    val addDescription = stringResource(R.string.rest_add_description)
    Row(
        modifier = Modifier.fillMaxWidth().background(Tung.colors.background).padding(start = 56.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.rest_skip), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
        }
        TextButton(
            onClick = onAdd,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = addDescription },
        ) {
            Text(stringResource(R.string.rest_add), style = MaterialTheme.typography.labelLarge, color = Tung.colors.text)
        }
    }
}

/** The position of set [setId] among the list's items; mirrors the item order in [WorkoutList]. */
private fun itemIndexOfSet(state: ActiveWorkoutUiState, setId: String): Int {
    var index = if (state.exercises.isEmpty()) 1 else 0
    state.exercises.forEach { exercise ->
        index++ // header
        exercise.sets.forEach { set ->
            if (set.id == setId) return index
            index++
        }
        index++ // "+ Set"
    }
    return index
}

/** Scrolls only when the item is not wholly on screen, and then leaves the row above it in view too. */
private suspend fun LazyListState.scrollIntoView(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index }
    val whollyVisible = item != null &&
        item.offset >= info.viewportStartOffset + info.beforeContentPadding &&
        item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding
    if (!whollyVisible) animateScrollToItem((index - 1).coerceAtLeast(0))
}

@Composable
private fun WorkoutList(
    state: ActiveWorkoutUiState,
    listState: LazyListState,
    contentPadding: PaddingValues,
    onEdit: (String, Field) -> Unit,
    onToggle: (ActiveExerciseUi, ActiveSetUi) -> Unit,
    onDeleteSet: (String) -> Unit,
    onAddSet: (String) -> Unit,
    onRemoveExercise: (String) -> Unit,
    onRestTimer: (ActiveExerciseUi) -> Unit,
    onAddExercise: () -> Unit,
) {
    LazyColumn(
        state = listState,
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
                ExerciseHeader(
                    exerciseDisplayName(exercise.nameKey, exercise.name),
                    onRestTimer = { onRestTimer(exercise) },
                    onRemove = { onRemoveExercise(exercise.workoutExerciseId) },
                )
            }
            items(exercise.sets, key = { "set-${it.id}" }) { set ->
                SwipeableSetRow(
                    set = set,
                    onEditWeight = { onEdit(set.id, Field.WEIGHT) },
                    onEditReps = { onEdit(set.id, Field.REPS) },
                    onToggle = { onToggle(exercise, set) },
                    onDelete = { onDeleteSet(set.id) },
                )
            }
            item(key = "add-${exercise.workoutExerciseId}") {
                val description = stringResource(
                    R.string.workout_add_set_description,
                    exerciseDisplayName(exercise.nameKey, exercise.name),
                )
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
private fun ExerciseHeader(name: String, onRestTimer: () -> Unit, onRemove: () -> Unit) {
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
                    text = { Text(stringResource(R.string.workout_rest_timer)) },
                    onClick = { menuOpen = false; onRestTimer() },
                )
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
private fun weightSpec(initial: String): NumberInputSpec {
    val context = LocalContext.current
    return NumberInputSpec(
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
        ruler = WEIGHT_RULER,
        toValue = ::parseWeight,
        fromValue = ::localWeight,
        rulerDescription = stringResource(R.string.input_ruler_weight),
        rulerState = { context.getString(R.string.input_ruler_weight_state, localWeight(it)) },
    )
}

@Composable
private fun repsSpec(initial: String): NumberInputSpec {
    val context = LocalContext.current
    return NumberInputSpec(
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
        ruler = REPS_RULER,
        toValue = { parseReps(it)?.toDouble() },
        fromValue = { it.roundToInt().toString() },
        rulerDescription = stringResource(R.string.input_ruler_reps),
        rulerState = { it.roundToInt().let { n -> context.resources.getQuantityString(R.plurals.input_ruler_reps_state, n, n) } },
    )
}
