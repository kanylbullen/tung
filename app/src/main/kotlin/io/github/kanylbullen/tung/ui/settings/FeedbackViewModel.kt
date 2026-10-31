package io.github.kanylbullen.tung.ui.settings

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.kanylbullen.tung.sync.FeedbackOutcome
import io.github.kanylbullen.tung.sync.SyncAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** As the server: three screenshots, 5000 characters. */
const val MAX_FEEDBACK_SCREENSHOTS = 3
const val MAX_FEEDBACK_MESSAGE = 5_000

enum class FeedbackKind(val wire: String) { BUG("bug"), IDEA("idea") }

/** One screenshot to send: the bytes as they go, and a small picture of them for the screen. */
class Screenshot(val bytes: ByteArray, val thumbnail: ImageBitmap)

data class FeedbackForm(
    val kind: FeedbackKind = FeedbackKind.BUG,
    val message: String = "",
    val screenshots: List<Screenshot> = emptyList(),
    val sending: Boolean = false,
    /** What the last send gave, or a picked file that was not an image ([FeedbackUiState.unreadable]); null before. */
    val outcome: FeedbackOutcome? = null,
    val unreadable: Boolean = false,
) {
    val canSend: Boolean get() = !sending && message.isNotBlank()
    val canAddScreenshot: Boolean get() = screenshots.size < MAX_FEEDBACK_SCREENSHOTS
}

data class FeedbackUiState(val signedIn: Boolean? = null, val form: FeedbackForm = FeedbackForm())

/**
 * Settings → Feedback: a bug or an idea with up to three screenshots, sent
 * with this phone's account to `POST /api/v1/feedback`, where the server's
 * admins read it. Signed out there is nothing to send with.
 */
class FeedbackViewModel(private val account: SyncAccount, private val appVersion: String) : ViewModel() {

    private val form = MutableStateFlow(FeedbackForm())

    val state: StateFlow<FeedbackUiState> = combine(account.state, form) { a, f -> FeedbackUiState(a.signedIn, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedbackUiState())

    fun setKind(kind: FeedbackKind) = form.update { it.copy(kind = kind) }

    fun setMessage(text: String) = form.update { it.copy(message = text.take(MAX_FEEDBACK_MESSAGE), outcome = null, unreadable = false) }

    /** The screenshots the picker gave, read by the screen; a null is a file that was not an image. */
    fun addScreenshots(read: List<Screenshot?>) = form.update { current ->
        val room = MAX_FEEDBACK_SCREENSHOTS - current.screenshots.size
        current.copy(
            screenshots = current.screenshots + read.filterNotNull().take(room),
            unreadable = read.any { it == null },
            outcome = null,
        )
    }

    fun removeScreenshot(index: Int) = form.update { current ->
        current.copy(screenshots = current.screenshots.filterIndexed { i, _ -> i != index })
    }

    fun send() {
        val current = form.value
        if (!current.canSend) return
        form.update { it.copy(sending = true, outcome = null, unreadable = false) }
        viewModelScope.launch {
            val outcome = account.sendFeedback(current.kind.wire, current.message, current.screenshots.map { it.bytes }, "android", appVersion)
            form.update {
                if (outcome == FeedbackOutcome.SENT) FeedbackForm(kind = it.kind, outcome = outcome)
                else it.copy(sending = false, outcome = outcome)
            }
        }
    }
}
