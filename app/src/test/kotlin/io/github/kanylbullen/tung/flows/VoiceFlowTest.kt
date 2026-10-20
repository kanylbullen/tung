package io.github.kanylbullen.tung.flows

import android.Manifest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.isEnabled
import io.github.kanylbullen.tung.R
import io.github.kanylbullen.tung.core.ai.AiProviderKind
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The microphone in the active workout, with [FakeSpeechInput] standing in
 * for the phone's recogniser: a tap hears one utterance and changes the
 * current set through the usual commands, a long press keeps listening until
 * the chip is tapped, and what is not understood is said so, with the
 * assistant offered when one is set up.
 */
class VoiceFlowTest : FlowTest() {

    private val mic get() = hasContentDescription(str(R.string.voice_mic))
    private val chip get() = hasContentDescription(str(R.string.voice_hands_free_description))
    private val benchPress get() = str(R.string.ex_bench_press)

    private fun logSet(weight: String, reps: String) = str(R.string.workout_log_set, benchPress, weight, reps)

    @Before
    fun allowTheMicrophone() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    private fun tapMic() {
        waitFor(mic)
        compose.onNode(mic).performClick()
        waitUntil { app.speech.listening }
    }

    private fun benchWorkout() {
        startWorkoutFromHome()
        addExercise(if (str(R.string.ex_bench_press) == "Bänkpress") "bänk" else "bench", benchPress)
        waitForText(logSet("0", str(R.string.workout_reps_empty)))
    }

    private fun loggedSets(): Int = count("SELECT COUNT(*) FROM set_entry WHERE completed = 1 AND weightKg = 80.0 AND reps = 8")

    /** "Åttio kilo åtta reps" on a Swedish phone fills the current set; "klar" logs it like the button. */
    @Test
    @Config(qualifiers = "sv-w411dp-h891dp-xhdpi")
    fun aTapHearsOneSetAndKlarLogsIt() {
        benchWorkout()
        tapMic()
        screenshot("1-listening")
        assertEquals("sv", app.speech.lastLocale?.language)
        app.speech.say("åttio kilo åtta reps")
        waitForText(logSet("80", "8"))
        waitForText(str(R.string.voice_echo_values, "80", "8"))
        waitUntil { !app.speech.listening }
        screenshot("2-understood")
        assertEquals(0, loggedSets())

        tapMic()
        app.speech.say("klar")
        waitForDescription(str(R.string.workout_mark_undone, 1))
        waitForText(str(R.string.voice_echo_logged, "80", "8"))
        screenshot("3-logged")
        assertEquals(1, loggedSets())
        // As the button: the next set is there, pre-filled, and the rest runs.
        waitForDescription(str(R.string.workout_mark_done, 2))
        waitFor(hasContentDescription(str(R.string.rest_title)))
        assertEquals(2, app.speech.listens)
    }

    /** A long press keeps listening: two utterances, one set logged, and the chip turns it off. */
    @Test
    @Config(qualifiers = "sv-w411dp-h891dp-xhdpi")
    fun handsFreeKeepsListeningUntilTheChipIsTapped() {
        benchWorkout()
        waitFor(mic)
        compose.onNode(mic).performTouchInput { longClick() }
        waitFor(chip)
        waitUntil { app.speech.listening }
        screenshot("1-hands-free")

        app.speech.say("åttio kilo åtta reps")
        waitForText(logSet("80", "8"))
        // Listening again by itself, a moment after the result.
        waitUntil(timeoutMillis = 10_000) { app.speech.listens >= 2 && app.speech.listening }
        app.speech.say("klar")
        waitForDescription(str(R.string.workout_mark_undone, 1))
        waitUntil(timeoutMillis = 10_000) { app.speech.listens >= 3 && app.speech.listening }
        assertEquals(1, loggedSets())
        screenshot("2-two-utterances")

        compose.onNode(chip).performClick()
        waitGone(chip)
        waitUntil { !app.speech.listening }
        val listens = app.speech.listens
        compose.waitForIdle()
        assertEquals(listens, app.speech.listens)
        screenshot("3-stopped")
    }

    @Test
    fun whatIsNotUnderstoodIsEchoedAndNothingChanges() {
        benchWorkout()
        tapMic()
        app.speech.say("eighty something")
        waitForText(str(R.string.voice_echo_not_understood, "eighty something"))
        screenshot("1-not-understood")
        // No assistant set up, so none is offered.
        assertTrue(compose.onAllNodes(hasText(str(R.string.voice_ask_assistant))).fetchSemanticsNodes().isEmpty())
        waitForText(logSet("0", str(R.string.workout_reps_empty)))
        assertEquals(0, count("SELECT COUNT(*) FROM set_entry WHERE reps > 0 OR weightKg > 0"))
    }

    /**
     * With an assistant set up, words the grammar does not know go to it as a
     * free-text request: the first request is shown before it goes, and its
     * workout plan is applied to the workout in progress once confirmed.
     */
    @Test
    fun notUnderstoodCanBeAskedOfTheAssistant() {
        val server = MockWebServer()
        server.start()
        try {
            runBlocking {
                app.container.aiSettings.save(AiProviderKind.OPENAI_COMPATIBLE, server.url("/v1").toString().trimEnd('/'), "test-model", jsonMode = true)
                app.container.aiSettings.setKey("sk-flow-test")
            }
            val plan = """{"rationale": "As you said.", "exercises": [{"key": "back_squat", "sets": [{"weight_kg": 60, "reps": 8}, {"weight_kg": 60, "reps": 8}, {"weight_kg": 60, "reps": 8}]}]}"""
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json").setBody(
                    Json.encodeToString(
                        JsonObject.serializer(),
                        buildJsonObject {
                            put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", plan) }) }) })
                        },
                    ),
                ),
            )

            benchWorkout()
            tapMic()
            val heard = "then three sets of squats at sixty"
            app.speech.say(heard)
            waitForText(str(R.string.voice_echo_not_understood, heard))
            screenshot("1-ask-the-assistant")
            clickText(str(R.string.voice_ask_assistant))

            waitForText(str(R.string.ai_voice_heard, heard))
            waitForText(str(R.string.ai_payload_title))
            screenshot("2-what-is-sent")
            assertEquals(0, server.requestCount)
            clickText(str(R.string.ai_payload_send))

            val start = hasText(str(R.string.ai_start_workout)) and isEnabled()
            waitFor(start)
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains(heard))
            compose.onNode(start).performScrollTo().performClick()

            waitForText(str(R.string.ex_back_squat))
            waitForText(str(R.string.workout_finish))
            assertEquals(3, count("SELECT COUNT(*) FROM set_entry WHERE weightKg = 60.0 AND reps = 8 AND completed = 0"))
        } finally {
            server.shutdown()
        }
    }
}
