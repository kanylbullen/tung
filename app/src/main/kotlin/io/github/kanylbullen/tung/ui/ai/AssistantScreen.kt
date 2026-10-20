package io.github.kanylbullen.tung.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.ai.AiPrepared
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.AiRequest
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.ui.common.PrimaryButton
import io.github.kanylbullen.tung.ui.common.SecondaryButton
import io.github.kanylbullen.tung.ui.common.appContainer
import io.github.kanylbullen.tung.ui.common.exerciseDisplayName
import io.github.kanylbullen.tung.ui.common.factoryOf
import io.github.kanylbullen.tung.ui.common.localWeight
import io.github.kanylbullen.tung.ui.theme.Tung
import io.github.kanylbullen.tung.ui.theme.TungCorner
import java.net.URI
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The in-app assistant (BYOK), reached from the row at the bottom of Home:
 * three questions, the answer as a proposal, and a button that turns it into
 * routines and a program, or into the workout in progress. Plateaus are only
 * shown. The menu's "Show what is sent" reviews every request before it goes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    /** What the user said in the workout that the voice grammar did not understand; asked as [AiRequest.FreeText] once. */
    ask: String? = null,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onProgramSaved: () -> Unit,
    onOpenWorkout: (String) -> Unit,
) {
    val container = appContainer()
    val vm: AssistantViewModel = viewModel(factory = factoryOf { AssistantViewModel(container.ai, container.aiSettings) })
    val state by vm.state.collectAsStateWithLifecycle()
    val language = LocalConfiguration.current.locales[0].toLanguageTag()
    var menuOpen by remember { mutableStateOf(false) }
    // The same path as the buttons: "What is sent" first when it is due, then the proposal.
    LaunchedEffect(ask, state.configured) {
        if (ask != null && state.configured == true) vm.askOnce(AiRequest.FreeText(ask), language)
    }

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
                title = { Text(stringResource(R.string.ai_title)) },
                actions = {
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
                                text = { Text(stringResource(R.string.ai_show_payload)) },
                                trailingIcon = {
                                    if (state.alwaysShowPayload) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.ai_show_payload_on))
                                },
                                onClick = {
                                    menuOpen = false
                                    vm.setAlwaysShowPayload(!state.alwaysShowPayload)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.ai_intro), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
            if (ask != null) {
                Text(stringResource(R.string.ai_voice_heard, ask), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
            }
            if (state.configured == false) {
                Text(stringResource(R.string.ai_not_configured), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
                SecondaryButton(text = stringResource(R.string.ai_open_settings), onClick = onOpenSettings)
            } else {
                val idle = state.busy == null && state.configured == true && !state.saving
                AskButton(R.string.ai_ask_program, idle) { vm.ask(AiRequest.Program, language) }
                AskButton(R.string.ai_ask_next, idle) { vm.ask(AiRequest.NextWorkout, language) }
                AskButton(R.string.ai_ask_plateaus, idle) { vm.ask(AiRequest.Plateaus, language) }
            }
            if (state.sending) {
                Text(stringResource(R.string.ai_asking), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
            }
            state.failure?.let { failure ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(aiFailureText(failure.failure)), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.accentText)
                    failure.message?.let {
                        Text(stringResource(R.string.ai_error_provider_says, it), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                    }
                }
            }
            when (val proposal = state.proposal) {
                null -> Unit
                is AiProposal.Program -> {
                    val defaultName = stringResource(R.string.ai_program_default_name)
                    ProgramProposal(proposal, defaultName)
                    Dropped(proposal.dropped)
                    PrimaryButton(
                        text = stringResource(R.string.ai_save_program),
                        onClick = { vm.saveProgram(defaultName, onProgramSaved) },
                        enabled = !state.saving,
                    )
                    Text(stringResource(R.string.ai_save_program_hint), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
                }
                is AiProposal.NextWorkout -> {
                    WorkoutProposal(proposal)
                    Dropped(proposal.dropped)
                    PrimaryButton(
                        text = stringResource(R.string.ai_start_workout),
                        onClick = { vm.startWorkout(onOpenWorkout) },
                        enabled = !state.saving,
                    )
                }
                is AiProposal.Plateaus -> {
                    PlateausAnswer(proposal)
                    Dropped(proposal.dropped)
                }
            }
            if (state.saveFailed) {
                Text(stringResource(R.string.ai_save_failed), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.accentText)
            }
        }
    }

    state.review?.let { prepared ->
        PayloadSheet(prepared, onSend = vm::sendReviewed, onCancel = vm::cancelReview)
    }
}

/** Three equal choices: none is the one bold move, so all are the quiet button. */
@Composable
private fun AskButton(label: Int, enabled: Boolean, onClick: () -> Unit) {
    SecondaryButton(text = stringResource(label), onClick = onClick, enabled = enabled)
}

@Composable
private fun Rationale(text: String) {
    if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
}

@Composable
private fun Dropped(count: Int) {
    if (count > 0) {
        Text(pluralStringResource(R.plurals.ai_dropped, count, count), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
    }
}

@Composable
private fun name(exercise: ExerciseCandidate): String = exerciseDisplayName(exercise.nameKey, exercise.displayName)

@Composable
private fun ProgramProposal(proposal: AiProposal.Program, defaultName: String) {
    Rationale(proposal.rationale)
    Text(proposal.programName ?: defaultName, style = MaterialTheme.typography.titleLarge, color = Tung.colors.text)
    proposal.routines.forEach { routine ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(routine.name, style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            routine.exercises.forEach { e ->
                val target = e.targetReps?.let { stringResource(R.string.set_value, e.targetSets.toString(), it) }
                    ?: pluralStringResource(R.plurals.ai_target_sets, e.targetSets, e.targetSets)
                ExerciseLine(name(e.exercise), target)
            }
        }
    }
}

@Composable
private fun WorkoutProposal(proposal: AiProposal.NextWorkout) {
    Rationale(proposal.rationale)
    val separator = stringResource(R.string.list_separator)
    val noWeight = stringResource(R.string.ai_set_from_last_time)
    proposal.exercises.forEach { e ->
        val sets = e.sets.map { set ->
            val kg = set.weightKg?.let(::localWeight) ?: noWeight
            set.reps?.let { stringResource(R.string.set_value, kg, it) } ?: kg
        }
        ExerciseLine(name(e.exercise), sets.joinToString(separator))
    }
}

@Composable
private fun PlateausAnswer(proposal: AiProposal.Plateaus) {
    if (proposal.plateaus.isEmpty()) {
        Text(stringResource(R.string.ai_plateaus_none), style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        return
    }
    val dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    proposal.plateaus.forEach { p ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name(p.exercise), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            p.since?.let { Text(stringResource(R.string.ai_plateau_since, dates.format(it)), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted) }
            if (p.observation.isNotBlank()) Text(p.observation, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
            if (p.suggestion.isNotBlank()) Text(p.suggestion, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text)
        }
    }
}

@Composable
private fun ExerciseLine(name: String, numbers: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(name, style = MaterialTheme.typography.bodyLarge, color = Tung.colors.text, modifier = Modifier.weight(1f))
        Text(numbers, style = Tung.numbers.inline, color = Tung.colors.muted)
    }
}

/**
 * "What is sent": the request exactly as it goes out, the body indented, with
 * the address it goes to. The key travels in a header and is not shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayloadSheet(prepared: AiPrepared, onSend: () -> Unit, onCancel: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val host = runCatching { URI(prepared.http.url).host }.getOrNull() ?: prepared.http.url
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheetState,
        shape = TungCorner,
        containerColor = Tung.colors.raised,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.ai_payload_title), style = MaterialTheme.typography.titleMedium, color = Tung.colors.text)
            Text(stringResource(R.string.ai_payload_intro, host), style = MaterialTheme.typography.bodyMedium, color = Tung.colors.muted)
            Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                SelectionContainer {
                    Text(
                        "POST ${prepared.http.url}\n\n" + prepared.previewBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = Tung.colors.text,
                    )
                }
            }
            PrimaryButton(text = stringResource(R.string.ai_payload_send), onClick = onSend)
            SecondaryButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        }
    }
}
