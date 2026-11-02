package io.github.kanylbullen.tung.voice

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * The phone's [SpeechRecognizer], one utterance per [listenOnce], on the main
 * thread as the API requires. Offline is preferred (`EXTRA_PREFER_OFFLINE`):
 * where the phone has an on-device model for the language, audio does not
 * leave it. Without a recogniser at all ([SpeechRecognizer.isRecognitionAvailable],
 * which needs the `<queries>` entry in the manifest on Android 11+) the flow
 * answers [SpeechErrorKind.UNAVAILABLE] at once.
 *
 * The language is the app's, in a variant the recogniser has: [recognizerAttempts] picks the
 * tags from what it says it has (Android 13+, asked once per instance), and a language error
 * (12 or 13, no pack for that variant) moves on to the next attempt, online when the phone has
 * none (the privacy page says so), before anything is heard. When every attempt fails that way
 * the flow answers [SpeechErrorKind.LANGUAGE].
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {
    /** The recogniser's answer, kept once it gave one: it does not change while the app runs. */
    private var support: RecognizerLanguages? = null

    override fun listenOnce(languageTag: String): Flow<SpeechEvent> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechEvent.Error(SpeechErrorKind.UNAVAILABLE))
            close()
            awaitClose()
            return@callbackFlow
        }
        // The platform keeps one connection to the recognition service per app, so a recogniser is
        // destroyed only when the turn ends: destroying one that had only asked what it supports cut
        // off the next before it listened (seen on a Pixel, 2026-11-02).
        val recognizers = mutableListOf<SpeechRecognizer>()
        fun newRecognizer() = SpeechRecognizer.createSpeechRecognizer(context).also { recognizers += it }
        var recognizer = newRecognizer()
        val known = support ?: try {
            recognizer.supportedLanguages()?.also { support = it }
        } catch (cancelled: CancellationException) {
            recognizers.forEach { it.destroy() }
            throw cancelled
        }
        // No answer (in time): the check may still be running in that recogniser, so listen in another.
        if (known == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            recognizer = newRecognizer()
        }
        val attempts = recognizerAttempts(languageTag, systemLocaleTag(), known).iterator()
        val listener = object : RecognitionListener {
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
                when {
                    !isLanguageError(error) -> trySend(SpeechEvent.Error(kindOf(error)))
                    attempts.hasNext() -> {
                        // The service tears its session down after a language error (onDestroy in the
                        // Pixel's log): the next attempt goes to a new recogniser, after that is done.
                        val next = attempts.next()
                        launch {
                            delay(RETRY_DELAY_MILLIS)
                            recognizer = newRecognizer()
                            listen(recognizer, next)
                        }
                        return
                    }
                    else -> trySend(SpeechEvent.Error(SpeechErrorKind.LANGUAGE))
                }
                close()
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            fun listen(on: SpeechRecognizer, attempt: RecognizerAttempt) {
                on.setRecognitionListener(this)
                on.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, attempt.tag)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, attempt.preferOffline)
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
                )
            }
        }
        listener.listen(recognizer, attempts.next())
        awaitClose {
            recognizers.forEach {
                it.cancel()
                it.destroy()
            }
        }
    }.flowOn(Dispatchers.Main)

    /**
     * The phone's own locale, not the app's: with a per-app language the default locale is the app's
     * ("sv", no region), while the system's still says where the phone is.
     */
    private fun systemLocaleTag(): String =
        Resources.getSystem().configuration.locales.get(0)?.toLanguageTag() ?: Locale.getDefault().toLanguageTag()

    /**
     * The languages the recogniser has on the device and online, or null before Android 13, on an
     * error, or when it does not answer within [SUPPORT_TIMEOUT_MILLIS].
     */
    private suspend fun SpeechRecognizer.supportedLanguages(): RecognizerLanguages? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return withTimeoutOrNull(SUPPORT_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { continuation ->
                checkRecognitionSupport(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                    ContextCompat.getMainExecutor(context),
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(support: RecognitionSupport) {
                            if (continuation.isActive) {
                                continuation.resume(RecognizerLanguages(support.installedOnDeviceLanguages, support.onlineLanguages))
                            }
                        }

                        override fun onError(error: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        }
    }

    private fun Bundle?.firstText(): String? =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun kindOf(error: Int): SpeechErrorKind = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechErrorKind.NO_SPEECH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechErrorKind.PERMISSION
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> SpeechErrorKind.NETWORK
        else -> SpeechErrorKind.OTHER
    }

    private companion object {
        const val SUPPORT_TIMEOUT_MILLIS = 1500L
        const val RETRY_DELAY_MILLIS = 150L
    }
}
