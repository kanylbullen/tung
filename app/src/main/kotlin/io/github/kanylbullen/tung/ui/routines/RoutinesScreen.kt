package io.github.kanylbullen.tung.ui.routines

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.rememberNow
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * The Routines tab: every routine, most recently used first; tap one to edit
 * it, or start it. "+ New routine" in the top bar asks for a name and opens the
 * empty routine. Programs are one step further, from the row on top.
 * A long press starts selection mode (issue #79): taps toggle rows, the top
 * bar selects all or deletes the selection in one `DeleteRoutines`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutinesScreen(
    onOpenRoutine: (String) -> Unit,
    onOpenWorkout: (String) -> Unit,
    onShowPrograms: () -> Unit,
) {
    val container = appContainer()
    val vm: RoutinesViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                RoutinesViewModel(
                    container.routineRepository,
                    container.workoutRepository,
                    container.programRepository,
                    container.commands,
                    createSavedStateHandle(),
                )
            }
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val confirm by vm.confirm.collectAsStateWithLifecycle()
    val now = rememberNow(intervalMillis = 60_000L)
    var creating by rememberSaveable { mutableStateOf(false) }

    // Back leaves selection mode before it leaves the tab.
    BackHandler(enabled = state.selecting) { vm.clearSelection() }

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            if (state.selecting) {
                SelectionTopBar(
                    count = state.selected.size,
                    allSelected = state.selected.size == state.routines.size,
                    onClose = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onDelete = vm::askDelete,
                )
            } else {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Tung.colors.background,
                        scrolledContainerColor = Tung.colors.background,
                    ),
                    title = { Text(stringResource(R.string.routines_title)) },
                    actions = {
                        TextButton(onClick = { creating = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.routine_new), color = Tung.colors.text)
                        }
                    },
                )
            }
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item(key = "programs") {
                ProgramsEntry(activeProgram = state.activeProgram, onClick = onShowPrograms)
            }
            if (!state.loading && state.routines.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.routines_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            items(state.routines, key = { it.id }) { row ->
                RoutineRow(
                    row = row,
                    now = now,
                    startEnabled = state.canStart,
                    onStart = { vm.start(row.id, onOpenWorkout) },
                    onClick = { if (state.selecting) vm.toggle(row.id) else onOpenRoutine(row.id) },
                    onLongClick = { vm.toggle(row.id) },
                    selected = if (state.selecting) row.id in state.selected else null,
                )
            }
        }
    }

    confirm?.let { asked ->
        DeleteRoutinesDialog(
            count = asked.ids.size,
            programDays = asked.programDays,
            body = stringResource(R.string.routines_delete_many_body),
            onDismiss = vm::dismissDelete,
            onConfirm = vm::confirmDelete,
        )
    }

    if (creating) {
        RoutineNameDialog(
            title = stringResource(R.string.routine_new_title),
            initialName = "",
            confirmLabel = stringResource(R.string.routine_create),
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                vm.create(name, onOpenRoutine)
            },
        )
    }
}

/** The way to Programs: a plain row with the active program's name, or what programs are for. */
@Composable
private fun ProgramsEntry(activeProgram: String?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.programs_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
        Text(
            activeProgram?.let { stringResource(R.string.programs_entry_active, it) }
                ?: stringResource(R.string.programs_entry_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Tung.colors.muted,
        )
    }
}

/** The top bar in selection mode: ✕, "3 selected", "Select all" and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectionTopBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Tung.colors.background,
            scrolledContainerColor = Tung.colors.background,
        ),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.routines_leave_selection))
            }
        },
        title = { Text(pluralStringResource(R.plurals.routines_selected, count, count)) },
        actions = {
            if (!allSelected) {
                TextButton(onClick = onSelectAll, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.routines_select_all), color = Tung.colors.text)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.routines_delete_selected))
            }
        },
    )
}

/**
 * Asks once before several routines go: "Delete 12 routines?", [body] (history
 * is kept), and how many program days go with them when any do. Shared by the
 * Routines tab and Settings → Data.
 */
@Composable
fun DeleteRoutinesDialog(count: Int, programDays: Int, body: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        shape = TungCorner,
        title = { Text(pluralStringResource(R.plurals.routines_delete_many_title, count, count)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, color = Tung.colors.muted)
                if (programDays > 0) {
                    Text(pluralStringResource(R.plurals.routines_delete_many_days, programDays, programDays), color = Tung.colors.muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.routine_delete_confirm), color = Tung.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}
