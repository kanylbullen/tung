package io.github.kanylbullen.jarn.ui.exercises

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.jarn.R
import io.github.kanylbullen.jarn.data.Exercise
import io.github.kanylbullen.jarn.domain.Equipment
import io.github.kanylbullen.jarn.domain.MuscleGroup
import io.github.kanylbullen.jarn.ui.common.SecondaryButton
import io.github.kanylbullen.jarn.ui.common.appContainer
import io.github.kanylbullen.jarn.ui.common.equipmentLabel
import io.github.kanylbullen.jarn.ui.common.factoryOf
import io.github.kanylbullen.jarn.ui.common.muscleGroupLabel
import io.github.kanylbullen.jarn.ui.theme.Jarn
import io.github.kanylbullen.jarn.ui.theme.JarnCorner

/**
 * The exercise catalogue. With [workoutId] it is the picker: tapping an
 * exercise adds it to that workout and calls [onPicked].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(
    workoutId: Long?,
    onBack: (() -> Unit)?,
    onPicked: () -> Unit,
) {
    val container = appContainer()
    val vm: ExercisesViewModel = viewModel(
        factory = factoryOf {
            ExercisesViewModel(container.exerciseRepository, container.workoutRepository, workoutId)
        }
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Jarn.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Jarn.colors.background,
                    scrolledContainerColor = Jarn.colors.background,
                ),
                title = {
                    Text(stringResource(if (vm.isPicker) R.string.picker_title else R.string.exercises_title))
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize()) {
            item(key = "search") {
                SearchField(query, vm::setQuery)
            }
            if (!state.loading && state.isEmpty) {
                item(key = "no-match") {
                    Text(
                        stringResource(R.string.exercises_no_match),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Jarn.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            state.groups.forEach { group ->
                item(key = "group-${group.muscleGroup.key}") {
                    Text(
                        muscleGroupLabel(group.muscleGroup),
                        style = MaterialTheme.typography.titleMedium,
                        color = Jarn.colors.muted,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(group.exercises, key = { "ex-${it.id}" }) { exercise ->
                    ExerciseRow(
                        exercise = exercise,
                        onClick = if (vm.isPicker) ({ vm.pick(exercise.id, onPicked) }) else null,
                    )
                }
            }
            item(key = "create") {
                Box(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                    SecondaryButton(text = stringResource(R.string.exercises_create), onClick = { creating = true })
                }
            }
        }
    }

    if (creating) {
        CreateExerciseDialog(
            initialName = query.trim(),
            onDismiss = { creating = false },
            onSave = { name, muscle, equipment ->
                vm.create(name, muscle, equipment) { added ->
                    creating = false
                    if (added) onPicked()
                }
            },
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.exercises_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.exercises_clear_search))
                }
            }
        },
        shape = JarnCorner,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Jarn.colors.raised,
    unfocusedContainerColor = Jarn.colors.raised,
    focusedBorderColor = Jarn.colors.muted,
    unfocusedBorderColor = Jarn.colors.border,
    focusedTextColor = Jarn.colors.text,
    unfocusedTextColor = Jarn.colors.text,
    cursorColor = Jarn.colors.accent,
    focusedLeadingIconColor = Jarn.colors.muted,
    unfocusedLeadingIconColor = Jarn.colors.muted,
    focusedTrailingIconColor = Jarn.colors.muted,
    unfocusedTrailingIconColor = Jarn.colors.muted,
    focusedPlaceholderColor = Jarn.colors.muted,
    unfocusedPlaceholderColor = Jarn.colors.muted,
    focusedLabelColor = Jarn.colors.muted,
    unfocusedLabelColor = Jarn.colors.muted,
)

@Composable
private fun ExerciseRow(exercise: Exercise, onClick: (() -> Unit)?) {
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            exercise.name,
            style = MaterialTheme.typography.bodyLarge,
            color = Jarn.colors.text,
            modifier = Modifier.weight(1f),
        )
        val detail = buildList {
            add(equipmentLabel(Equipment.fromKey(exercise.equipment)))
            if (exercise.isCustom) add(stringResource(R.string.exercises_custom))
        }.joinToString(stringResource(R.string.row_separator))
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Jarn.colors.muted)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateExerciseDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String, MuscleGroup, Equipment) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var muscle by rememberSaveable { mutableStateOf(MuscleGroup.CHEST) }
    var equipment by rememberSaveable { mutableStateOf(Equipment.BARBELL) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Jarn.colors.raised,
        tonalElevation = 0.dp,
        title = { Text(stringResource(R.string.create_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showError = false },
                    label = { Text(stringResource(R.string.create_name)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(R.string.create_error_name)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = JarnCorner,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.create_muscle), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MuscleGroup.entries.forEach { group ->
                        Choice(muscleGroupLabel(group), selected = group == muscle) { muscle = group }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.create_equipment), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Equipment.entries.forEach { item ->
                        Choice(equipmentLabel(item), selected = item == equipment) { equipment = item }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) showError = true else onSave(name, muscle, equipment)
            }) {
                Text(stringResource(R.string.create_save), color = Jarn.colors.accentText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel), color = Jarn.colors.text)
            }
        },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = JarnCorner,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Jarn.colors.raised,
            labelColor = Jarn.colors.text,
            selectedContainerColor = Jarn.colors.text,
            selectedLabelColor = Jarn.colors.background,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Jarn.colors.muted,
            selectedBorderColor = Jarn.colors.text,
        ),
    )
}
