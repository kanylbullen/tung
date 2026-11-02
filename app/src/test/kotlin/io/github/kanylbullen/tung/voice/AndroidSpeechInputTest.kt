package io.github.kanylbullen.tung.voice

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognitionSupport
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.time.Duration

/**
 * The real [AndroidSpeechInput] over Robolectric's recogniser: which language it asks for, that
 * one recogniser asks and listens, and that a missing language pack (error 12) moves on to the
 * next variant instead of ending the turn.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AndroidSpeechInputTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val events = mutableListOf<SpeechEvent>()
    private val input = AndroidSpeechInput(context)

    @Before
    fun setUp() {
        val service = ComponentName("com.google.android.tts", "com.google.android.tts.Recognition")
        shadowOf(context.packageManager).run {
            addServiceIfNotPresent(service)
            addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        ShadowSpeechRecognizer.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun idleFor(millis: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    private fun start() {
        scope.launch { input.listenOnce("en").collect { events += it } }
        idle()
    }

    private fun latest() = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())

    private fun listen(support: RecognitionSupport) {
        start()
        val asked = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        latest().triggerSupportResult(support)
        idle()
        // The recogniser that asked is the one that listens: destroying it cut off the next one on a Pixel.
        assertSame(asked, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertFalse(latest().isDestroyed)
    }

    private fun Intent.asked() = getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) to getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)

    private fun heard(text: String) = Bundle().apply { putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text)) }

    @Test
    fun asksForTheVariantInstalledOnThePhone() {
        listen(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("en-US")).setOnlineLanguages(listOf("en-US")).build())
        assertEquals("en-US" to true, latest().lastRecognizerIntent.asked())
        latest().triggerOnResults(heard("80 kilo 8 reps"))
        idle()
        assertEquals(listOf<SpeechEvent>(SpeechEvent.Result("80 kilo 8 reps")), events)
    }

    @Test
    fun aMissingLanguagePackMovesOnToTheNextVariant() {
        listen(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("en-US")).setOnlineLanguages(listOf("en-GB")).build())
        val first = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        latest().triggerOnError(12)
        idleFor(200)
        // A new recogniser, since the service tears the session down after the error; the first is kept.
        assertNotSame(first, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertFalse(shadowOf(first).isDestroyed)
        assertEquals("en-GB" to false, latest().lastRecognizerIntent.asked())
        latest().triggerOnResults(heard("klar"))
        idle()
        assertEquals(listOf<SpeechEvent>(SpeechEvent.Result("klar")), events)
    }

    @Test
    fun whenNoVariantHasALanguagePackTheErrorSaysSo() {
        listen(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("en-US")).build())
        latest().triggerOnError(12)
        idleFor(200)
        assertEquals(listOf<SpeechEvent>(SpeechEvent.Error(SpeechErrorKind.LANGUAGE)), events)
    }

    @Test
    fun anotherErrorEndsTheTurnAtOnce() {
        listen(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("en-US")).setOnlineLanguages(listOf("en-GB")).build())
        latest().triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle()
        assertEquals(listOf<SpeechEvent>(SpeechEvent.Error(SpeechErrorKind.NO_SPEECH)), events)
    }

    @Test
    fun withoutAnAnswerItListensInAnotherRecogniserWithTheUsualVariant() {
        start()
        val asked = ShadowSpeechRecognizer.getLatestSpeechRecognizer()
        idleFor(1600)
        // The check may still be running in the first: listening goes to a second.
        assertNotSame(asked, ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        assertEquals("en-US" to true, latest().lastRecognizerIntent.asked())
    }

    @Test
    fun anErrorFromTheCheckFallsBackTheSameWay() {
        start()
        latest().triggerSupportError(SpeechRecognizer.ERROR_CLIENT)
        idle()
        assertEquals("en-US" to true, latest().lastRecognizerIntent.asked())
    }

    @Test
    fun theAnswerIsAskedForOnce() {
        listen(RecognitionSupport.Builder().setInstalledOnDeviceLanguages(listOf("en-GB")).build())
        latest().triggerOnResults(heard("ten reps"))
        idle()
        start()
        // No support check this time: the new recogniser listens at once, in the variant learnt before.
        assertEquals("en-GB" to true, latest().lastRecognizerIntent.asked())
    }
}
