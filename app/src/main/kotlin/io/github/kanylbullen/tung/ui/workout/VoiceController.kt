package io.github.kanylbullen.tung.ui.workout

import io.github.kanylbullen.tung.voice.SpeechErrorKind
import io.github.kanylbullen.tung.voice.SpeechEvent
import io.github.kanylbullen.tung.voice.SpeechInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class VoiceUiState(
    /** A recogniser is open: the mic button is filled. */
    val listening: Boolean = false,
    /** Hands-free mode is on: the "Listening" chip shows, between utterances too. */
    val handsFree: Boolean = false,
    /** What the recogniser has heard so far of the utterance in progress. */
    val partial: String? = null,
    val echo: VoiceEcho? = null,
    /** Wall clock when [echo] was shown; the screen hides it a few seconds later. */
    val echoAt: Long = 0L,
)

/**
 * The microphone of the active workout. [listenOnce] hears one utterance;
 * [startHandsFree] keeps listening, [HANDS_FREE_PAUSE_MILLIS] after each
 * result, until [stop], a finish asked for by voice, a fatal error (no
 * recogniser, no permission) or [MAX_ERRORS] failures in a row. Silence is
 * not a failure: between sets the phone mostly hears nothing.
 *
 * Runs on [scope], the screen's composition scope, so leaving the screen
 * stops it. The utterance is turned into a change by the `handle` function
 * the screen passes, which returns what to echo.
 */
class VoiceController(
    private val speech: SpeechInput,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutableState = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = mutableState.asStateFlow()
    private var job: Job? = null

    val active: Boolean get() = job?.isActive == true

    fun listenOnce(locale: Locale, handle: suspend (String) -> VoiceEcho) {
        stop()
        job = scope.launch {
            val echo = listen(locale, handle)
            show(echo)
        }
    }

    /** [onUnderstood] after each utterance that changed something: the haptic. */
    fun startHandsFree(locale: Locale, handle: suspend (String) -> VoiceEcho, onUnderstood: () -> Unit) {
        stop()
        mutableState.update { it.copy(handsFree = true) }
        job = scope.launch {
            try {
                var failures = 0
                while (failures < MAX_ERRORS) {
                    val echo = listen(locale, handle)
                    val silence = echo is VoiceEcho.Failed && echo.kind == SpeechErrorKind.NO_SPEECH
                    if (!silence) show(echo)
                    if (echo.understood) onUnderstood()
                    if (echo is VoiceEcho.Failed && echo.kind.fatal) break
                    if (echo is VoiceEcho.FinishAsked) break
                    failures = if (echo is VoiceEcho.Failed && !silence) failures + 1 else 0
                    delay(HANDS_FREE_PAUSE_MILLIS)
                }
            } finally {
                if (isCurrent()) mutableState.update { it.copy(handsFree = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableState.update { it.copy(listening = false, handsFree = false, partial = null) }
    }

    fun show(echo: VoiceEcho) {
        mutableState.update { it.copy(echo = echo, echoAt = clock()) }
    }

    fun dismissEcho() {
        mutableState.update { it.copy(echo = null) }
    }

    private suspend fun listen(locale: Locale, handle: suspend (String) -> VoiceEcho): VoiceEcho {
        mutableState.update { it.copy(listening = true, partial = null) }
        val last = try {
            speech.listenOnce(locale)
                .onEach { e -> if (e is SpeechEvent.Partial) mutableState.update { it.copy(partial = e.text) } }
                .firstOrNull { it is SpeechEvent.Result || it is SpeechEvent.Error }
        } finally {
            if (isCurrent()) mutableState.update { it.copy(listening = false, partial = null) }
        }
        return when (last) {
            is SpeechEvent.Result -> handle(last.text)
            is SpeechEvent.Error -> VoiceEcho.Failed(last.kind)
            else -> VoiceEcho.Failed(SpeechErrorKind.NO_SPEECH)
        }
    }

    /**
     * Whether the running coroutine is still [job]. A cancelled one finishes
     * after [stop] (which resets the state itself) or after the next start,
     * and must not reset what the new one shows.
     */
    private suspend fun isCurrent(): Boolean = job === currentCoroutineContext()[Job]

    companion object {
        const val HANDS_FREE_PAUSE_MILLIS = 1_000L
        const val MAX_ERRORS = 3
    }
}
