package io.github.kanylbullen.tung.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * The phone's [SpeechRecognizer], one utterance per [listenOnce], on the main
 * thread as the API requires. Offline is preferred (`EXTRA_PREFER_OFFLINE`):
 * where the phone has an on-device model for the language, audio does not
 * leave it. Without a recogniser at all ([SpeechRecognizer.isRecognitionAvailable],
 * which needs the `<queries>` entry in the manifest on Android 11+) the flow
 * answers [SpeechErrorKind.UNAVAILABLE] at once.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    override fun listenOnce(locale: Locale): Flow<SpeechEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Error(SpeechErrorKind.UNAVAILABLE))
            close()
            awaitClose()
            return@callbackFlow
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SpeechEvent.Listening)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstText()?.let { trySend(SpeechEvent.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val text = results.firstText()
                trySend(if (text.isNullOrBlank()) SpeechEvent.Error(SpeechErrorKind.NO_SPEECH) else SpeechEvent.Result(text))
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(kindOf(error)))
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        recognizer.startListening(intent)
        awaitClose {
            recognizer.cancel()
            recognizer.destroy()
        }
    }.flowOn(Dispatchers.Main)

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun kindOf(error: Int): SpeechErrorKind = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechErrorKind.NO_SPEECH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorKind.PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SpeechErrorKind.NETWORK
        else -> if (error == ERROR_LANGUAGE_UNAVAILABLE || error == ERROR_LANGUAGE_NOT_SUPPORTED) SpeechErrorKind.UNAVAILABLE else SpeechErrorKind.OTHER
    }

    private companion object {
        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / _UNAVAILABLE, API 31; the values, so minSdk 26 compiles.
        const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
