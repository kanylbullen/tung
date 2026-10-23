package io.github.kanylbullen.tung.ui.importing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.ai.AiFailure
import io.github.kanylbullen.tung.ai.AiOutcome
import io.github.kanylbullen.tung.ai.AiPrepared
import io.github.kanylbullen.tung.ai.AiService
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.core.ai.AiConfidence
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.ProposedMatch
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.importing.ImportChoice
import io.github.kanylbullen.tung.importing.ImportPreview
import io.github.kanylbullen.tung.importing.ImportRead
import io.github.kanylbullen.tung.importing.ImportResult
import io.github.kanylbullen.tung.importing.ImportService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ImportPhase { Loading, NotRecognised, Unreadable, Empty, Preview, Importing, Done, Undoing, Undone }

/** "Suggest with the assistant" in the review: what is asked, shown and answered. Nothing in it is a decision. */
data class ImportAssistState(
    /** True from the tap until the answer or the cancel. */
    val busy: Boolean = false,
    /** True while the request is out with the provider. */
    val sending: Boolean = false,
    /** The request in "What is sent", before it goes out. */
    val review: AiPrepared? = null,
    /** The proposals by review name; a null exercise is "no good match". */
    val matches: Map<String, ProposedMatch> = emptyMap(),
    val failure: AiOutcome.Failed? = null,
)

data class ImportUiState(
    val phase: ImportPhase = ImportPhase.Loading,
    val preview: ImportPreview? = null,
    /** The review's decisions, for the names in [ImportPreview.unmatched] only. */
    val choices: Map<String, ImportChoice> = emptyMap(),
    /**
     * Decided names whose row "Change" opened again (issue #77). The decision
     * stays until a new one is made, which collapses the row again.
     */
    val reopened: Set<String> = emptySet(),
    val result: ImportResult? = null,
    val removed: Int = 0,
    /** A BYOK provider is set up, so "Suggest with the assistant" is offered. */
    val assistantConfigured: Boolean = false,
    val assist: ImportAssistState = ImportAssistState(),
) {
    val undecided: List<String> get() = preview?.unmatched.orEmpty().filter { it !in choices }
    val canImport: Boolean get() = phase == ImportPhase.Preview && undecided.isEmpty()

    /** "8 of 11 decided" above the rows. */
    val decidedCount: Int get() = preview?.unmatched.orEmpty().count { it in choices }

    /** A decided row shows only its decision and "Change" (issue #77). */
    fun collapsed(name: String): Boolean = name in choices && name !in reopened

    /** The undecided names the assistant matched with high confidence: what "Accept all high-confidence" decides. */
    val highConfidence: List<String> get() =
        undecided.filter { name -> assist.matches[name]?.let { it.exercise != null && it.confidence == AiConfidence.HIGH } == true }
}

/**
 * The import from a file: read and parse it ([ImportService.read], off the
 * main thread), show the preview with the review of unmatched names, then
 * import and offer to undo. [readFile] gives the file's text, or null when it
 * cannot be read; the screen reads it through the content resolver.
 *
 * The review offers the offline suggestions and, with a provider set up, one
 * assistant request on a tap ([suggestWithAssistant], the Assistant's rules:
 * the first request is shown first). Every suggestion and proposal is only
 * shown: a decision is made by a tap, never by an answer arriving.
 */
class ImportViewModel(
    private val imports: ImportService,
    private val ai: AiService? = null,
    private val aiSettings: AiSettings? = null,
    private val readFile: suspend () -> String?,
) : ViewModel() {

    private val local = MutableStateFlow(ImportUiState())

    val state: StateFlow<ImportUiState> =
        combine(local, aiSettings?.config?.map { it.configured } ?: flowOf(false)) { s, configured ->
            s.copy(assistantConfigured = configured && ai != null)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ImportUiState())

    /** The name a "Choose" opened the picker for; the picker's answer goes to it. */
    private var pickingFor: String? = null

    init {
        viewModelScope.launch {
            val text = runCatching { readFile() }.getOrNull()
            if (text == null) {
                local.update { it.copy(phase = ImportPhase.Unreadable) }
                return@launch
            }
            when (val read = imports.read(text)) {
                ImportRead.NotRecognised -> local.update { it.copy(phase = ImportPhase.NotRecognised) }
                is ImportRead.Ready -> {
                    val empty = read.preview.bundle.workouts.isEmpty() && read.preview.bundle.routines.isEmpty()
                    local.update { it.copy(phase = if (empty) ImportPhase.Empty else ImportPhase.Preview, preview = read.preview) }
                }
            }
        }
    }

    /** Every decision path ends here or in the bulk ones below, and each collapses the row it decides. */
    fun choose(name: String, choice: ImportChoice) {
        local.update { it.copy(choices = it.choices + (name to choice), reopened = it.reopened - name) }
    }

    /** "Change" on a decided row: shows the row's options again; the decision stands until another is made. */
    fun reopen(name: String) {
        if (name !in local.value.choices) return
        local.update { it.copy(reopened = it.reopened + name) }
    }

    /** A suggestion's chip, or a proposal's Accept: the same as choosing [exercise] in the picker. */
    fun use(name: String, exercise: ExerciseCandidate) = choose(name, ImportChoice.Use(exercise))

    /**
     * "Create all remaining" / "Skip all remaining": [choice] for every name
     * still undecided, the others untouched (a reopened row is decided, so it
     * stays open with its decision).
     */
    fun decideRemaining(choice: ImportChoice) {
        local.update { s -> s.copy(choices = s.choices + s.undecided.associateWith { choice }) }
    }

    /** "Accept all high-confidence": each undecided name with a high-confidence proposal takes it; the rest stay as they are. */
    fun acceptAllHighConfidence() {
        local.update { s ->
            val accepted = s.highConfidence.associateWith { name -> ImportChoice.Use(checkNotNull(s.assist.matches.getValue(name).exercise)) }
            s.copy(choices = s.choices + accepted)
        }
    }

    /** "Choose" in the review: remember the name; the picker's answer comes to [onPicked]. */
    fun startPicking(name: String) {
        pickingFor = name
    }

    fun onPicked(ref: ExerciseRef) {
        val name = pickingFor ?: return
        pickingFor = null
        viewModelScope.launch {
            imports.candidate(ref)?.let { use(name, it) }
        }
    }

    /**
     * "Suggest with the assistant": the undecided names (all of them when
     * every one is decided) with their offline suggestions, in one request.
     * "What is sent" first when it is due, like the Assistant screen.
     */
    fun suggestWithAssistant(language: String) {
        val service = ai ?: return
        val settings = aiSettings ?: return
        val current = local.value
        val preview = current.preview ?: return
        if (current.assist.busy) return
        val names = current.undecided.ifEmpty { preview.unmatched }
        if (names.isEmpty()) return
        local.update { it.copy(assist = it.assist.copy(busy = true, failure = null)) }
        viewModelScope.launch {
            val prepared = service.prepareMatch(names, preview.suggestions.filterKeys { it in names }, language)
            if (prepared == null) {
                local.update { it.copy(assist = it.assist.copy(busy = false, failure = AiOutcome.Failed(AiFailure.NOT_CONFIGURED))) }
                return@launch
            }
            val review = !settings.payloadShown.first() || settings.alwaysShowPayload.first()
            if (review) local.update { it.copy(assist = it.assist.copy(review = prepared)) } else send(prepared)
        }
    }

    /** "Send" in "What is sent": the request exactly as it was shown. */
    fun sendReviewed() {
        val prepared = local.value.assist.review ?: return
        local.update { it.copy(assist = it.assist.copy(review = null)) }
        viewModelScope.launch {
            aiSettings?.markPayloadShown()
            send(prepared)
        }
    }

    fun cancelReview() {
        local.update { it.copy(assist = it.assist.copy(review = null, busy = false)) }
    }

    private suspend fun send(prepared: AiPrepared) {
        val service = ai ?: return
        local.update { it.copy(assist = it.assist.copy(sending = true)) }
        val outcome = service.send(prepared)
        val matches = ((outcome as? AiOutcome.Proposed)?.proposal as? AiProposal.Matches)?.matches.orEmpty()
        local.update { s ->
            s.copy(
                assist = s.assist.copy(
                    busy = false,
                    sending = false,
                    matches = s.assist.matches + matches.associateBy { it.name },
                    failure = outcome as? AiOutcome.Failed ?: if (outcome is AiOutcome.Proposed && outcome.proposal !is AiProposal.Matches) AiOutcome.Failed(AiFailure.UNREADABLE) else null,
                ),
            )
        }
    }

    fun import() {
        val current = local.value
        val preview = current.preview ?: return
        if (!current.canImport) return
        local.update { it.copy(phase = ImportPhase.Importing) }
        viewModelScope.launch {
            val result = imports.apply(preview.bundle, preview.matchedChoices + current.choices)
            local.update { it.copy(phase = ImportPhase.Done, result = result) }
        }
    }

    fun undo() {
        val result = local.value.result ?: return
        if (local.value.phase != ImportPhase.Done) return
        local.update { it.copy(phase = ImportPhase.Undoing) }
        viewModelScope.launch {
            val removed = imports.undo(result)
            local.update { it.copy(phase = ImportPhase.Undone, removed = removed) }
        }
    }
}
