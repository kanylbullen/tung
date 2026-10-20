package io.github.kanylbullen.tung.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.ai.AiConfirmation
import io.github.kanylbullen.tung.ai.AiFailure
import io.github.kanylbullen.tung.ai.AiOutcome
import io.github.kanylbullen.tung.ai.AiPrepared
import io.github.kanylbullen.tung.ai.AiService
import io.github.kanylbullen.tung.ai.AiSettings
import io.github.kanylbullen.tung.core.ai.AiProposal
import io.github.kanylbullen.tung.core.ai.AiRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AssistantUiState(
    /** Null until the settings are read. */
    val configured: Boolean? = null,
    /** The request being prepared, reviewed or sent; the buttons wait meanwhile. */
    val busy: AiRequest? = null,
    /** True while the request is out with the provider. */
    val sending: Boolean = false,
    /** The request on review in "What is sent", before it goes out. */
    val review: AiPrepared? = null,
    val proposal: AiProposal? = null,
    val failure: AiOutcome.Failed? = null,
    /** The menu's "Show what is sent": every request is reviewed first. */
    val alwaysShowPayload: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/**
 * The Assistant screen: three questions, one call per tap. The first request
 * ever is shown in full before it goes out, and every one after that too
 * while "Show what is sent" is on. A proposal changes nothing until it is
 * confirmed; then it goes through the commands ([AiService.confirm]).
 */
class AssistantViewModel(private val ai: AiService, private val settings: AiSettings) : ViewModel() {

    private val local = MutableStateFlow(AssistantUiState())

    val state: StateFlow<AssistantUiState> =
        combine(local, settings.config, settings.alwaysShowPayload) { s, config, always ->
            s.copy(configured = config.configured, alwaysShowPayload = always)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AssistantUiState())

    private val askedOnce = mutableSetOf<AiRequest>()

    /** [ask], but only the first time for [request]: a request the screen makes on arrival is not repeated on rotation. */
    fun askOnce(request: AiRequest, language: String) {
        if (askedOnce.add(request)) ask(request, language)
    }

    fun ask(request: AiRequest, language: String) {
        if (local.value.busy != null) return
        local.value = local.value.copy(busy = request, proposal = null, failure = null, saveFailed = false)
        viewModelScope.launch {
            val prepared = ai.prepare(request, language)
            if (prepared == null) {
                local.value = local.value.copy(busy = null, failure = AiOutcome.Failed(AiFailure.NOT_CONFIGURED))
                return@launch
            }
            val review = !settings.payloadShown.first() || settings.alwaysShowPayload.first()
            if (review) local.value = local.value.copy(review = prepared) else send(prepared)
        }
    }

    /** "Send" in the review sheet: the request exactly as it was shown. */
    fun sendReviewed() {
        val prepared = local.value.review ?: return
        local.value = local.value.copy(review = null)
        viewModelScope.launch {
            settings.markPayloadShown()
            send(prepared)
        }
    }

    fun cancelReview() {
        local.value = local.value.copy(review = null, busy = null)
    }

    fun setAlwaysShowPayload(show: Boolean) {
        viewModelScope.launch { settings.setAlwaysShowPayload(show) }
    }

    private suspend fun send(prepared: AiPrepared) {
        local.value = local.value.copy(sending = true)
        val outcome = ai.send(prepared)
        local.value = local.value.copy(
            busy = null,
            sending = false,
            proposal = (outcome as? AiOutcome.Proposed)?.proposal,
            failure = outcome as? AiOutcome.Failed,
        )
    }

    /** "Save program": the routines and a rotating program with them, then [onSaved]. */
    fun saveProgram(defaultName: String, onSaved: () -> Unit) {
        val proposal = local.value.proposal as? AiProposal.Program ?: return
        if (local.value.saving) return
        local.value = local.value.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            val result = ai.confirm(proposal, proposal.programName ?: defaultName)
            local.value = local.value.copy(saving = false, saveFailed = result !is AiConfirmation.Saved, proposal = if (result is AiConfirmation.Saved) null else proposal)
            if (result is AiConfirmation.Saved) onSaved()
        }
    }

    /** "Start this workout": the plan applied to the workout in progress or a new one, then [onStarted] with it. */
    fun startWorkout(onStarted: (String) -> Unit) {
        val proposal = local.value.proposal as? AiProposal.NextWorkout ?: return
        if (local.value.saving) return
        local.value = local.value.copy(saving = true, saveFailed = false)
        viewModelScope.launch {
            val result = ai.confirm(proposal)
            local.value = local.value.copy(saving = false, saveFailed = result !is AiConfirmation.Started, proposal = if (result is AiConfirmation.Started) null else proposal)
            if (result is AiConfirmation.Started) onStarted(result.workoutId)
        }
    }
}
