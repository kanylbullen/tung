package io.github.kanylbullen.tung.flows

import io.github.kanylbullen.tung.voice.SpeechErrorKind
import io.github.kanylbullen.tung.voice.SpeechEvent
import io.github.kanylbullen.tung.voice.SpeechInput
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Locale

/**
 * The microphone of the flow tests: each [listenOnce] reports
 * [SpeechEvent.Listening] and then waits for the next line the test queues
 * with [say] (or a failure with [fail]), as a person would take their time.
 * Lines queued before the mic is tapped are heard in order.
 */
class FakeSpeechInput : SpeechInput {
    private val heard = Channel<SpeechEvent>(Channel.UNLIMITED)

    /** How many times listening started. */
    @Volatile
    var listens = 0
        private set

    /** True while a [listenOnce] is being collected: the recogniser would be open. */
    @Volatile
    var listening = false
        private set

    @Volatile
    var lastLocale: Locale? = null
        private set

    fun say(text: String) {
        heard.trySend(SpeechEvent.Result(text))
    }

    fun fail(kind: SpeechErrorKind) {
        heard.trySend(SpeechEvent.Error(kind))
    }

    override fun listenOnce(locale: Locale): Flow<SpeechEvent> = flow {
        listens++
        lastLocale = locale
        listening = true
        try {
            emit(SpeechEvent.Listening)
            emit(heard.receive())
        } finally {
            listening = false
        }
    }
}
