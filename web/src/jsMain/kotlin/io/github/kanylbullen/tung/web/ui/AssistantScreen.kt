package io.github.kanylbullen.tung.web.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.AiRequest
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.PlannedSet
import io.github.kanylbullen.tung.web.WebContainer
import io.github.kanylbullen.tung.web.ai.AiOutcome
import io.github.kanylbullen.tung.web.ai.AiPrepared
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import kotlinx.browser.window
import kotlinx.coroutines.launch
import org.jetbrains.compose.web.attributes.InputType
import org.jetbrains.compose.web.attributes.disabled
import org.jetbrains.compose.web.dom.A
import org.jetbrains.compose.web.dom.Button
import org.jetbrains.compose.web.dom.CheckboxInput
import org.jetbrains.compose.web.dom.Code
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H2
import org.jetbrains.compose.web.dom.Input
import org.jetbrains.compose.web.dom.Label
import org.jetbrains.compose.web.dom.Li
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Pre
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Ul

private sealed interface AskPhase {
    data object Idle : AskPhase
    data class Showing(val prepared: AiPrepared) : AskPhase
    data object Asking : AskPhase
    data class Answer(val proposal: AiProposal) : AskPhase
    data class Failed(val message: String) : AskPhase
}

/**
 * The Assistant (`#/assistant`), the phone's: three questions answered by
 * the user's own provider with their own key, each sent only on a tap. The
 * first request ever, and every one while "Show what is sent" is on, is
 * shown in full first (the key is a header and never shown). A program is
 * saved and a workout started only on Save program or Start this workout,
 * through the commands; plateaus are only shown.
 */
@Composable
fun AssistantScreen(container: WebContainer) {
    val strings = container.strings
    val ai = container.ai
    val config = ai.settings.config.collectAsState().value
    val alwaysShow = ai.settings.alwaysShow.collectAsState().value
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf<AskPhase>(AskPhase.Idle) }

    fun send(prepared: AiPrepared) {
        ai.settings.markFirstShown()
        phase = AskPhase.Asking
        scope.launch {
            phase = when (val outcome = ai.send(prepared)) {
                is AiOutcome.Proposed -> AskPhase.Answer(outcome.proposal)
                is AiOutcome.Failed -> AskPhase.Failed(
                    listOfNotNull(strings.aiFailure(outcome.failure), outcome.message?.let(strings::providerSays)).joinToString(" "),
                )
            }
        }
    }

    fun ask(request: AiRequest) {
        val prepared = ai.prepare(request, strings.tag) ?: return
        if (!ai.settings.firstShown || alwaysShow) phase = AskPhase.Showing(prepared) else send(prepared)
    }

    Div({ classes("screen") }) {
        Div({ classes("topbar", "topbar-start") }) {
            A(href = Route.HOME.hash, attrs = { classes("text-button"); attr("aria-label", strings.home) }) { Text("←") }
            H1({ classes("detail-title") }) { Text(strings.assistant) }
        }
        if (!config.configured) {
            P({ classes("hint") }) { Text(strings.aiNotConfigured) }
            A(href = Route.SETTINGS.hash, attrs = { classes("text-button", "pad-link") }) { Text(strings.openSettings) }
            return@Div
        }
        P({ classes("hint") }) { Text(strings.aiIntro) }
        val busy = phase is AskPhase.Asking
        Div({ classes("ask-buttons") }) {
            listOf(AiRequest.Program to strings.askProgram, AiRequest.NextWorkout to strings.askNext, AiRequest.Plateaus to strings.askPlateaus).forEach { (request, label) ->
                Button({ classes("secondary"); if (busy) disabled(); onClick { ask(request) } }) { Text(label) }
            }
        }
        Label(attrs = { classes("check", "pad") }) {
            CheckboxInput(alwaysShow) { onChange { ai.settings.setAlwaysShow(!alwaysShow) } }
            Span { Text(strings.showWhatIsSent) }
        }
        when (val current = phase) {
            AskPhase.Asking -> P({ classes("hint"); attr("role", "status") }) { Text(strings.aiAsking) }
            is AskPhase.Failed -> P({ classes("error"); attr("role", "alert") }) { Text(current.message) }
            is AskPhase.Answer -> Proposal(container, current.proposal)
            else -> Unit
        }
    }

    (phase as? AskPhase.Showing)?.let { showing ->
        val prepared = showing.prepared
        Div({ classes("sheet-backdrop"); onClick { phase = AskPhase.Idle } })
        Div({ classes("sheet", "payload"); attr("role", "dialog"); attr("aria-modal", "true"); attr("aria-label", strings.payloadTitle) }) {
            Div({ classes("sheet-head") }) {
                H2 { Text(strings.payloadTitle) }
                Button({ classes("text-button"); onClick { phase = AskPhase.Idle } }) { Text(strings.cancel) }
            }
            P({ classes("hint") }) { Text(strings.payloadIntro(hostOf(prepared.http.url))) }
            Pre({ classes("payload-body") }) {
                Code { Text("POST " + prepared.http.url + "\n" + ai.previewHeaders(prepared).entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n\n" + prepared.previewBody) }
            }
            Button({ classes("primary"); onClick { send(prepared) } }) { Text(strings.send) }
        }
    }
}

private fun hostOf(url: String): String = url.substringAfter("://").substringBefore("/")

@Composable
private fun Proposal(container: WebContainer, proposal: AiProposal) {
    val strings = container.strings
    val locale = container.locale
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    fun name(candidate: ExerciseCandidate): String =
        container.store.exercises.get(candidate.id)?.let { exerciseDisplayName(it, container.catalogueNames, strings.tag) } ?: candidate.displayName
    fun setText(set: PlannedSet): String = when {
        set.weightKg == null && set.reps == null -> strings.setFromLastTime
        else -> (set.weightKg?.let { weightText(it, locale) + " kg" } ?: "?") + " × " + (set.reps?.toString() ?: "?")
    }

    Div({ classes("proposal") }) {
        when (proposal) {
            is AiProposal.Program -> {
                if (proposal.rationale.isNotBlank()) P({ classes("hint") }) { Text(proposal.rationale) }
                proposal.routines.forEach { routine ->
                    H2({ classes("section") }) { Text(routine.name) }
                    Ul({ classes("plain-list") }) {
                        routine.exercises.forEach { e ->
                            Li { Text(name(e.exercise) + strings.separator + strings.targetSets(e.targetSets) + (e.targetReps?.let { " × $it" } ?: "")) }
                        }
                    }
                }
                var programName by remember(proposal) { mutableStateOf(proposal.programName ?: strings.programDefaultName) }
                Label(attrs = { classes("field", "pad") }) {
                    Span { Text(strings.routineName) }
                    Input(InputType.Text) { value(programName); onInput { programName = it.value } }
                }
                P({ classes("hint") }) { Text(strings.saveProgramHint) }
                Button({
                    classes("primary")
                    if (programName.isBlank()) disabled()
                    onClick {
                        scope.launch {
                            val id = container.ai.confirm(proposal, programName.trim())
                            if (id != null) window.location.hash = programHash(id) else failed = true
                        }
                    }
                }) { Text(strings.saveProgram) }
            }
            is AiProposal.NextWorkout -> {
                if (proposal.rationale.isNotBlank()) P({ classes("hint") }) { Text(proposal.rationale) }
                proposal.exercises.forEach { e ->
                    H2({ classes("section") }) { Text(name(e.exercise)) }
                    Ul({ classes("plain-list", "numbers") }) { e.sets.forEach { Li { Text(setText(it)) } } }
                }
                Button({
                    classes("primary")
                    onClick { scope.launch { if (container.ai.confirm(proposal)) go(Route.WORKOUT) else failed = true } }
                }) { Text(strings.startThisWorkout) }
            }
            is AiProposal.Plateaus -> {
                if (proposal.plateaus.isEmpty()) P({ classes("hint") }) { Text(strings.plateausNone) }
                proposal.plateaus.forEach { p ->
                    key(p.exercise.id) {
                        H2({ classes("section") }) { Text(name(p.exercise)) }
                        p.since?.let { P({ classes("hint") }) { Text(strings.plateauSince(shortMonthDay(it, locale))) } }
                        P({ classes("hint") }) { Text(p.observation) }
                        P({ classes("hint", "accent") }) { Text(p.suggestion) }
                    }
                }
            }
            is AiProposal.Matches -> Unit
        }
        if (proposal.dropped > 0) P({ classes("hint") }) { Text(strings.aiDropped(proposal.dropped)) }
        if (failed) P({ classes("error"); attr("role", "alert") }) { Text(strings.saveFailed) }
    }
}
