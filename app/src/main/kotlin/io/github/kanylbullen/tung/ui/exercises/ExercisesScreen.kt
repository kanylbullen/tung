package io.github.kanylbullen.tung.ui.exercises

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.data.ExtendedExercise
import io.github.kanylbullen.tung.domain.Equipment
import io.github.kanylbullen.tung.domain.MuscleGroup
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.displayName
import io.github.kanylbullen.tung.ui.common.equipmentLabel
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.muscleGroupLabel
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner

/**
 * The exercise catalogue. With [workoutId] it is the picker: tapping an
 * exercise adds it to that workout and calls [onPicked].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisesScreen(
    workoutId: String?,
    onBack: (() -> Unit)?,
    onPicked: () -> Unit,
) {
    val container = appContainer()
    val appContext = LocalContext.current.applicationContext
    val vm: ExercisesViewModel = viewModel(
        factory = factoryOf {
            ExercisesViewModel(
                container.exerciseRepository,
                container.workoutRepository,
                container.extendedCatalog,
                workoutId,
            ) {
                it.displayName(appContext)
            }
        }
    )
    val locale = LocalConfiguration.current.locales[0]
    LaunchedEffect(locale) { vm.setLocale(locale) }
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = Tung.colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Tung.colors.background,
                    scrolledContainerColor = Tung.colors.background,
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
                        color = Tung.colors.muted,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
            state.groups.forEach { group ->
                item(key = "group-${group.muscleGroup.key}") {
                    SectionHeader(muscleGroupLabel(group.muscleGroup))
                }
                items(group.exercises, key = { "ex-${it.exercise.id}" }) { named ->
                    ExerciseRow(
                        exercise = named,
                        onClick = if (vm.isPicker) ({ vm.pick(named.exercise.id, onPicked) }) else null,
                    )
                }
            }
            if (state.more.isNotEmpty()) {
                item(key = "more") {
                    SectionHeader(stringResource(R.string.picker_more))
                }
                items(state.more, key = { "ext-${it.id}" }) { entry ->
                    ExtendedRow(entry, onClick = { vm.pickExtended(entry, onPicked) })
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
        shape = TungCorner,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Tung.colors.raised,
    unfocusedContainerColor = Tung.colors.raised,
    focusedBorderColor = Tung.colors.muted,
    unfocusedBorderColor = Tung.colors.border,
    focusedTextColor = Tung.colors.text,
    unfocusedTextColor = Tung.colors.text,
    cursorColor = Tung.colors.accent,
    focusedLeadingIconColor = Tung.colors.muted,
    unfocusedLeadingIconColor = Tung.colors.muted,
    focusedTrailingIconColor = Tung.colors.muted,
    unfocusedTrailingIconColor = Tung.colors.muted,
    focusedPlaceholderColor = Tung.colors.muted,
    unfocusedPlaceholderColor = Tung.colors.muted,
    focusedLabelColor = Tung.colors.muted,
    unfocusedLabelColor = Tung.colors.muted,
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Tung.colors.muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ExerciseRow(exercise: NamedExercise, onClick: (() -> Unit)?) {
    val detail = buildList {
        add(equipmentLabel(Equipment.fromKey(exercise.exercise.equipment)))
        if (exercise.exercise.isCustom) add(stringResource(R.string.exercises_custom))
    }.joinToString(stringResource(R.string.row_separator))
    NameRow(exercise.displayName, detail, onClick)
}

/** The extended list is not grouped, so the row names the muscle group as well as the equipment. */
@Composable
private fun ExtendedRow(entry: ExtendedExercise, onClick: () -> Unit) {
    val detail = listOf(
        muscleGroupLabel(MuscleGroup.fromKey(entry.muscleGroup)),
        equipmentLabel(Equipment.fromKey(entry.equipment)),
    ).joinToString(stringResource(R.string.row_separator))
    NameRow(entry.displayName, detail, onClick)
}

@Composable
private fun NameRow(name: String, detail: String, onClick: (() -> Unit)?) {
    val base = Modifier.fillMaxWidth()
    Row(
        modifier = (if (onClick != null) base.clickable(onClick = onClick) else base)
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            color = Tung.colors.text,
            modifier = Modifier.weight(1f),
        )
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
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
        containerColor = Tung.colors.raised,
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
                    shape = TungCorner,
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
                Text(stringResource(R.string.create_save), color = Tung.colors.accentText)
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
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = TungCorner,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Tung.colors.raised,
            labelColor = Tung.colors.text,
            selectedContainerColor = Tung.colors.text,
            selectedLabelColor = Tung.colors.background,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Tung.colors.muted,
            selectedBorderColor = Tung.colors.text,
        ),
    )
}
