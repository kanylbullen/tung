package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ISO_WEEKDAYS
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.data.RoutineSummaryRow
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * One program: its days in order, each a routine; weekdays per day in fixed
 * mode; add, reorder and remove days; rename, activate or delete the program.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramDetailScreen(programId: String, onBack: () -> Unit) {
    val container = appContainer()
    val vm: ProgramDetailViewModel = viewModel(
        factory = factoryOf { ProgramDetailViewModel(programId, container.programRepository, container.routineRepository, container.commands) }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

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
                                if (state.isActive) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.program_deactivate)) },
                                        onClick = { menuOpen = false; vm.deactivate() },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.program_delete)) },
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
                        stringResource(R.string.program_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            if (!state.loading && !state.missing && !deleting) {
                item(key = "status") {
                    ProgramStatus(state, onActivate = vm::activate)
                }
                if (state.days.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.program_days_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = Tung.colors.muted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
                itemsIndexed(state.days, key = { _, day -> day.id }) { index, day ->
                    ProgramDayRow(
                        day = day,
                        mode = state.mode,
                        number = index + 1,
                        count = state.days.size,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.days.lastIndex,
                        onMove = { delta -> vm.moveDay(day.id, delta) },
                        onRemove = { vm.removeDay(day.id) },
                        onToggleWeekday = { weekday -> vm.toggleWeekday(day.id, weekday) },
                    )
                }
                item(key = "add") {
                    SecondaryButton(
                        text = stringResource(R.string.program_add_day),
                        onClick = { picking = true },
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }
    }

    if (picking) {
        RoutinePickerSheet(
            routines = state.routines,
            onDismiss = { picking = false },
            onPick = { routineId ->
                picking = false
                vm.addDay(routineId)
            },
        )
    }

    if (renaming) {
        RoutineNameDialog(
            title = stringResource(R.string.program_rename_title),
            initialName = state.name,
            confirmLabel = stringResource(R.string.routine_rename),
            errorText = stringResource(R.string.program_error_name),
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
            title = { Text(stringResource(R.string.program_delete_title)) },
            text = { Text(stringResource(R.string.program_delete_body), color = Tung.colors.muted) },
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

/** The mode and day count, and "Active" or an Activate button. */
@Composable
private fun ProgramStatus(state: ProgramDetailUiState, onActivate: () -> Unit) {
    val details = listOf(
        programModeLabel(state.mode),
        pluralStringResource(R.plurals.program_day_count, state.days.size, state.days.size),
    ).joinToString(stringResource(R.string.row_separator))
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(details, style = Tung.numbers.inline, color = Tung.colors.muted, modifier = Modifier.weight(1f))
        if (state.isActive) {
            Text(stringResource(R.string.program_active), style = MaterialTheme.typography.labelLarge, color = Tung.colors.accentText)
        } else {
            ActivateButton(name = state.name, onClick = onActivate)
        }
    }
}

@Composable
private fun ProgramDayRow(
    day: ProgramDayEditUi,
    mode: ProgramMode,
    number: Int,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onToggleWeekday: (Int) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(day.routineName, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
                if (mode == ProgramMode.ROTATING) {
                    Text(
                        stringResource(R.string.program_day_of, number, count),
                        style = Tung.numbers.inline,
                        color = Tung.colors.muted,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.program_day_menu, day.routineName))
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
                        text = { Text(stringResource(R.string.program_remove_day)) },
                        onClick = { menuOpen = false; onRemove() },
                    )
                }
            }
        }
        if (mode == ProgramMode.FIXED) {
            Spacer(Modifier.height(8.dp))
            WeekdayToggles(routineName = day.routineName, selected = day.weekdays, onToggle = onToggleWeekday)
        }
    }
}

/** Mon–Sun as seven toggles in one row; any number can be on. */
@Composable
private fun WeekdayToggles(routineName: String, selected: Set<Int>, onToggle: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ISO_WEEKDAYS.forEach { weekday ->
            val on = weekday in selected
            val description = stringResource(R.string.program_weekday_description, routineName, weekdayFull(weekday))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .then(
                        if (on) {
                            Modifier.background(Tung.colors.accent, TungCorner)
                        } else {
                            Modifier.border(1.dp, Tung.colors.border, TungCorner)
                        },
                    )
                    .toggleable(value = on, role = Role.Checkbox, onValueChange = { onToggle(weekday) })
                    .semantics { contentDescription = description },
            ) {
                Text(
                    weekdayShort(weekday),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) Tung.colors.onAccent else Tung.colors.text,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Every routine; tapping one adds it as the program's last day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutinePickerSheet(routines: List<RoutineSummaryRow>, onDismiss: () -> Unit, onPick: (String) -> Unit) {
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
                stringResource(R.string.program_pick_routine),
                style = MaterialTheme.typography.titleMedium,
                color = Tung.colors.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            if (routines.isEmpty()) {
                Text(
                    stringResource(R.string.program_no_routines),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Tung.colors.muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            routines.forEach { routine ->
                ProgramDayChoiceRow(
                    routineName = routine.name,
                    place = pluralStringResource(R.plurals.exercise_count, routine.exerciseCount, routine.exerciseCount),
                    onClick = { onPick(routine.id) },
                )
            }
        }
    }
}
