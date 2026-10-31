package io.github.kanylbullen.tung.web.voice

import kotlinx.browser.window
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** How one listen ended, the phone's `SpeechInput` events reduced to what the screen shows. */
sealed interface Heard {
    data class Text(val text: String) : Heard
    data object Nothing : Heard
    data object NotAllowed : Heard
    data object Network : Heard
    data object Failed : Heard
}

/**
 * The browser's speech recognition (`SpeechRecognition`, `webkit`-prefixed
 * in Chrome, Edge and Safari; Firefox has none): one utterance in [tag]'s
 * language, its partial text to [onPartial]. The browser asks for the
 * microphone the first time. Chrome sends the audio to its own service, so
 * it needs a network; the grammar that reads the text is core's and runs
 * here.
 */
object WebSpeech {
    private val constructor: dynamic
        get() = window.asDynamic().SpeechRecognition ?: window.asDynamic().webkitSpeechRecognition

    val available: Boolean get() = constructor != null && constructor != undefined

    suspend fun listenOnce(tag: String, onPartial: (String) -> Unit): Heard = suspendCancellableCoroutine { continuation ->
        val ctor = constructor
        if (ctor == null || ctor == undefined) {
            continuation.resume(Heard.Failed)
            return@suspendCancellableCoroutine
        }
        val recognition: dynamic = js("new ctor()")
        recognition.lang = tag
        recognition.interimResults = true
        recognition.maxAlternatives = 1
        var done = false
        fun finish(heard: Heard) {
            if (done) return
            done = true
            continuation.resume(heard)
        }
        recognition.onresult = { event: dynamic ->
            val results = event.results
            val last = results[results.length - 1]
            val text = (last[0].transcript as String).trim()
            if (last.isFinal == true) finish(if (text.isEmpty()) Heard.Nothing else Heard.Text(text)) else onPartial(text)
        }
        recognition.onerror = { event: dynamic ->
            finish(
                when (event.error as? String) {
                    "no-speech", "aborted" -> Heard.Nothing
                    "not-allowed", "service-not-allowed", "audio-capture" -> Heard.NotAllowed
                    "network" -> Heard.Network
                    else -> Heard.Failed
                },
            )
        }
        recognition.onend = { finish(Heard.Nothing) }
        continuation.invokeOnCancellation { runCatching { recognition.abort() } }
        try {
            recognition.start()
        } catch (e: Throwable) {
            finish(Heard.Failed)
        }
    }
}
