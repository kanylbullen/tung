package io.github.kanylbullen.tung.voice

import kotlinx.coroutines.flow.Flow

/**
 * Speech to text for one utterance. The phone's own recogniser in the app
 * (`AndroidSpeechInput`); the flow tests script a fake through
 * `TungApp.createSpeechInput()`. What it hears is only text: turning it into
 * an intent is core's grammar (`core/voice/`), and into a change the
 * workout's commands.
 */
interface SpeechInput {
    /**
     * Listens once in the language of [languageTag], the language the app shows ("sv", "en"; the
     * recogniser picks a regional variant it has): [SpeechEvent.Listening] when the recogniser is
     * ready, [SpeechEvent.Partial] while words come in, then exactly one
     * [SpeechEvent.Result] or [SpeechEvent.Error], after which the flow ends.
     * Cancelling the collection stops listening.
     */
    fun listenOnce(languageTag: String): Flow<SpeechEvent>
}

sealed interface SpeechEvent {
    data object Listening : SpeechEvent

    data class Partial(val text: String) : SpeechEvent

    data class Result(val text: String) : SpeechEvent

    data class Error(val kind: SpeechErrorKind) : SpeechEvent
}

enum class SpeechErrorKind {
    /** Silence, or nothing the recogniser could match: not a failure, nothing was said. */
    NO_SPEECH,

    /** No recogniser on this phone (de-Googled phones often have none). */
    UNAVAILABLE,

    /** The recogniser has no variant of the app's language, on the phone or online. */
    LANGUAGE,

    /** No microphone permission. */
    PERMISSION,

    /** The recogniser needs a network and has none (no offline model for the language). */
    NETWORK,

    /** The recogniser is busy with another app, or failed in its own way. */
    OTHER;

    /** Listening again cannot help: hands-free mode stops at once. */
    val fatal: Boolean get() = this == UNAVAILABLE || this == LANGUAGE || this == PERMISSION
}
