package io.github.kanylbullen.tung.ui.importing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.SkipReason
import io.github.kanylbullen.tung.importing.ImportResult
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.localDateWithYear
import io.github.kanylbullen.tung.ui.theme.Tung

/**
 * The import's preview, review and summary (CLAUDE.md, "Import"). The
 * preview shows what the file holds (dates, workouts, sets, routines), how
 * set types and skipped rows are handled, and the names that need a decision:
 * choose an exercise ([onPick] opens the picker), create a custom one with the
 * source's name, or skip. Import is enabled once each name is decided; the
 * summary offers "Undo import".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: ImportViewModel, onBack: () -> Unit, onPick: () -> Unit, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
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
                title = { Text(stringResource(R.string.import_title)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        ) {
            when (state.phase) {
                ImportPhase.Loading -> item(key = "loading") { Muted(stringResource(R.string.import_reading)) }
                ImportPhase.NotRecognised -> item(key = "error") { Body(stringResource(R.string.import_not_recognised)) }
                ImportPhase.Unreadable -> item(key = "error") { Body(stringResource(R.string.import_unreadable)) }
                ImportPhase.Empty -> item(key = "error") { Body(stringResource(R.string.import_empty)) }
                ImportPhase.Preview, ImportPhase.Importing -> {
                    val preview = state.preview ?: return@LazyColumn
                    item(key = "overview") { Overview(preview.bundle) }
                    if (preview.unmatched.isNotEmpty()) {
                        item(key = "review-title") {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Heading(stringResource(R.string.import_review_title))
                                Muted(stringResource(R.string.import_review_hint))
                            }
                        }
                        items(preview.unmatched, key = { "review-$it" }) { name ->
                            ReviewRow(
                                name = name,
                                choice = state.choices[name],
                                onPick = {
                                    vm.startPicking(name)
                                    onPick()
                                },
                                onCreate = { vm.choose(name, ImportChoice.CreateCustom) },
                                onSkip = { vm.choose(name, ImportChoice.Skip) },
                            )
                        }
                    }
                    item(key = "notes") { Notes(preview.bundle) }
                    item(key = "action") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.undecided.isNotEmpty()) Muted(stringResource(R.string.import_decide_first))
                            PrimaryButton(
                                text = stringResource(if (state.phase == ImportPhase.Importing) R.string.import_importing else R.string.import_action),
                                onClick = vm::import,
                                enabled = state.canImport,
                            )
                        }
                    }
                }
                ImportPhase.Done, ImportPhase.Undoing -> {
                    val result = state.result ?: return@LazyColumn
                    item(key = "done") { Summary(result) }
                    item(key = "done-actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton(text = stringResource(R.string.import_done), onClick = onDone)
                            SecondaryButton(
                                text = stringResource(R.string.import_undo),
                                onClick = vm::undo,
                                enabled = state.phase == ImportPhase.Done && (result.workoutsAdded > 0 || result.routineIds.isNotEmpty()),
                            )
                        }
                    }
                }
                ImportPhase.Undone -> {
                    item(key = "undone") { Body(pluralStringResource(R.plurals.import_undone, state.removed, state.removed)) }
                    item(key = "undone-done") { PrimaryButton(text = stringResource(R.string.import_done), onClick = onDone) }
                }
            }
        }
    }
}

@Composable
private fun Overview(bundle: ImportBundle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        bundle.dateRange?.let { range ->
            Body(stringResource(R.string.import_date_range, localDateWithYear(range.first), localDateWithYear(range.last)))
        }
        Text(
            counts(bundle.workouts.size, bundle.setCount, bundle.routines.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Tung.colors.text,
        )
    }
}

@Composable
private fun counts(workouts: Int, sets: Int, routines: Int): String = listOf(
    pluralStringResource(R.plurals.import_workouts, workouts, workouts),
    pluralStringResource(R.plurals.import_sets, sets, sets),
    pluralStringResource(R.plurals.import_routines, routines, routines),
).joinToString(stringResource(R.string.row_separator))

@Composable
private fun ReviewRow(name: String, choice: ImportChoice?, onPick: () -> Unit, onCreate: () -> Unit, onSkip: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Tung.colors.text)
        Muted(
            when (choice) {
                null -> stringResource(R.string.import_chosen_none)
                is ImportChoice.Use -> stringResource(
                    R.string.import_chosen_use,
                    exerciseDisplayName(choice.exercise.nameKey, choice.exercise.displayName),
                )
                ImportChoice.CreateCustom -> stringResource(R.string.import_chosen_create, name)
                ImportChoice.Skip -> stringResource(R.string.import_chosen_skip)
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(stringResource(R.string.import_choice_pick), selected = choice is ImportChoice.Use, onClick = onPick)
            Choice(stringResource(R.string.import_choice_create), selected = choice == ImportChoice.CreateCustom, onClick = onCreate)
            Choice(stringResource(R.string.import_choice_skip), selected = choice == ImportChoice.Skip, onClick = onSkip)
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = io.github.kanylbullen.tung.ui.theme.TungCorner,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Tung.colors.raised,
            labelColor = Tung.colors.text,
            selectedContainerColor = Tung.colors.accent,
            selectedLabelColor = Tung.colors.onAccent,
        ),
    )
}

/** Set types imported as ordinary sets, and what was left out. */
@Composable
private fun Notes(bundle: ImportBundle) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        bundle.setTypes.forEach { (type, count) ->
            Muted(
                when (type) {
                    "warm-up" -> pluralStringResource(R.plurals.import_note_warm_up, count, count)
                    "drop" -> pluralStringResource(R.plurals.import_note_drop, count, count)
                    "failure" -> pluralStringResource(R.plurals.import_note_failure, count, count)
                    else -> pluralStringResource(R.plurals.import_note_other, count, count, type)
                },
            )
        }
        val skipped = bundle.skipped.filterValues { it > 0 }
        if (skipped.isNotEmpty()) {
            Heading(stringResource(R.string.import_skipped_title))
            SkipReason.entries.forEach { reason ->
                skipped[reason]?.let { Muted(pluralStringResource(skipReasonText(reason), it, it)) }
            }
        }
    }
}

private fun skipReasonText(reason: SkipReason): Int = when (reason) {
    SkipReason.SESSION_WITHOUT_EXERCISES -> R.plurals.import_skip_session_without_exercises
    SkipReason.SESSION_WITHOUT_TIME -> R.plurals.import_skip_session_without_time
    SkipReason.CARDIO -> R.plurals.import_skip_cardio
    SkipReason.CARDIO_LOG -> R.plurals.import_skip_cardio_log
    SkipReason.LOG_WITHOUT_SETS -> R.plurals.import_skip_log_without_sets
    SkipReason.SET_WITHOUT_REPS -> R.plurals.import_skip_set_without_reps
    SkipReason.SET_UNREADABLE -> R.plurals.import_skip_set_unreadable
    SkipReason.SET_WITHOUT_LOG -> R.plurals.import_skip_set_without_log
    SkipReason.STRAY_HEADER -> R.plurals.import_skip_stray_header
    SkipReason.ROUTINE_WITHOUT_EXERCISES -> R.plurals.import_skip_routine_without_exercises
    SkipReason.DUPLICATE_ROUTINE -> R.plurals.import_skip_duplicate_routine
}

@Composable
private fun Summary(result: ImportResult) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Heading(stringResource(R.string.import_done_title))
        Body(stringResource(R.string.import_done_added, counts(result.workoutsAdded, result.setsAdded, result.routineIds.size)))
        if (result.workoutsAlreadyThere > 0) {
            Muted(pluralStringResource(R.plurals.import_done_already, result.workoutsAlreadyThere, result.workoutsAlreadyThere))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = Tung.colors.muted, modifier = Modifier.semantics { heading() })
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
}
