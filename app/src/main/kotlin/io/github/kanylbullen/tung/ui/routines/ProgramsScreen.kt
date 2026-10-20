package io.github.kanylbullen.tung.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.data.ProgramSummaryRow
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.tungFieldColors
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/** Every program: create one, open one, or make one the active program. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgramsScreen(onBack: () -> Unit, onOpenProgram: (String) -> Unit) {
    val container = appContainer()
    val vm: ProgramsViewModel = viewModel(factory = factoryOf { ProgramsViewModel(container.programRepository, container.commands) })
    val state by vm.state.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

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
                title = { Text(stringResource(R.string.programs_title)) },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item(key = "new") {
                SecondaryButton(
                    text = stringResource(R.string.program_new),
                    onClick = { creating = true },
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            if (!state.loading && state.programs.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.programs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
            items(state.programs, key = { it.id }) { row ->
                ProgramRow(row = row, onClick = { onOpenProgram(row.id) }, onActivate = { vm.activate(row.id) })
            }
        }
    }

    if (creating) {
        NewProgramDialog(
            onDismiss = { creating = false },
            onCreate = { name, mode ->
                creating = false
                vm.create(name, mode, onOpenProgram)
            },
        )
    }
}

/** A program as one list row: name, mode and day count, and "Active" or an Activate button. */
@Composable
private fun ProgramRow(row: ProgramSummaryRow, onClick: () -> Unit, onActivate: () -> Unit) {
    val details = listOf(
        programModeLabel(ProgramMode.fromKey(row.mode)),
        pluralStringResource(R.plurals.program_day_count, row.dayCount, row.dayCount),
    ).joinToString(stringResource(R.string.row_separator))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(details, style = Tung.numbers.inline, color = Tung.colors.muted)
        }
        Spacer(Modifier.width(12.dp))
        if (row.isActive) {
            Text(stringResource(R.string.program_active), style = MaterialTheme.typography.labelLarge, color = Tung.colors.accentText)
        } else {
            ActivateButton(name = row.name, onClick = onActivate)
        }
    }
}

/** "Activate" on the raised surface, described with the program's name. */
@Composable
fun ActivateButton(name: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(R.string.program_activate_description, name)
    Button(
        onClick = onClick,
        shape = TungCorner,
        colors = ButtonDefaults.buttonColors(containerColor = Tung.colors.raised, contentColor = Tung.colors.text),
        elevation = null,
        modifier = modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
    ) {
        Text(stringResource(R.string.program_activate), style = MaterialTheme.typography.labelLarge)
    }
}

/** Asks for a new program's name and mode. */
@Composable
private fun NewProgramDialog(onDismiss: () -> Unit, onCreate: (String, ProgramMode) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var mode by rememberSaveable { mutableStateOf(ProgramMode.ROTATING) }
    var showError by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // See RoutineNameDialog: a dialog with a text field needs Compose's width to settle under Robolectric.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(horizontal = 24.dp),
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
        shape = TungCorner,
        title = { Text(stringResource(R.string.program_new)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showError = false },
                    label = { Text(stringResource(R.string.program_name)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(R.string.program_error_name)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = TungCorner,
                    colors = tungFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(Modifier.selectableGroup()) {
                    listOf(
                        ProgramMode.ROTATING to R.string.program_mode_rotating_hint,
                        ProgramMode.FIXED to R.string.program_mode_fixed_hint,
                    ).forEach { (option, hint) ->
                        ModeOption(
                            label = programModeLabel(option),
                            hint = stringResource(hint),
                            selected = mode == option,
                            onSelect = { mode = option },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (name.isBlank()) showError = true else onCreate(name.trim(), mode) }) {
                Text(stringResource(R.string.program_create), color = Tung.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Tung.colors.text)
            }
        },
    )
}

@Composable
private fun ModeOption(label: String, hint: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Tung.colors.accent, unselectedColor = Tung.colors.muted),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Tung.colors.muted)
        }
    }
}
