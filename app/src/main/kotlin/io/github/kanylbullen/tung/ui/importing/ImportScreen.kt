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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ai.AiConfidence
import io.github.kanylbullen.tung.core.ai.ProposedMatch
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.importing.NameUsage
import io.github.kanylbullen.tung.ui.ai.PayloadSheet
import io.github.kanylbullen.tung.ui.ai.aiFailureText
import io.github.kanylbullen.tung.core.importing.ImportBundle
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.core.importing.SkipReason
import io.github.kanylbullen.tung.importing.ImportResult
import io.github.kanylbullen.tung.ui.common.ConfidenceTag
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
 * source's name, or skip. Each name says how much of the export uses it, and
 * offers up to two offline suggestions as chips; with a provider set up,
 * "Suggest with the assistant" asks once and puts a proposal on each row.
 * Every suggestion waits for a tap. A decided row collapses to its decision
 * and "Change" (issue #77), and "8 of 11 decided" counts them. Import is
 * enabled once each name is decided; the summary offers "Undo import".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(vm: ImportViewModel, onBack: () -> Unit, onPick: () -> Unit, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    // The language the app shows, not the phone's locale: en-SE is English (issue #75).
    val language = stringResource(R.string.ai_language_tag)
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
                        item(key = "review-actions") {
                            ReviewActions(
                                state = state,
                                onSuggest = { vm.suggestWithAssistant(language) },
                                onAcceptAll = vm::acceptAllHighConfidence,
                                onCreateAll = { vm.decideRemaining(ImportChoice.CreateCustom) },
                                onSkipAll = { vm.decideRemaining(ImportChoice.Skip) },
                            )
                        }
                        item(key = "review-count") {
                            Muted(stringResource(R.string.import_decided_count, state.decidedCount, preview.unmatched.size))
                        }
                        items(preview.unmatched, key = { "review-$it" }) { name ->
                            val choice = state.choices[name]
                            if (choice != null && state.collapsed(name)) {
                                DecidedRow(name = name, choice = choice, usage = preview.usage[name], onChange = { vm.reopen(name) })
                                return@items
                            }
                            ReviewRow(
                                name = name,
                                choice = choice,
                                usage = preview.usage[name],
                                suggestions = preview.suggestions[name].orEmpty().take(2),
                                proposal = state.assist.matches[name],
                                onUse = { vm.use(name, it) },
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

    state.assist.review?.let { prepared ->
        PayloadSheet(prepared, onSend = vm::sendReviewed, onCancel = vm::cancelReview)
    }
}

/**
 * Above the review's rows: the assistant (when set up) with its progress,
 * failure and "Accept all high-confidence", then the bulk decisions for the
 * names still undecided.
 */
@Composable
private fun ReviewActions(
    state: ImportUiState,
    onSuggest: () -> Unit,
    onAcceptAll: () -> Unit,
    onCreateAll: () -> Unit,
    onSkipAll: () -> Unit,
) {
    val idle = state.phase == ImportPhase.Preview
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.assistantConfigured) {
            SecondaryButton(text = stringResource(R.string.import_assist_action), onClick = onSuggest, enabled = idle && !state.assist.busy)
            if (state.assist.sending) Muted(stringResource(R.string.import_assist_asking))
            state.assist.failure?.let { failure ->
                Text(stringResource(aiFailureText(failure.failure)), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
                failure.message?.let { Muted(stringResource(R.string.ai_error_provider_says, it)) }
            }
            if (state.highConfidence.isNotEmpty()) {
                SecondaryButton(text = stringResource(R.string.import_assist_accept_all), onClick = onAcceptAll, enabled = idle)
            }
        }
        if (state.undecided.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource(R.string.import_create_remaining), selected = false, onClick = onCreateAll)
                Choice(stringResource(R.string.import_skip_remaining), selected = false, onClick = onSkipAll)
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

/** "In 12 workouts" or "Only in routines" under a review name. */
@Composable
private fun UsageLine(usage: NameUsage?) {
    usage?.let {
        Muted(
            if (it.workouts > 0) {
                pluralStringResource(R.plurals.import_used_in_workouts, it.workouts, it.workouts)
            } else {
                stringResource(R.string.import_only_in_routines)
            },
        )
    }
}

/**
 * A decided row (issue #77): the name, its usage and the decision in accent
 * text with a check, read as one node ("Machine Seated Row: imported as
 * Leverage Iso Row"), and a quiet "Change" that opens the row's options
 * again. No filled button here: nothing on a decided row asks for a tap.
 */
@Composable
private fun DecidedRow(name: String, choice: ImportChoice, usage: NameUsage?, onChange: () -> Unit) {
    val (line, description) = when (choice) {
        is ImportChoice.Use -> {
            val exercise = exerciseDisplayName(choice.exercise.nameKey, choice.exercise.displayName)
            stringResource(R.string.import_decided_use, exercise) to stringResource(R.string.import_decided_use_description, name, exercise)
        }
        ImportChoice.CreateCustom ->
            stringResource(R.string.import_decided_create, name) to stringResource(R.string.import_decided_create_description, name)
        ImportChoice.Skip ->
            stringResource(R.string.import_decided_skip) to stringResource(R.string.import_decided_skip_description, name)
    }
    val changeDescription = stringResource(R.string.import_change_description, name)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Tung.colors.text)
        UsageLine(usage)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                line,
                style = MaterialTheme.typography.bodyLarge,
                color = Tung.colors.accentText,
                modifier = Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
            )
            TextButton(
                onClick = onChange,
                // The description names the row: a list of "Change" buttons is no help to a screen reader.
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = changeDescription },
            ) {
                Text(stringResource(R.string.import_change), color = Tung.colors.text)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewRow(
    name: String,
    choice: ImportChoice?,
    usage: NameUsage?,
    suggestions: List<ExerciseCandidate>,
    proposal: ProposedMatch?,
    onUse: (ExerciseCandidate) -> Unit,
    onPick: () -> Unit,
    onCreate: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = Tung.colors.text)
        UsageLine(usage)
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
        val chosenId = (choice as? ImportChoice.Use)?.exercise?.id
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { s ->
                    Choice(
                        stringResource(R.string.import_suggestion, exerciseDisplayName(s.nameKey, s.displayName)),
                        selected = chosenId == s.id,
                        onClick = { onUse(s) },
                    )
                }
            }
        }
        proposal?.let { p ->
            val exercise = p.exercise
            if (exercise == null) {
                Muted(stringResource(R.string.import_assist_no_match))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.import_assist_suggests, exerciseDisplayName(exercise.nameKey, exercise.displayName)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Tung.colors.text,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ConfidenceTag(p.confidence)
                }
            }
            if (p.note.isNotBlank()) Muted(p.note)
            if (exercise != null) {
                // The loud button only where the assistant is sure; once accepted the row says "As …" and Accept rests.
                val accept = stringResource(R.string.import_assist_accept)
                val accepted = chosenId == exercise.id
                if (p.confidence == AiConfidence.HIGH) {
                    PrimaryButton(text = accept, onClick = { onUse(exercise) }, enabled = !accepted)
                } else {
                    SecondaryButton(text = accept, onClick = { onUse(exercise) }, enabled = !accepted)
                }
            }
        }
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
